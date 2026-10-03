package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The SBOM adapter's facts: one stored document is one identity, spelled the way the {@code
 * SoftwareRelease} event spells the release it describes, and the belt counts per package rather
 * than per repository — every artifact on the platform publishes into the one {@code sboms} root.
 *
 * <p>The parsing is what this suite pins hardest. A package name carries {@code @}, {@code /} and
 * {@code :} of its own, so an identity is only decomposable because the <b>version</b> may carry
 * none of them: {@code SbomPaths}' {@code VERSION} charset admits no {@code @}. Every case below
 * uses a real name of each shape, and the delete case proves the round trip through the collection
 * door rather than through a string assertion.
 */
@QuarkusTest
class SbomGcAdapterTest extends GcFixture {

  private static final String SBOM_REPO = "sboms";
  /** A maven coordinate — one value carrying groupId, a colon and artifactId. */
  private static final String MAVEN_NAME = "eu.wohlben.qits:qits-eventstream";
  /** A scoped npm name — an {@code @} at the front and a {@code /} in the middle. */
  private static final String NPM_NAME = "@qits/ui-components";

  @Inject SbomGcStrategy strategy;
  @Inject SbomGcAdapter adapter;

  @Test
  void anIdentityIsTheReleaseCoordinateAndTheVersionIsWhatFollowsTheLastAt() throws Exception {
    // Three names of the three awkward shapes, each with a calver document and a sha one. The point
    // is that the last '@' separates the version in all six, and that only the calver rows are
    // releases — the release pipelines publish calver, anything else lives on the window alone.
    repository();
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.801.30", blob(11), daysAgo(10), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "a".repeat(40), blob(12), daysAgo(10), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", blob(13), daysAgo(10), null);
    sbomRow(SBOM_REPO, "docker", "qits/qits-artifacts", "2026.801.30", blob(14), daysAgo(10), null);

    List<GcCandidate> candidates = adapter.enumerate();

    assertEquals(
        Set.of(
            "maven/" + MAVEN_NAME + "@2026.801.30",
            "maven/" + MAVEN_NAME + "@" + "a".repeat(40),
            "npm/" + NPM_NAME + "@2026.801.30",
            "docker/qits/qits-artifacts@2026.801.30"),
        candidates.stream().map(GcCandidate::identity).collect(Collectors.toSet()));
    assertEquals(
        SBOM_REPO + "/npm/" + NPM_NAME,
        candidate(candidates, "npm/" + NPM_NAME + "@2026.801.30").group(),
        "the belt counts per package: two packages must not spend each other's slots");
    assertTrue(
        candidate(candidates, "maven/" + MAVEN_NAME + "@2026.801.30").released(),
        "a calver version is a release");
    assertFalse(
        candidate(candidates, "maven/" + MAVEN_NAME + "@" + "a".repeat(40)).released(),
        "a sha version is not");
    assertEquals(
        Set.of("2026.801.30", "a".repeat(40)),
        candidates.stream()
            .map(candidate -> versionOf(candidate.identity()))
            .collect(Collectors.toSet()),
        "lastIndexOf('@'), not indexOf — a scoped npm name starts with one");
  }

  @Test
  void theBeltKeepsTheLastTwoReleasedDocumentsOfEveryPackage() throws Exception {
    // Three calver documents of one package: the newest two hold their belt slots whatever their
    // age, the third goes. A second package with one old document keeps it — groups do not spend
    // each other's slots — and a sha document holds no slot at all, which since the window went to
    // P0D on 2026-09-05 means it holds nothing at all.
    repository();
    String doomed = blob(21);
    String doomedSha = blob(22);
    backdate(doomed, Duration.ofDays(30));
    backdate(doomedSha, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.601.10", doomed, daysAgo(400), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.701.20", blob(23), daysAgo(300), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.801.30", blob(24), daysAgo(200), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "b".repeat(40), doomedSha, daysAgo(300), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.601.10", blob(25), daysAgo(400), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Set.of(
            "maven/" + MAVEN_NAME + "@2026.601.10",
            "maven/" + MAVEN_NAME + "@" + "b".repeat(40)),
        Set.copyOf(identities(plan.dead())));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "maven/" + MAVEN_NAME + "@2026.701.20"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "maven/" + MAVEN_NAME + "@2026.801.30"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "npm/" + NPM_NAME + "@2026.601.10"),
        "the quiet package's one release keeps its own slot");
  }

  @Test
  void aReleasedDocumentIsKeptWhileItsMavenCoordinateIsStillStored() throws Exception {
    // qits-739: maven keeps every hosted release forever, so a belt-displaced document whose jar
    // is still here is a bill of materials torn off a release the store still serves. The
    // third-oldest is kept because its coordinate is present; the fourth-oldest, whose coordinate
    // is not stored, keeps today's belt-and-window answer and dies.
    repository();
    repositoryService.ensure(MAVEN_REPO, MavenPackagesProfile.KEY);
    mavenRow("eu/wohlben/qits/qits-eventstream/2026.601.10/qits-eventstream-2026.601.10.jar");
    // A sibling artifact holding the very version the doomed document names: presence is per
    // coordinate, so it must not answer for `qits-eventstream`.
    mavenRow(
        "eu/wohlben/qits/qits-eventstream-x/2026.501.5/qits-eventstream-x-2026.501.5.jar");
    String doomed = blob(71);
    backdate(doomed, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.501.5", doomed, daysAgo(500), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.601.10", blob(72), daysAgo(400), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.701.20", blob(73), daysAgo(300), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.801.30", blob(74), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of("maven/" + MAVEN_NAME + "@2026.501.5"), identities(plan.dead()));
    assertEquals(
        SbomGcAdapter.KEPT_ARTIFACT_PRESENT,
        ruleFor(plan.kept(), "maven/" + MAVEN_NAME + "@2026.601.10"),
        "the jar is still served, so its bill of materials must be too");
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "maven/" + MAVEN_NAME + "@2026.801.30"));
  }

  @Test
  void aReleasedDocumentIsKeptWhileItsNpmVersionIsStillStored() throws Exception {
    // The same pair for npm, which keeps every hosted release for the same reason maven does.
    repository();
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    npmRow(NPM_NAME, "2026.601.10");
    npmRow("@qits/other", "2026.501.5"); // the version, under a different name: no answer for ours
    String doomed = blob(81);
    backdate(doomed, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.501.5", doomed, daysAgo(500), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.601.10", blob(82), daysAgo(400), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.701.20", blob(83), daysAgo(300), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", blob(84), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of("npm/" + NPM_NAME + "@2026.501.5"), identities(plan.dead()));
    assertEquals(
        SbomGcAdapter.KEPT_ARTIFACT_PRESENT,
        ruleFor(plan.kept(), "npm/" + NPM_NAME + "@2026.601.10"));
  }

  @Test
  void aReleasedDocumentIsKeptWhileItsImageTagOrDaemonVersionIsStillStored() throws Exception {
    // docker names `<repository>/<image>` and the tag is the version; daemon names the daemon.
    // Each has a third-oldest document whose artifact is here and a fourth whose artifact is not.
    repository();
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    String config = store(filled(91, (byte) 91));
    byte[] manifest = imageManifest(config, Map.of());
    String digest = store(manifest);
    imageTag("qits-artifacts", "2026.601.10", digest, manifest.length);
    repositoryService.ensure(DAEMON_REPO, DaemonBinariesProfile.KEY);
    daemonRow("qits-ci-daemon", "2026.601.10", blob(92), daysAgo(400), null);
    int seed = 0;
    for (String[] described :
        List.of(
            new String[] {"docker", "qits/qits-artifacts"},
            new String[] {"daemon", "qits-ci-daemon"})) {
      String doomed = store(filled(100 + ++seed, (byte) seed));
      backdate(doomed, Duration.ofDays(30));
      sbomRow(SBOM_REPO, described[0], described[1], "2026.501.5", doomed, daysAgo(500), null);
      for (String version : List.of("2026.601.10", "2026.701.20", "2026.801.30")) {
        String bytes = store((described[0] + version).getBytes(StandardCharsets.UTF_8));
        sbomRow(SBOM_REPO, described[0], described[1], version, bytes, daysAgo(300), null);
      }
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Set.of("docker/qits/qits-artifacts@2026.501.5", "daemon/qits-ci-daemon@2026.501.5"),
        Set.copyOf(identities(plan.dead())));
    assertEquals(
        SbomGcAdapter.KEPT_ARTIFACT_PRESENT,
        ruleFor(plan.kept(), "docker/qits/qits-artifacts@2026.601.10"));
    assertEquals(
        SbomGcAdapter.KEPT_ARTIFACT_PRESENT,
        ruleFor(plan.kept(), "daemon/qits-ci-daemon@2026.601.10"));
  }

  @Test
  void aShaDocumentIsNotKeptByItsArtifact() throws Exception {
    // Only a calver document is a release's bill of materials; a sha document whose version
    // happens to exist as an artifact still lives on the window alone, which at P0D is nothing.
    repository();
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String sha = "c".repeat(40);
    npmRow(NPM_NAME, sha);
    String doomed = blob(95);
    backdate(doomed, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "npm", NPM_NAME, sha, doomed, daysAgo(300), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of("npm/" + NPM_NAME + "@" + sha), identities(plan.dead()));
  }

  @Test
  void aCollectedDocumentGoesThroughTheCollectionDoorAndNoOtherWay() throws Exception {
    // The delete half, driven end to end: the condemned row is gone from the store afterwards, the
    // surviving ones are untouched, and nothing here reaches past SbomRegistryCollection.
    repository();
    String doomed = blob(31);
    backdate(doomed, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.601.10", doomed, daysAgo(400), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.701.20", blob(32), daysAgo(300), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", blob(33), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of("npm/" + NPM_NAME + "@2026.601.10"), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    sbomDocuments.getEntityManager().clear();
    assertTrue(sbomDocuments.findOne(SBOM_REPO, "npm", NPM_NAME, "2026.601.10").isEmpty());
    assertTrue(sbomDocuments.findOne(SBOM_REPO, "npm", NPM_NAME, "2026.701.20").isPresent());
  }

  @Test
  void aDocumentWhoseBlobIsStillInsideTheGraceWindowIsWithheldWhole() throws Exception {
    // Deleting the row over a blob still inside the window would strand the blob: row-less blobs
    // are untouchable by construction, so it would never be reclaimed at all.
    repository();
    String young = blob(41);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.601.10", young, daysAgo(400), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.701.20", blob(42), daysAgo(300), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", blob(43), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(young));

    assertEquals(List.of(), applied.deleted());
    assertEquals(
        List.of("npm/" + NPM_NAME + "@2026.601.10"),
        identities(applied.withheldByGraceWindow()));
    sbomDocuments.getEntityManager().clear();
    assertTrue(sbomDocuments.findOne(SBOM_REPO, "npm", NPM_NAME, "2026.601.10").isPresent());
  }

  @Test
  void aRowThatVanishedBetweenThePlanAndTheSweepIsAnErrorLineRatherThanAThrow() throws Exception {
    // A plan is computed moments before it is applied, but "moments" is not "atomically": a
    // republish or a concurrent run can move the store underneath it. One error line, and the rest
    // of the plan still executes — a whole type failing on one missing row would be the worse
    // answer.
    repository();
    String doomed = blob(51);
    backdate(doomed, Duration.ofDays(30));
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.601.10", doomed, daysAgo(400), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.701.20", blob(52), daysAgo(300), null);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", blob(53), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    execute("delete from sbom_document where version = '2026.601.10'");
    sbomDocuments.getEntityManager().clear();
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(), applied.deleted());
    assertEquals(1, applied.errors().size());
    assertTrue(
        applied.errors().get(0).contains("the store moved since planning"), applied.errors().get(0));
  }

  @Test
  void theRetainedSetIsTheSurvivingDocumentsBlobsInTheCensusVocabulary() throws Exception {
    // What a strategy hands the substrate is blobs, never identities — and this type's retained set
    // has to equal the census's own live set for it when nothing dies, or the reconciliation is
    // comparing two different readings of the same store.
    repository();
    String kept = blob(61);
    String other = blob(62);
    sbomRow(SBOM_REPO, "npm", NPM_NAME, "2026.801.30", kept, daysAgo(10), null);
    sbomRow(SBOM_REPO, "maven", MAVEN_NAME, "2026.801.30", other, daysAgo(10), null);
    String rowless = blob(63);

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(Set.of(kept, other), plan.blobsRetained());
    assertEquals(taken.live(SbomProfile.KEY).keySet(), plan.blobsRetained());
    assertFalse(plan.blobsRetained().contains(rowless), "no row ever named it");
    assertEquals(SbomProfile.KEY, strategy.type());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void mavenRow(String path) throws IOException {
    String blobId = store(path.getBytes(StandardCharsets.UTF_8));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MavenArtifact row = new MavenArtifact();
              row.repository = MAVEN_REPO;
              row.path = path;
              row.blobId = blobId;
              row.sizeBytes = path.length();
              row.createdAt = Instant.now();
              mavenArtifacts.persist(row);
            });
  }

  private void npmRow(String packageName, String version) throws IOException {
    String blobId = store((packageName + version).getBytes(StandardCharsets.UTF_8));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = "npm";
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = Instant.now();
              npmVersions.persist(row);
            });
  }

  private void imageTag(String image, String tag, String digest, long size) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciManifest manifest = new OciManifest();
              manifest.repository = "qits";
              manifest.imageName = image;
              manifest.digest = digest;
              manifest.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
              manifest.size = size;
              manifest.createdAt = Instant.now();
              ociManifests.persist(manifest);
              OciTag row = new OciTag();
              row.repository = "qits";
              row.imageName = image;
              row.tag = tag;
              row.manifestDigest = digest;
              row.updatedAt = Instant.now();
              ociTags.persist(row);
            });
  }

  private void repository() {
    repositoryService.ensure(SBOM_REPO, SbomProfile.KEY);
  }

  private String blob(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf('@') + 1);
  }

  private static GcCandidate candidate(List<GcCandidate> candidates, String identity) {
    return candidates.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> new AssertionError("not enumerated: " + identity));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> kept, String identity) {
    return kept.stream()
        .filter(entry -> entry.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError("not kept at all: " + identity));
  }
}
