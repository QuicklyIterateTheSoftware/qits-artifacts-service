package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.blobstore.entity.ArtifactRecord;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositorySweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The executed sweep, end to end against real rows and real files.
 *
 * <p>The dry-run suites prove what a plan says; this one proves what happens when it is applied.
 * The cases are the ways the answer must be "nothing" — a file inside the grace window, a blob
 * another type still serves, a blob that never had a row — plus the one way it must be "gone":
 * matured content whose last identity a strategy's own rule condemned. Each deletion case asserts
 * the whole chain: the row, the tombstone where the type has one, the file, and the receipt.
 *
 * <p>Strategies are passed in one by one rather than taken from the registry, so a case controls
 * exactly what runs, and the pins are passed in as a value — {@link GcPins#none()} for the cases below,
 * which is "nothing is pinned and nothing is broken". The strategies are the real beans, because
 * {@code collect}'s tombstone-and-refusal mechanics are half of what is on trial.
 * The whole-run abort, which is what an incomplete aggregate causes, is {@code GcPinsTest}'s.
 */
@QuarkusTest
class GcSweepExecutorTest extends GcFixture {

  @Inject GcSweepExecutor executor;
  @Inject OciImageGcStrategy ociStrategy;
  @Inject NpmPackagesGcStrategy npmStrategy;

  private static final String PKG = "@qits/sweep-case";
  private static final String RELEASE = "2026.801.85149";
  private static final String OLDER = RELEASE + "-main.g0000000";
  private static final String SUPERSEDED = RELEASE + "-main.g1111111";
  private static final String NEWEST = RELEASE + "-main.g2222222";

  @Test
  void aColdPrereleaseLosesItsRowGainsATombstoneAndItsTarballIsUnlinked() throws Exception {
    // The full npm chain, executed: the engine condemns, collect() deletes the row and writes the
    // tombstone in one transaction, and the sweep unlinks the tarball nothing references any more.
    // The release is as cold as the condemned build and survives on the belt.
    //
    // The newer build survives too, and since 2026-09-05 for a DIFFERENT reason, which is the whole
    // realignment in one fixture: at a zero window a build published a moment ago is condemned like
    // any other unnamed coordinate, and what saves it is the six-hour grace on its bytes — which
    // withholds the identity WHOLE, row and tarball together. Its tarball is therefore deliberately
    // not backdated, and the receipt reports it as withheld rather than deleted.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String releaseBlob = agedBlob(41);
    String supersededBlob = agedBlob(42);
    String newestBlob = store(filled(43, (byte) 43)); // as young as a fresh publish
    versionRow(PKG, RELEASE, releaseBlob, daysAgo(400));
    versionRow(PKG, SUPERSEDED, supersededBlob, daysAgo(400));
    versionRow(PKG, NEWEST, newestBlob, Instant.now());

    GcSweepReport report = executor.execute(census.take(), List.of(npmStrategy), GcPins.none());

    assertFalse(report.dryRun());
    assertNotNull(report.executedAt());
    assertEquals("PT6H", report.graceWindow());
    GcTypeSweepResult npm = typeResult(report, NpmPackagesProfile.KEY);
    assertNull(npm.error());
    assertEquals(List.of(PKG + "@" + SUPERSEDED), identities(npm.deleted()));
    assertEquals(
        List.of(PKG + "@" + NEWEST),
        identities(npm.withheldByGraceWindow()),
        "the just-published build: condemned by the rule, withheld by the grace, row intact");

    detachEntities();
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isEmpty(), "the row is gone");
    assertTrue(
        npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isPresent(),
        "the tombstone is written, so the name can never be silently republished");
    assertFalse(blobStore.exists(supersededBlob), "the tarball file is unlinked");
    assertTrue(blobStore.exists(releaseBlob), "the only release survives on the belt");
    assertTrue(npmVersions.findOne("npm", PKG, NEWEST).isPresent(), "and so does the fresh publish");
    assertTrue(blobStore.exists(newestBlob));

    assertEquals(1, report.sweep().blobsUnlinked());
    assertEquals(42L, report.sweep().bytesReclaimed());
    assertEquals(List.of(supersededBlob), report.sweep().unlinkedBlobIds());
    assertEquals(1, report.sweep().withheldByGraceWindow());
    assertEquals(43L, report.sweep().withheldBytes());
    assertEquals(0, report.sweep().stillReferenced());
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsTheWholeIdentityRowIntactNothingUnlinked()
      throws Exception {
    // The strand hazard, closed: deleting the row first would leave the young blob row-less — and
    // row-less blobs are untouchable by construction, so it would never be reclaimed at all. The
    // identity waits out the window with its file, and the next run past it takes both together.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String supersededBlob = store(filled(52, (byte) 52)); // NOT backdated: as young as a fresh push
    String releaseBlob = store(filled(53, (byte) 53));
    // The ROW is cold even though its file is young — a republish of the same bytes after a long
    // silence, which is exactly the shape the two clocks exist to tell apart. The second row is a
    // RELEASE, so the belt keeps it and this case is about one identity meeting one young file.
    versionRow(PKG, SUPERSEDED, supersededBlob, daysAgo(400));
    versionRow(PKG, RELEASE, releaseBlob, daysAgo(400));

    GcSweepReport report = executor.execute(census.take(), List.of(npmStrategy), GcPins.none());

    GcTypeSweepResult npm = typeResult(report, NpmPackagesProfile.KEY);
    assertEquals(List.of(), npm.deleted());
    assertEquals(List.of(PKG + "@" + SUPERSEDED), identities(npm.withheldByGraceWindow()));
    assertNull(npm.error(), "withheld is not an error — it is the window working");

    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isEmpty(), "no tombstone");
    assertTrue(blobStore.exists(supersededBlob), "the file stays");
    assertEquals(0, report.sweep().blobsUnlinked());
    assertEquals(1, report.sweep().withheldByGraceWindow());
    assertEquals(52L, report.sweep().withheldBytes());
  }

  @Test
  void theMechanismRefusesADistTagNamedVersionEvenWhenAPlanCondemnsIt() throws Exception {
    // The policy never condemns a dist-tag-named version; this proves the MECHANISM refuses one
    // anyway. A hand-built plan stands in for the policy bug, and collect()'s 409 lands in the
    // receipt's error column instead of breaking the packument.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String taggedBlob = agedBlob(62);
    versionRow(PKG, SUPERSEDED, taggedBlob, daysAgo(400));
    distTagRow(PKG, "main", SUPERSEDED);
    GcStrategy.Plan condemned =
        new GcStrategy.Plan(
            List.of(
                new GcIdentity(
                    "npm",
                    PKG + "@" + SUPERSEDED,
                    OwnArtifactsStrategy.deadUnaccessed(Duration.ofDays(30)))),
            List.of(),
            Set.of(taggedBlob),
            Set.of());

    GcStrategy.Applied applied = npmStrategy.apply(condemned, blobId -> false);

    assertEquals(List.of(), applied.deleted());
    assertEquals(1, applied.errors().size());
    assertTrue(applied.errors().get(0).contains("dist-tag"), applied.errors().get(0));
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", PKG, SUPERSEDED).isEmpty(), "no tombstone");
  }

  @Test
  void ociDeletesColdTagsAndUnreachableManifestsNeverAKeptIdentityAndSharedContentSurvives()
      throws Exception {
    // The whole OCI chain plus the three survival rules in one store: a kept identity's rows are
    // untouched, a blob the npm type still serves outlives its own type's deletion, and a row-less
    // blob outlives everything — asserted through the executor, not just the plan.
    //
    // Rows carry their ages here because the settled rule is access-gated: the dead sha tag is two
    // months cold and the orphan manifest older still, while the calver release and the newest build
    // were written moments ago.
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String config = agedBlob(10);
    String layerKept = agedBlob(100);
    String layerShared = agedBlob(200);
    String layerDoomed = agedBlob(300);
    String layerOrphan = agedBlob(150);
    String rowless = agedBlob(500);

    byte[] keptBytes = imageManifest(config, Map.of(layerKept, 100L, layerShared, 200L));
    byte[] doomedBytes = imageManifest(config, Map.of(layerDoomed, 300L, layerShared, 200L));
    byte[] orphanBytes = imageManifest(config, Map.of(layerOrphan, 150L));
    String manifestKept = agedStore(keptBytes);
    String manifestDoomed = agedStore(doomedBytes);
    String manifestOrphan = agedStore(orphanBytes);

    String deadSha = "a".repeat(40);
    String newestSha = "b".repeat(40);
    Instant cold = Instant.now().minus(Duration.ofDays(60));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ociManifests.persist(qitsManifest(manifestKept, keptBytes.length, Instant.now()));
              ociManifests.persist(qitsManifest(manifestDoomed, doomedBytes.length, Instant.now()));
              ociManifests.persist(qitsManifest(manifestOrphan, orphanBytes.length, cold));
              ociTags.persist(qitsTag("2026.801.5", manifestKept, Instant.now()));
              ociTags.persist(qitsTag(newestSha, manifestKept, Instant.now()));
              ociTags.persist(qitsTag(deadSha, manifestDoomed, cold));
            });
    // The cross-type case: the npm registry serves the doomed layer's bytes as a release tarball.
    versionRow(PKG, RELEASE, layerDoomed, Instant.now());

    GcSweepReport report =
        executor.execute(census.take(), List.of(ociStrategy, npmStrategy), GcPins.none());

    GcTypeSweepResult oci = typeResult(report, OciImagesProfile.KEY);
    assertNull(oci.error());
    assertEquals(
        List.of(
            "alpha8:" + deadSha,
            "alpha8:" + newestSha,
            "alpha8@sha256:" + manifestOrphan),
        identities(oci.deleted()).stream().sorted().toList(),
        "both sha tags — since 2026-09-05 the newest one has no claim on anything either — and the"
            + " manifest no tag has ever named");
    assertEquals(List.of(), oci.withheldByGraceWindow());

    // Never a kept identity: both kept tag rows and the kept manifest row are exactly where they
    // were, and the dead rows are gone. The manifest the dead tag named is NOT gone — a tagged
    // manifest's identity is its tag, so it becomes an untagged manifest today and is collected on
    // the next run, which is the mirror's shape verbatim.
    detachEntities();
    assertTrue(ociTags.findOne("qits", "alpha8", "2026.801.5").isPresent(), "the release tag");
    assertTrue(
        ociTags.findOne("qits", "alpha8", newestSha).isEmpty(),
        "and the sha tag beside it goes: the release is what a deploy pulls, and nothing pulls a"
            + " sha coordinate any more");
    assertTrue(
        ociManifests.findOne("qits", "alpha8", manifestKept).isPresent(),
        "their shared manifest stays, because the release tag still names it");
    assertTrue(ociTags.findOne("qits", "alpha8", deadSha).isEmpty());
    assertTrue(ociManifests.findOne("qits", "alpha8", manifestOrphan).isEmpty());
    assertTrue(
        ociManifests.findOne("qits", "alpha8", manifestDoomed).isPresent(),
        "one run, one class of identity — the row that lost its tag goes next time");

    // The blobs: dead-only content goes, and everything a surviving ROW still names stays. The
    // pre-unlink re-census is what enforces that second half — the plan released the doomed
    // manifest's closure with the tag, and the fresh census answers that a live row still holds it.
    assertEquals(
        List.of(layerOrphan, manifestOrphan).stream().sorted().toList(),
        report.sweep().unlinkedBlobIds());
    assertFalse(blobStore.exists(manifestOrphan));
    assertFalse(blobStore.exists(layerOrphan));
    assertTrue(blobStore.exists(manifestDoomed), "its row outlived its tag by one run");
    assertTrue(blobStore.exists(layerDoomed), "npm still serves these bytes as a tarball");
    assertTrue(blobStore.exists(layerShared), "the kept manifest still names it");
    assertTrue(blobStore.exists(config), "the kept manifest still names it");
    assertTrue(blobStore.exists(layerKept));
    assertEquals(
        1,
        report.sweep().stillReferenced(),
        "the doomed manifest's own bytes: released by the plan, held by a row that survived it");

    // Row-less blobs are untouchable straight through an executed sweep, matured or not.
    assertTrue(blobStore.exists(rowless), "no identity ever named it, so nothing can reach it");
    assertTrue(report.untouchable().blobIds().contains(rowless));
  }

  @Test
  void theStubsPlanNothingAtZeroRowsAndRefuseOnceRowsExist() throws Exception {
    // Zero rows: nothingDies under the note naming the intended rule — the honest caption, on the
    // receipt as on the plan. Rows: the stub fails closed rather than guess with an unimplemented
    // rule, and the refusal names what to implement.
    repositoryService.ensure("shots", CiScreenshotsProfile.KEY);
    repositoryService.ensure("clips", CiVideosProfile.KEY);

    GcSweepReport quiet =
        executor.execute(census.take(), List.of(screenshotsStub(), videosStub()), GcPins.none());
    GcTypeSweepResult shots = typeResult(quiet, CiScreenshotsProfile.KEY);
    GcTypeSweepResult clips = typeResult(quiet, CiVideosProfile.KEY);
    // The receipt says what the plan says about absence: excluded by configuration, then the stub's
    // own caption. A receipt that only carried the caption would leave an operator reading "nothing
    // was deleted" with no way to tell a decision from a gap.
    assertEquals(GcRules.EXCLUDED_NOTE + CiScreenshotsGcStrategy.NOTE, shots.note());
    assertEquals(GcRules.EXCLUDED_NOTE + CiVideosGcStrategy.NOTE, clips.note());
    assertNull(shots.error());
    assertNull(clips.error());
    assertEquals(List.of(), shots.deleted());
    assertEquals(List.of(), clips.deleted());
    assertEquals(0, quiet.sweep().blobsUnlinked());

    String screenshot = agedBlob(77);
    recordRow("shots", screenshot);
    GcSweepReport refused =
        executor.execute(census.take(), List.of(screenshotsStub(), videosStub()), GcPins.none());
    GcTypeSweepResult refusedShots = typeResult(refused, CiScreenshotsProfile.KEY);
    assertNotNull(refusedShots.error());
    assertTrue(refusedShots.error().contains("stub"), refusedShots.error());
    assertTrue(refusedShots.error().contains("branch"), "the refusal names the rule to implement");
    assertNull(typeResult(refused, CiVideosProfile.KEY).error(), "videos still has no rows");
    assertTrue(blobStore.exists(screenshot), "fail-closed keeps every blob of the type");
  }

  @Test
  void aScopedSweepDeletesOneRepositorysRowsAndNeverBytesItsTwinStillNames() throws Exception {
    // Per-repository collection, executed, with the hazard it exists to survive in the middle of
    // it: two npm repositories hold the same tarball bytes, and both their prereleases are cold, so
    // a whole-store run would free those bytes. Scoped to one repository it must not — the other's
    // row is standing — while content only this repository names still goes. The scoped plan's
    // retained set is what stops it before the re-census or the store's guard ever have to.
    ArtifactRepository npm = repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    repositoryService.ensure("npm2", NpmPackagesProfile.KEY);
    String releaseBlob = agedBlob(91);
    String release2Blob = agedBlob(92);
    String sharedBlob = agedBlob(93);
    String mineOnlyBlob = agedBlob(94);
    versionRow("npm", PKG, RELEASE, releaseBlob, daysAgo(400));
    versionRow("npm", PKG, SUPERSEDED, sharedBlob, daysAgo(400));
    versionRow("npm", PKG, OLDER, mineOnlyBlob, daysAgo(400));
    versionRow("npm2", PKG, RELEASE, release2Blob, daysAgo(400));
    versionRow("npm2", PKG, SUPERSEDED, sharedBlob, daysAgo(400));

    GcRepositorySweepReport report =
        executor.execute(npm, census.take(), List.of(npmStrategy), GcPins.none());

    assertEquals("npm", report.repository());
    assertEquals("npm-packages", report.type());
    assertFalse(report.dryRun());
    assertNull(report.aborted());
    assertNull(report.error());
    assertEquals(
        List.of(PKG + "@" + OLDER, PKG + "@" + SUPERSEDED),
        identities(report.deleted()).stream().sorted().toList(),
        "both of this repository's cold prereleases, and neither of the other's");

    detachEntities();
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isEmpty(), "this repository's row goes");
    assertTrue(
        npmVersions.findOne("npm2", PKG, SUPERSEDED).isPresent(),
        "the twin's identically cold row is out of scope and is not touched");
    assertTrue(npmVersions.findOne("npm2", PKG, RELEASE).isPresent());

    assertEquals(List.of(mineOnlyBlob), report.sweep().unlinkedBlobIds());
    assertFalse(blobStore.exists(mineOnlyBlob), "content only this repository named is freed");
    assertTrue(
        blobStore.exists(sharedBlob),
        "and content the twin still rows survives, which a whole-store run would have freed");
    assertTrue(blobStore.exists(releaseBlob), "the only release survives on the belt");
    assertTrue(blobStore.exists(release2Blob));
  }

  @Test
  void aScopedRunWhosePinSourcesCannotAnswerAbortsWholeAndRefusesAnUnknownRepository()
      throws Exception {
    // The abort rule does not shrink with the scope, and it must not: blobs dedupe globally, so
    // the bytes one repository releases can be the last local reference to content qits-ci pins by
    // digest. This suite's pin urls are closed ports, so this is the deployed behaviour under a
    // broken dependency rather than a simulated one.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String coldBlob = agedBlob(95);
    versionRow("npm", PKG, SUPERSEDED, coldBlob, daysAgo(400));

    GcRepositorySweepReport report = executor.sweep("npm");

    assertNotNull(report.aborted());
    assertTrue(report.aborted().contains("qits-platform-deployments"), report.aborted());
    assertTrue(report.aborted().contains("qits-ci"), report.aborted());
    assertTrue(report.aborted().contains("qits-platform-maintenance"), report.aborted());
    assertTrue(report.aborted().contains("qits-configuration"), report.aborted());
    assertTrue(report.aborted().contains("qits-workspaces"), report.aborted());
    assertTrue(report.aborted().contains("qits-projects"), report.aborted());
    assertEquals(List.of(), report.deleted());
    assertEquals(0, report.sweep().blobsUnlinked());
    assertEquals(6, report.pins().size(), "an aborted receipt still says how it read its pins");
    assertTrue(
        report.untouchable().reason().contains("not computed"),
        "no census was taken, so the pool is uncomputed rather than empty");

    detachEntities();
    assertTrue(npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(), "nothing was deleted");
    assertTrue(blobStore.exists(coldBlob));

    assertThrows(
        NotFoundException.class,
        () -> executor.sweep("no-such-repository"),
        "a name that is not a repository is a 404, never a wider sweep");
  }

  @Test
  void aRepositoryWhoseTypeNobodyCollectsGetsAReceiptSayingSoRatherThanAnError() throws Exception {
    // "Report rather than throw", at repository scope. A row of an unclaimed type is a legitimate
    // thing to ask about, and the honest answer is a receipt with the reason and zeros — not a
    // status code the caller has to interpret. The explorer never offers the button for such a row,
    // and the route does not depend on that.
    ArtifactRepository shots = repositoryService.ensure("shots", CiScreenshotsProfile.KEY);

    GcRepositorySweepReport report =
        executor.execute(shots, census.take(), List.of(npmStrategy), GcPins.none());

    assertNull(report.strategy());
    assertEquals("no strategy registered for ci-screenshots", report.note());
    assertNull(report.error());
    assertEquals(List.of(), report.deleted());
    assertEquals(0, report.sweep().blobsUnlinked());
    assertNotNull(report.untouchable().reason(), "a census WAS taken, so the pool is a reading");
  }

  // --- wiring ----------------------------------------------------------------------------------

  private CiScreenshotsGcStrategy screenshotsStub() {
    CiScreenshotsGcStrategy strategy = new CiScreenshotsGcStrategy();
    strategy.repositories = repositories;
    strategy.records = records;
    return strategy;
  }

  private CiVideosGcStrategy videosStub() {
    CiVideosGcStrategy strategy = new CiVideosGcStrategy();
    strategy.repositories = repositories;
    strategy.records = records;
    return strategy;
  }

  /**
   * Clears the request-scoped persistence context before asserting on database state.
   *
   * <p>The repo's documented landmine, met head-on: inside a {@code @QuarkusTest} a request context
   * is already active, {@code findOne} resolves through {@code findById}, and a row the sweep
   * deleted (and committed) is still answered from the first-level cache — a lost delete that is a
   * property of the test, not of the service.
   */
  private void detachEntities() {
    npmVersions.getEntityManager().clear();
  }

  /** A stored blob aged past the grace window — the executed cases' default. */
  private String agedBlob(int size) throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private String agedStore(byte[] bytes) throws IOException {
    String blobId = store(bytes);
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private void versionRow(String packageName, String version, String blobId, Instant createdAt) {
    versionRow("npm", packageName, version, blobId, createdAt);
  }

  /** The same, in a named repository — the scoping cases need two of them at once. */
  private void versionRow(
      String repository, String packageName, String version, String blobId, Instant createdAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = repository;
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = createdAt;
              npmVersions.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private void distTagRow(String packageName, String tag, String version) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmDistTag row = new NpmDistTag();
              row.repository = "npm";
              row.packageName = packageName;
              row.tag = tag;
              row.version = version;
              row.updatedAt = Instant.now();
              npmDistTags.persist(row);
            });
  }

  private void recordRow(String repository, String blobId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              ArtifactRecord row = new ArtifactRecord();
              row.id = java.util.UUID.randomUUID().toString();
              row.repository = repository;
              row.blobId = blobId;
              row.mediatype = "image/png";
              row.size = 77;
              row.createdAt = Instant.now();
              row.metadata = Map.copyOf(screenshotMeta("main", "flow", 1, 1));
              records.persist(row);
            });
  }

  private OciManifest qitsManifest(String digest, long size, Instant createdAt) {
    OciManifest row = new OciManifest();
    row.repository = "qits";
    row.imageName = "alpha8";
    row.digest = digest;
    row.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
    row.size = size;
    row.createdAt = createdAt;
    return row;
  }

  private OciTag qitsTag(String name, String digest, Instant updatedAt) {
    OciTag row = new OciTag();
    row.repository = "qits";
    row.imageName = "alpha8";
    row.tag = name;
    row.manifestDigest = digest;
    row.updatedAt = updatedAt;
    return row;
  }

  private static GcTypeSweepResult typeResult(GcSweepReport report, String type) {
    return report.types().stream()
        .filter(result -> RepositoryTypeProfile.wireNameOf(type).equals(result.type()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }
}
