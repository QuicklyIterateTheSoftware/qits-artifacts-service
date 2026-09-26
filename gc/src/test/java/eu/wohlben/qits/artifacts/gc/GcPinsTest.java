package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a run does when a pin source cannot answer: the sweep refuses whole, the dry-run degrades.
 *
 * <p>This suite runs under the shipped test configuration, which points all six pin urls at a
 * closed port — so every case here is the deployed failure rather than a simulated one. None of the
 * six peers exists in this repository, and that is what makes the refusal path the easy one to
 * exercise honestly.
 *
 * <p><b>The settlement's rule is all-or-nothing, and the second case is why.</b> It would be
 * tempting to let the types that read no pins run anyway — npm's rule needs nothing from qits-platform-deployments.
 * But blobs dedupe globally: a tarball npm releases may be the last reference to bytes an image a
 * deployment pins also names, and with the pins unavailable nothing can tell. So the run stops
 * before the census, and the receipt says why.
 */
@QuarkusTest
class GcPinsTest extends GcFixture {

  /** The eight types this deployment registers — see {@code GcPlanTest} for the arithmetic. */
  private static final int REGISTERED_TYPES = 8;

  @Inject GcPlanner planner;
  @Inject GcSweepExecutor executor;
  @Inject GcPinSources pinSources;

  private static final String DIGEST =
      "3ff84c05e2a01c3f2b9d1e8a7c6b5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c";
  private static final String PKG = "@qits/pins-case";
  private static final String RELEASE = "2026.801.85149";
  private static final String SUPERSEDED = RELEASE + "-main.g1111111";
  private static final String NEWEST = RELEASE + "-main.g2222222";

  @Test
  void everyPinSourceFailingIsReportedRatherThanThrown() {
    GcPins pins = pinSources.fetch();

    assertFalse(pins.complete());
    assertEquals(6, pins.failures().size(), "every source is asked, so every outage is named");
    assertTrue(pins.whyIncomplete().contains("qits-platform-deployments"));
    assertTrue(pins.whyIncomplete().contains("qits-ci"));
    assertTrue(pins.whyIncomplete().contains("qits-platform-maintenance"));
    assertTrue(pins.whyIncomplete().contains("qits-configuration"));
    assertTrue(pins.whyIncomplete().contains("qits-workspaces"));
    assertTrue(pins.whyIncomplete().contains("qits-projects"));

    // And the provenance says which url produced which outage, so a fix starts from the report
    // rather than from a grep for the config key.
    assertEquals(6, pins.sources().size());
    GcPinSource cd = source(pins, "qits-platform-deployments");
    assertFalse(cd.answered());
    assertTrue(cd.url().endsWith("/deployments/api/pins"), cd.url());
    assertTrue(cd.outcome().contains("qits-platform-deployments"), cd.outcome());
    assertEquals(0, cd.pinCount());
    assertEquals(List.of(), cd.keeps());
    assertTrue(source(pins, "qits-ci").url().endsWith("/ci/api/daemon"));
    assertTrue(source(pins, "qits-platform-maintenance").url().endsWith("/maintenance/api/pins"));
    assertTrue(source(pins, "qits-configuration").url().endsWith("/configuration/api/pins"));
    assertTrue(source(pins, "qits-workspaces").url().endsWith("/workspaces/api/pins"));
    assertTrue(source(pins, "qits-projects").url().endsWith("/projects/api/pins"));

    // And a failed source leaves NO half-folded keep-set behind: a report claiming coordinates a
    // source did not finish stating would be the one thing worse than zeros.
    assertEquals(java.util.Set.of(), pins.mavenDependencies());
    assertEquals(java.util.Set.of(), pins.npmDependencies());
    assertEquals(java.util.Set.of(), pins.manifestImages());
    assertEquals(java.util.Set.of(), pins.daemonDependencies());
    assertEquals(java.util.Set.of(), pins.configuredImages());
    assertEquals(java.util.Set.of(), pins.workspaceLaunchImages());
    assertEquals(java.util.Set.of(), pins.projectLaunchImages());
  }

  @Test
  void aSourceThatAnsweredReportsItsUrlItsCountsAndTheKeepsItProduced() {
    // The pins section a healthy run carries, which is the half of a keep-set a reviewer can check
    // against their own deployments: not "3 pins" but which image at which sha, and — for the
    // daemon — the blob a digest rung protects beside the row it names. Built by hand because
    // neither qits-platform-deployments nor qits-ci exists in this repository.
    GcPinSources sources = answering();
    sources.cd =
        () ->
            List.of(
                new CdDeploymentPins.ApplicationPin("qits-ci", List.of("aaaa", "bbbb")),
                new CdDeploymentPins.ApplicationPin("qits-platform-deployments", List.of("cccc")));
    sources.ci = () -> new CiDaemonPins.DaemonPin("qits-ci-daemon", DIGEST, "2026.802.40", "adopted");

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    GcPinSource cd = source(pins, "qits-platform-deployments");
    assertTrue(cd.answered());
    assertEquals(2, cd.pinCount(), "applications, which is what the deployer answers with");
    // Sorted: the keeps are a TreeSet, so this is name order and not the order the pins arrived in.
    assertEquals(List.of("qits-ci:aaaa", "qits-ci:bbbb", "qits-platform-deployments:cccc"), cd.keeps());
    assertTrue(cd.outcome().contains("3 image shas"), cd.outcome());
    assertTrue(cd.tookMillis() >= 0);

    GcPinSource ci = source(pins, "qits-ci");
    assertEquals(2, ci.pinCount(), "both rungs of the ladder pin");
    assertEquals(
        List.of("blob " + DIGEST, "qits-ci-daemon@2026.802.40", "qits-ci-daemon@" + DIGEST),
        ci.keeps(),
        "a 64-hex rung pins the bytes as well as the row, and the section shows both");
    assertTrue(ci.outcome().contains("adopted"), ci.outcome());
  }

  @Test
  void aBlankDaemonVersionIsReportedAsAnAnswerRatherThanAsAnOutage() {
    // The shipped default on a platform that has published no daemon. It must read as a source that
    // answered with nothing pinned, because a reviewer who reads it as an outage goes looking for a
    // broken qits-ci that is working perfectly.
    GcPinSources sources = answering();
    sources.ci = () -> new CiDaemonPins.DaemonPin("", "", "", "none");

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    GcPinSource ci = source(pins, "qits-ci");
    assertTrue(ci.answered());
    assertEquals(0, ci.pinCount());
    assertEquals(List.of(), ci.keeps());
    assertTrue(ci.outcome().contains("an answer, not an absence"), ci.outcome());
  }

  @Test
  void theTwoConsumptionSourcesFoldIntoTheirEcosystemsOwnCoordinateSpelling() {
    // The fold that makes the P3D windows defensible: what repositories still build against, and
    // what the platform is configured to launch, in the exact string each adapter's identities are
    // spelled with — so the lookup on the other side is an equality test and never a translation.
    // A maven pin joins on "g:a:v", an npm pin on "name@version", a docker pin and a configured
    // image on "full/image:tag".
    GcPinSources sources = answering();
    sources.maintenance =
        () ->
            List.of(
                new MaintenanceDependencyPins.DependencyPin(
                    "maven",
                    "eu.wohlben.qits:qits-blobstore",
                    "2026.903.85122",
                    "qits-githost-service",
                    "pom.xml"),
                new MaintenanceDependencyPins.DependencyPin(
                    "npm",
                    "@qits/ui-components",
                    "2026.902.204627",
                    "qits-spa-home",
                    "package.json"),
                new MaintenanceDependencyPins.DependencyPin(
                    "docker",
                    "qits/workspace-base",
                    "2026.902.143920",
                    "qits-workspace-daemon",
                    "docker/Dockerfile"),
                // The same version referenced twice: the keep-set is a set, and the pin COUNT is
                // still the number of references, which is what the receipt reports.
                new MaintenanceDependencyPins.DependencyPin(
                    "maven",
                    "eu.wohlben.qits:qits-blobstore",
                    "2026.903.85122",
                    "qits-ci-service",
                    "pom.xml"));
    sources.configuration =
        () ->
            List.of(
                new ConfigurationImagePins.ImagePin(
                    "qits/workspace",
                    "2026.904.160522",
                    "qits-workspaces",
                    "env.QITS_WORKSPACE_IMAGE_VERSION"),
                // Two applications, one image version — one keep, two rows.
                new ConfigurationImagePins.ImagePin(
                    "qits/workspace",
                    "2026.904.160522",
                    "qits-projects",
                    "env.QITS_PROJECTS_REFINEMENT_IMAGE_VERSION"));

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    assertEquals(
        java.util.Set.of("eu.wohlben.qits:qits-blobstore:2026.903.85122"),
        pins.mavenDependencies());
    assertEquals(java.util.Set.of("@qits/ui-components@2026.902.204627"), pins.npmDependencies());
    assertEquals(java.util.Set.of("qits/workspace-base:2026.902.143920"), pins.manifestImages());
    assertEquals(java.util.Set.of("qits/workspace:2026.904.160522"), pins.configuredImages());

    assertEquals(GcPins.BY_MANIFEST, pins.pinsMavenCoordinate("eu.wohlben.qits:qits-blobstore:2026.903.85122"));
    assertEquals(GcPins.BY_MANIFEST, pins.pinsNpmCoordinate("@qits/ui-components@2026.902.204627"));
    assertEquals(GcPins.BY_MANIFEST, pins.pinsManifestImage("qits/workspace-base", "2026.902.143920"));
    assertEquals(GcPins.BY_CONFIGURATION, pins.pinsConfiguredImage("qits/workspace", "2026.904.160522"));
    assertNull(pins.pinsConfiguredImage("qits/workspace-base", "2026.902.143920"));

    GcPinSource maintenance = source(pins, "qits-platform-maintenance");
    assertEquals(4, maintenance.pinCount(), "references, which is what maintenance answers with");
    assertEquals(
        List.of(
            "@qits/ui-components@2026.902.204627",
            "eu.wohlben.qits:qits-blobstore:2026.903.85122",
            "qits/workspace-base:2026.902.143920"),
        maintenance.keeps(),
        "the three coordinates the four references resolve to, in name order");
    assertTrue(maintenance.outcome().contains("1 maven, 1 npm, 1 docker"), maintenance.outcome());

    GcPinSource configuration = source(pins, "qits-configuration");
    assertEquals(2, configuration.pinCount(), "entries, which is what configuration answers with");
    assertEquals(List.of("qits/workspace:2026.904.160522"), configuration.keeps());
  }

  @Test
  void aDaemonPinFoldsOnTheDaemonAdaptersOwnIdentitySpellingAndCarriesItsOwnReason() {
    // The fourth ecosystem of the maintenance source, and the two things about it that matter here.
    //
    // It joins on `name@version` — DaemonBinariesGcAdapter's identity verbatim, separator and all —
    // because a keep-set the other side has to translate is a keep-set that will one day translate
    // wrong. And it carries a reason of its OWN: the row arrives beside maven and npm rows in one
    // document, but "a pom names this jar" and "a pom names a jar whose release also shipped this
    // executable" are different claims, and the receipt is what a reviewer reads to tell them apart.
    GcPinSources sources = answering();
    sources.maintenance =
        () ->
            List.of(
                new MaintenanceDependencyPins.DependencyPin(
                    "maven",
                    "eu.wohlben.qits:qits-platform-access-cli-binary",
                    "2026.917.65806",
                    "qits-ci-service",
                    "pom.xml"),
                // The carrier above, resolved by maintenance to the binary it ships. Same version,
                // because the daemons store files the CLI under the coordinate's own version.
                new MaintenanceDependencyPins.DependencyPin(
                    "daemon",
                    "qits-platform-access-cli",
                    "2026.917.65806",
                    "qits-platform-access-cli",
                    "pom.xml"));

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    assertEquals(
        java.util.Set.of("qits-platform-access-cli@2026.917.65806"), pins.daemonDependencies());
    assertEquals(
        GcPins.BY_CARRIED_DAEMON,
        pins.pinsCarriedDaemon("qits-platform-access-cli@2026.917.65806"));
    assertNull(
        pins.pinsCarriedDaemon("qits-ci-daemon@2026.917.65806"),
        "a version of a DIFFERENT daemon is not what the carrier shipped");
    assertNull(
        pins.pinsDaemonVersion("qits-platform-access-cli", "2026.917.65806"),
        "and it is not the ladder's keep either — two sources, two sets, two sentences");

    GcPinSource maintenance = source(pins, "qits-platform-maintenance");
    assertEquals(2, maintenance.pinCount());
    assertEquals(
        List.of(
            "eu.wohlben.qits:qits-platform-access-cli-binary:2026.917.65806",
            "qits-platform-access-cli@2026.917.65806"),
        maintenance.keeps(),
        "the carrier and what it carries, both in the one section a reviewer reads");
    assertTrue(maintenance.outcome().contains("1 daemon"), maintenance.outcome());
  }

  @Test
  void theTwoEffectiveSourcesAreSeparateKeepSetsAndMayDisagreeWithTheConfiguredOne() {
    // The residual the fifth source could not close, written as a fixture: qits-configuration says
    // the workspace image is 2026.904.160522 — what the NEXT deploy of qits-workspaces will be
    // handed — while qits-workspaces itself is still running 2026.903.120000 and pulling that. Both
    // must survive, and they are three different coordinates over two images.
    //
    // Two sets rather than one because qits/workspace is launched by BOTH services, and a report has
    // to be able to say which of them saved a tag.
    GcPinSources sources = answering();
    sources.configuration =
        () ->
            List.of(
                new ConfigurationImagePins.ImagePin(
                    "qits/workspace",
                    "2026.904.160522",
                    "qits-workspaces",
                    "env.QITS_WORKSPACE_IMAGE_VERSION"));
    sources.workspaces =
        () ->
            List.of(
                new LaunchImagePins.LaunchPin("qits/workspace", "2026.903.120000", "workspace"),
                new LaunchImagePins.LaunchPin(
                    "qits/workspace-editor", "2026.904.100239", "editor"));
    sources.projects =
        () ->
            List.of(
                new LaunchImagePins.LaunchPin("qits/project-agent", "2026.903.090000", "agent"),
                // The same image and the same version the workspace service answers with — one
                // coordinate in each set, and it is not a duplicate to be folded away.
                new LaunchImagePins.LaunchPin("qits/workspace", "2026.903.120000", "refinement"));

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    assertEquals(java.util.Set.of("qits/workspace:2026.904.160522"), pins.configuredImages());
    assertEquals(
        java.util.Set.of(
            "qits/workspace:2026.903.120000", "qits/workspace-editor:2026.904.100239"),
        pins.workspaceLaunchImages());
    assertEquals(
        java.util.Set.of("qits/project-agent:2026.903.090000", "qits/workspace:2026.903.120000"),
        pins.projectLaunchImages());

    // The three sentences, and they are three because a reviewer reads them to decide whether a tag
    // may go — "configured" and "being pulled" are different answers to that question.
    assertEquals(
        GcPins.BY_CONFIGURATION, pins.pinsConfiguredImage("qits/workspace", "2026.904.160522"));
    assertEquals(
        GcPins.BY_WORKSPACE_LAUNCH,
        pins.pinsWorkspaceLaunchImage("qits/workspace", "2026.903.120000"));
    assertEquals(
        GcPins.BY_PROJECT_LAUNCH,
        pins.pinsProjectLaunchImage("qits/project-agent", "2026.903.090000"));
    assertNull(
        pins.pinsConfiguredImage("qits/workspace", "2026.903.120000"),
        "the version being pulled is NOT the configured one, which is the whole point");
    assertNull(pins.pinsProjectLaunchImage("qits/workspace-editor", "2026.904.100239"));

    GcPinSource workspaces = source(pins, "qits-workspaces");
    assertEquals(2, workspaces.pinCount());
    assertEquals(
        List.of("qits/workspace-editor:2026.904.100239", "qits/workspace:2026.903.120000"),
        workspaces.keeps());
    assertTrue(workspaces.outcome().contains("would pull TODAY"), workspaces.outcome());
    assertEquals(2, source(pins, "qits-projects").pinCount());
  }

  @Test
  void anEffectiveSourceThatCannotAnswerFailsTheRunAndLeavesNoHalfFoldedKeepSet() {
    // Fail-closed, per source: qits-projects answers and qits-workspaces does not, so the run
    // refuses — and the set the broken source had started filling is cleared, because a report
    // claiming coordinates a source did not finish stating is worse than zeros.
    GcPinSources sources = answering();
    sources.workspaces =
        () -> {
          throw new IllegalStateException(
              "qits-workspaces unreachable at http://qits-workspaces:8080/workspaces/api/pins");
        };
    sources.projects =
        () -> List.of(new LaunchImagePins.LaunchPin("qits/project-agent", "1", "agent"));

    GcPins pins = sources.fetch();

    assertFalse(pins.complete());
    assertEquals(1, pins.failures().size());
    assertTrue(pins.whyIncomplete().contains("qits-workspaces"), pins.whyIncomplete());
    assertTrue(pins.whyIncomplete().contains("launch pins"), pins.whyIncomplete());
    assertEquals(java.util.Set.of(), pins.workspaceLaunchImages());
    assertFalse(source(pins, "qits-workspaces").answered());
    assertTrue(
        source(pins, "qits-projects").answered(),
        "the other launching service still answered, so the report names one outage and not two");
    assertEquals(java.util.Set.of("qits/project-agent:1"), pins.projectLaunchImages());
  }

  @Test
  void anEmptyConfiguredImageAnswerIsAnAnswerRatherThanAnOutage() {
    // A platform that has released none of the on-demand images yet. It must read as a source that
    // answered with nothing pinned — the opposite of an outage, and the state a fresh install is in.
    GcPinSources sources = answering();

    GcPins pins = sources.fetch();

    assertTrue(pins.complete());
    GcPinSource configuration = source(pins, "qits-configuration");
    assertTrue(configuration.answered());
    assertEquals(0, configuration.pinCount());
    assertEquals(List.of(), configuration.keeps());
  }

  /**
   * A collector whose six ports all answer with nothing, for a case to override the one it is
   * about.
   *
   * <p>The fields are the CDI injection points, and a hand-built collector leaves them null — so a
   * case that set only the two it cared about would meet an NPE rather than the fold it meant to
   * exercise.
   */
  private static GcPinSources answering() {
    GcPinSources sources = new GcPinSources();
    sources.cd = List::of;
    sources.ci = () -> new CiDaemonPins.DaemonPin("", "", "", "none");
    sources.maintenance = List::of;
    sources.configuration = List::of;
    sources.workspaces = List::of;
    sources.projects = List::of;
    return sources;
  }

  private static GcPinSource source(GcPins pins, String name) {
    return pins.sources().stream()
        .filter(source -> source.source().equals(name))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aSweepWithUnreadablePinsAbortsTheWholeRunAndDeletesNothing() throws Exception {
    // The content below is exactly what a healthy run deletes — a superseded main build whose
    // tarball has aged past the grace window — and npm's rule needs no pin to condemn it. It
    // survives anyway, which is the settlement's abort rule doing the only thing it is for.
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    String supersededBlob = agedBlob(62);
    versionRow(PKG, RELEASE, agedBlob(61));
    versionRow(PKG, SUPERSEDED, supersededBlob);
    versionRow(PKG, NEWEST, agedBlob(63));

    GcSweepReport report = executor.sweep();

    assertNotNull(report.aborted(), "a receipt of a run that never started still says why");
    assertTrue(report.aborted().contains("qits-platform-deployments"));
    assertTrue(report.aborted().contains("qits-ci"));
    assertTrue(report.aborted().contains("qits-platform-maintenance"));
    assertTrue(report.aborted().contains("qits-configuration"));
    assertTrue(report.aborted().contains("qits-workspaces"));
    assertTrue(report.aborted().contains("qits-projects"));
    assertFalse(report.dryRun(), "it is still the execute surface's receipt");
    assertEquals(REGISTERED_TYPES, report.types().size());
    for (GcTypeSweepResult type : report.types()) {
      assertEquals(report.aborted(), type.error(), type.type());
      assertEquals(0, type.deleted().size());
    }
    assertEquals(0, report.sweep().blobsUnlinked());
    assertEquals(0L, report.sweep().bytesReclaimed());
    assertTrue(
        report.untouchable().reason().contains("not computed"),
        "no census was taken, so claiming an empty row-less pool would be claiming something");
    assertEquals(
        6,
        report.pins().size(),
        "a receipt of a run that never started still shows what it tried to read");
    assertFalse(report.pins().get(0).answered());

    npmVersions.getEntityManager().clear();
    assertTrue(
        npmVersions.findOne("npm", PKG, SUPERSEDED).isPresent(),
        "the row a healthy run would have collected is untouched");
    assertTrue(blobStore.exists(supersededBlob), "and so is its tarball");
  }

  @Test
  void theDryRunStillAnswersButMarksItselfNonExecutable() throws Exception {
    // A report that 500s tells a reviewer nothing about the types that are fine, so the plan is
    // still computed. What it must not do is read like a finding: executable says a sweep would
    // refuse, the failures are named, and every pin-dependent type carries the refusal instead of
    // zeros nobody can interpret.
    seed();

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    assertEquals(6, report.pinFailures().size());
    assertEquals(6, report.pins().size(), "and the same six sources are named in the pins section");
    GcTypePlan oci = typePlan(report, OciImagesProfile.KEY);
    assertNotNull(oci.error());
    assertTrue(oci.error().contains("live pins unavailable"));
    assertEquals(0, oci.dead().size());
    assertEquals(0, oci.kept().size(), "not planned at all, rather than planned against no pins");

    // Every type on an engine reads pins now, so the useful half of a broken run is the CI stubs:
    // they carry their caption rather than an error, which is what keeps the report readable when
    // half of it is a refusal.
    GcTypePlan npm = typePlan(report, NpmPackagesProfile.KEY);
    assertNotNull(npm.error(), "the own engine reads pins too");
    assertEquals(0, npm.dead().size());
    GcTypePlan videos = typePlan(report, CiVideosProfile.KEY);
    assertNull(videos.error(), "a type that reads no pins is still planned");
    assertNotNull(videos.note());
    assertEquals(0, report.sweep().blobCount());
  }

  @Test
  void aCompleteAggregateMakesThePlanExecutableAgain() throws Exception {
    // The other half of the flag, so it is a fact about the pins rather than a constant: with an
    // aggregate that answered, the report is executable and oci-images is planned like any type.
    seed();

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    assertTrue(report.executable());
    assertEquals(0, report.pinFailures().size());
    assertNull(typePlan(report, OciImagesProfile.KEY).error());
  }

  private static GcTypePlan typePlan(GcPlanReport report, String type) {
    return report.types().stream()
        .filter(plan -> RepositoryTypeProfile.wireNameOf(type).equals(plan.type()))
        .findFirst()
        .orElseThrow();
  }

  private String agedBlob(int size) throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    backdate(blobId, Duration.ofDays(30));
    return blobId;
  }

  private void versionRow(String packageName, String version, String blobId) {
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
}
