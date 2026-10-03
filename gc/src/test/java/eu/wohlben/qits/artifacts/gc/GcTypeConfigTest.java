package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The settlement as shipped configuration, and the proof that switching the caches on changed
 * <b>only</b> the caches.
 *
 * <p>Three questions. What does this deployment have configured; does the report echo it; and —
 * type by type — is every plan this workstream did not touch still identity-for-identity what it
 * was. The third is the one that carries, and it is why this comparison is edited <b>deliberately,
 * once per workstream</b>: the types moving onto an engine get their new dead sets written out here,
 * and every other type stays pinned exactly as it was. A diff that quietly widened would be the one
 * change nobody could review.
 *
 * <p>Every type here is on the OWN engine or excluded. The three cache types went to
 * qits-platform-mirror with the eviction engine that collected them, so what is left is the
 * pin-based half of the settlement: {@code oci-images}, {@code npm-packages}, {@code
 * maven-packages}, {@code daemon-binaries}, {@code docs} and {@code sboms} collected, the two CI
 * types excluded.
 */
@QuarkusTest
class GcTypeConfigTest extends GcFixture {

  @Inject GcTypeConfig config;
  @Inject GcPlanner planner;
  @Inject RepositoryTypeProfiles repositoryTypes;

  @Test
  void everyRepositoryTypeIsConfiguredAndTheShippedValuesAreTheSettlements() {
    // The settlement's own numbers, as revised on 2026-09-05: P0D for all six own types, and the two
    // CI types excluded and honest about it. P30D/P90D priced two guesses at how long consumption
    // takes and P3D priced a smaller one; zero prices none, which is the point — consumption is
    // named outright by six pin sources and the structural belts, so RETENTION IS THE KEEP-SET and
    // the window is not standing in for anything any more.
    // Looping over the REGISTERED types rather than listing cases is what makes a newly contributed
    // profile fail here — a type with no policy is a decision nobody took, not a default.
    Map<String, GcPolicy> strategies = new TreeMap<>();
    Map<String, Optional<Duration>> windows = new TreeMap<>();
    for (String type : repositoryTypes.keys()) {
      strategies.put(type, config.of(type).strategy());
      windows.put(type, config.of(type).window());
    }

    assertEquals(GcPolicy.OWN, strategies.get(OciImagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(NpmPackagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(MavenPackagesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(DaemonBinariesProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(DocsProfile.KEY));
    assertEquals(GcPolicy.OWN, strategies.get(SbomProfile.KEY));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(CiScreenshotsProfile.KEY));
    assertEquals(GcPolicy.EXCLUDED, strategies.get(CiVideosProfile.KEY));
    assertEquals(
        8,
        strategies.size(),
        "the cache types are not merely unconfigured here — they are unregistered");

    assertEquals(Duration.ZERO, config.requireWindow(OciImagesProfile.KEY));
    assertEquals(Duration.ZERO, config.requireWindow(NpmPackagesProfile.KEY));
    assertEquals(Duration.ZERO, config.requireWindow(MavenPackagesProfile.KEY));
    assertEquals(Duration.ZERO, config.requireWindow(DaemonBinariesProfile.KEY));
    assertEquals(Duration.ZERO, config.requireWindow(DocsProfile.KEY));
    assertEquals(Duration.ZERO, config.requireWindow(SbomProfile.KEY));
    assertEquals(Optional.empty(), windows.get(CiScreenshotsProfile.KEY));
    assertEquals(Optional.empty(), windows.get(CiVideosProfile.KEY));
  }

  @Test
  void thePlanEchoesEveryTypesStrategyWindowAndEffectiveRule() {
    // The echo is what a reviewer reads the mapping off, so it carries the sentence rather than the
    // enum: "own, P0D" is not reviewable, "keep the last 2 released versions … delete the rest once
    // unaccessed for longer than P0D, so the keep-classes ARE the retention policy" is.
    GcPlanReport report = planner.plan();

    assertEquals(
        List.of(
            "ci-screenshots",
            "ci-videos",
            "daemon-binaries",
            "docs",
            "maven-packages",
            "npm-packages",
            "oci-images",
            "sboms"),
        report.configuration().stream().map(GcTypeConfiguration::type).toList(),
        "one line per registered type, in the registry's own order, so the echo and the per-type"
            + " plans read down the page together");

    GcTypeConfiguration maven = line(report, MavenPackagesProfile.KEY);
    assertEquals("own", maven.strategy());
    assertEquals("P0D", maven.window());
    assertTrue(maven.rule().contains("last 2 released versions"));
    assertTrue(maven.rule().contains("P0D"));
    assertTrue(
        maven.rule().contains("keep-classes ARE the retention policy"),
        "at zero the sentence has to say what the window stopped doing: " + maven.rule());

    GcTypeConfiguration videos = line(report, CiVideosProfile.KEY);
    assertEquals("excluded", videos.strategy());
    assertNull(videos.window(), "a window beside a type nobody collects reads as a running rule");
    assertEquals(GcRules.EXCLUDED, videos.rule());
  }

  @Test
  void everyConfiguredTypeCollectsAndTheExcludedOnesStillCondemnNothing() throws Exception {
    // The definition of done for this workstream, and the shape of the comparison it inherits.
    // Everything is seeded and aged; the plan is taken with complete pins so the change under test
    // is the POLICY rather than this suite's closed pin ports.
    //
    // WHAT CHANGED on 2026-09-05, deliberately: the windows are P0D, so oci-images now condemns
    // every tag no keep-class names — the substrate's v1 and v2, and BOTH build shas including the
    // one written a moment ago. A calver release is added to the fixture so the belt is still
    // visible beside them; without it this type's keep-list would be empty and the case would prove
    // only that everything dies. What must NOT have changed is everything else: the belts of
    // npm-packages, maven-packages and daemon-binaries decide those three exactly as before, because
    // a belt never asked what time it was.
    Store store = seed();
    seedMaven();
    // One image, two build shas and a release: the shas are named by nothing and go, the release is
    // the pull target the belt protects for an image cd has never deployed.
    ociTag("alpha", COLD_SHA, store.manifestKept(), Instant.now().minus(Duration.ofDays(60)));
    ociTag("alpha", WARM_SHA, store.manifestKept(), Instant.now());
    ociTag("alpha", RELEASE_TAG, store.manifestKept(), Instant.now());
    String coldDaemon = store(filled(64, (byte) 64));
    backdate(coldDaemon, Duration.ofDays(30));
    daemonRepository();
    daemonRow(DAEMON, "2026.601.10", coldDaemon, daysAgo(400), null);
    daemonRow(DAEMON, "2026.701.20", store(filled(65, (byte) 65)), daysAgo(300), null);
    daemonRow(DAEMON, "2026.801.30", store(filled(66, (byte) 66)), daysAgo(200), null);
    // A cold prerelease beside the fixture's two warm releases, and a third release version of a
    // second maven artifact — the two identities this workstream's rules condemn.
    String coldTarball = store(filled(67, (byte) 67));
    backdate(coldTarball, Duration.ofDays(30));
    npmVersionRow("@qits/thing", "0.9.0-main.gaaaaaa1", coldTarball, daysAgo(400));
    String coldJar = store(filled(68, (byte) 68));
    backdate(coldJar, Duration.ofDays(30));
    mavenRow(OTHER_ARTIFACT + "/1.0.0/qits-other-1.0.0.jar", coldJar, daysAgo(400));
    mavenRow(
        OTHER_ARTIFACT + "/1.1.0/qits-other-1.1.0.jar",
        store(filled(69, (byte) 69)),
        daysAgo(390));
    mavenRow(
        OTHER_ARTIFACT + "/2.0.0/qits-other-2.0.0.jar",
        store(filled(70, (byte) 70)),
        daysAgo(380));
    // Each with its pom, which the maven closure reads for every coordinate it keeps (qits-739):
    // a kept coordinate with no pom would keep the whole type for the run instead.
    for (String version : List.of("1.0.0", "1.1.0", "2.0.0")) {
      mavenRow(
          OTHER_ARTIFACT + "/" + version + "/qits-other-" + version + ".pom",
          store(("<project><!-- qits-other " + version + " --></project>").getBytes(
              java.nio.charset.StandardCharsets.UTF_8)),
          daysAgo(380));
    }

    GcPlanReport report = planner.plan(census.take(), planner.registered(), GcPins.none());

    Map<String, List<String>> dead = new LinkedHashMap<>();
    Map<String, List<String>> kept = new LinkedHashMap<>();
    report
        .types()
        .forEach(
            plan -> {
              dead.put(
                  plan.type(),
                  plan.dead().stream().map(identity -> identity.identity()).sorted().toList());
              kept.put(
                  plan.type(),
                  plan.kept().stream().map(identity -> identity.identity()).sorted().toList());
            });

    // The type this workstream moved.
    assertEquals(
        List.of("alpha:" + COLD_SHA, "alpha:" + WARM_SHA, "alpha:v1", "alpha:v2"),
        dead.get(RepositoryTypeProfile.wireNameOf(OciImagesProfile.KEY)),
        "every tag no pin, no belt and no pointer names — the sha written this second included");
    assertEquals(
        List.of("alpha:" + RELEASE_TAG),
        kept.get(RepositoryTypeProfile.wireNameOf(OciImagesProfile.KEY)),
        "the release stays as the next deployment's pull target, and it is the whole keep-set now");
    assertEquals(
        List.of(DAEMON + "@2026.601.10"),
        dead.get(RepositoryTypeProfile.wireNameOf(DaemonBinariesProfile.KEY)),
        "the third version down a belt of two, and nothing has launched it in a year");
    assertEquals(
        List.of(DAEMON + "@2026.701.20", DAEMON + "@2026.801.30"),
        kept.get(RepositoryTypeProfile.wireNameOf(DaemonBinariesProfile.KEY)));

    // The two that moved in this workstream.
    assertEquals(
        List.of("@qits/thing@0.9.0-main.gaaaaaa1"),
        dead.get(RepositoryTypeProfile.wireNameOf(NpmPackagesProfile.KEY)),
        "a prerelease earns no belt, and nothing has installed it in a year");
    assertEquals(
        List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), kept.get(RepositoryTypeProfile.wireNameOf(NpmPackagesProfile.KEY)));
    assertEquals(
        List.of("eu.wohlben.qits:qits-other:1.0.0"),
        dead.get(RepositoryTypeProfile.wireNameOf(MavenPackagesProfile.KEY)),
        "maven-packages collects releases again since 2026-10-03 (qits-739): qits-other:1.0.0 is"
            + " below the belt, unpinned, and named by no kept coordinate's SBOM or pom");
    assertEquals(
        List.of(
            "eu.wohlben.qits:qits-eventstream:1.0.0",
            "eu.wohlben.qits:qits-other:1.1.0",
            "eu.wohlben.qits:qits-other:2.0.0"),
        kept.get(RepositoryTypeProfile.wireNameOf(MavenPackagesProfile.KEY)),
        "the jar and pom of the fixture's release are one identity, and the belt keeps two");
    // The echo's belt sentence is true for maven again; the type's own note adds what it cannot
    // show — the closure in front of the belt and the fail-closed rule behind it.
    assertEquals(
        MavenPackagesGcStrategy.NOTE,
        typePlan(report, MavenPackagesProfile.KEY).note(),
        "the type's own line names the closure and the fail-closed rule");

    // The two nobody collects — excluded by the settlement, and still saying so.
    for (String type : List.of(CiScreenshotsProfile.KEY, CiVideosProfile.KEY)) {
      String wire = RepositoryTypeProfile.wireNameOf(type);
      assertEquals(List.of(), dead.get(wire), wire + " must still condemn nothing");
      assertEquals(List.of(), kept.get(wire));
    }

    // The two collected types this fixture seeds nothing under: planned by the own engine, and
    // condemning nothing because there is nothing there. That is the shape a newly registered type
    // has to have on its first landing — an empty plan, not a missing line.
    for (String type : List.of(DocsProfile.KEY, SbomProfile.KEY)) {
      String wire = RepositoryTypeProfile.wireNameOf(type);
      assertEquals(List.of(), dead.get(wire), wire + " has no rows in this fixture");
      assertEquals(List.of(), kept.get(wire), wire);
    }

    // The blob half of the same comparison, and it is where both halves of this reconciliation show
    // up in one list. The cold daemon binary and the cold published tarball are released by the zero
    // window, and since qits-739 so is the cold JAR: its coordinate is a release below the belt that
    // nothing kept reaches. Its pom was stored this second, so that blob is still inside the grace
    // period and only the jar shows here (and at execution the young pom withholds the coordinate
    // whole, as GcSweepExecutorTest proves). What the zero window adds is the doomed
    // manifest's own layer, which the dead v2 tag released and nothing surviving names; the image's
    // other blobs stay, because the release tag reaches the kept manifest and the shared layer is an
    // npm tarball besides. This figure is the DRY RUN's, which runs one run ahead of the store for
    // OCI by design — the manifest rows the dead tags leave behind still hold their bytes until the
    // next run makes them candidates.
    assertEquals(
        List.of(coldDaemon, coldTarball, coldJar, store.layerDoomed(), store.manifestDoomed())
            .stream()
            .sorted()
            .toList(),
        report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
  }

  @Test
  void everyPinReadingTypeRefusesToPlanWhileThePinSourcesCannotAnswer() throws Exception {
    // The other side of the same wiring, on the report a deployment actually gets when one of the
    // six pin peers is down: every type on an engine reads pins, so every one of them is refused
    // rather than planned against "nothing is pinned". This suite's six pin urls are closed ports,
    // which is that state exactly.
    seed();

    GcPlanReport report = planner.plan();

    assertFalse(report.executable());
    for (String type :
        List.of(
            OciImagesProfile.KEY,
            NpmPackagesProfile.KEY,
            MavenPackagesProfile.KEY,
            DaemonBinariesProfile.KEY,
            DocsProfile.KEY,
            SbomProfile.KEY)) {
      assertTrue(
          typePlan(report, type).error().contains("live pins unavailable"),
          RepositoryTypeProfile.wireNameOf(type) + ": " + typePlan(report, type).error());
      assertEquals(List.of(), typePlan(report, type).dead());
    }
    assertEquals(0, report.sweep().blobCount(), "a refusal reclaims nothing, by construction");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private static final String COLD_SHA = "a".repeat(40);
  private static final String WARM_SHA = "b".repeat(40);
  /** A calver tag, which is the only shape of coordinate a belt of this type can see. */
  private static final String RELEASE_TAG = "2026.905.110103";
  private static final String DAEMON = "qits-ci-daemon";
  private static final String OTHER_ARTIFACT = "eu/wohlben/qits/qits-other";

  private void daemonRepository() {
    repositoryService.ensure(DAEMON_REPO, DaemonBinariesProfile.KEY);
  }

  /** One more tag on the substrate's own image, with {@code updated_at} under the case's control. */
  private void ociTag(String image, String name, String digest, Instant updatedAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.OciTag row =
                  new eu.wohlben.qits.artifacts.entity.OciTag();
              row.repository = "qits";
              row.imageName = image;
              row.tag = name;
              row.manifestDigest = digest;
              row.updatedAt = updatedAt;
              ociTags.persist(row);
            });
  }

  /** One published npm version, with its publish time under the case's control. */
  private void npmVersionRow(String packageName, String version, String blobId, Instant createdAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.NpmVersion row =
                  new eu.wohlben.qits.artifacts.entity.NpmVersion();
              row.repository = "npm";
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = "{}";
              row.createdAt = createdAt;
              npmVersions.persist(row);
            });
  }

  /** One deployed maven file, with its deploy time under the case's control. */
  private void mavenRow(String path, String blobId, Instant createdAt) {
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.artifacts.entity.MavenArtifact row =
                  new eu.wohlben.qits.artifacts.entity.MavenArtifact();
              row.repository = MAVEN_REPO;
              row.path = path;
              row.blobId = blobId;
              row.sizeBytes = 1;
              row.createdAt = createdAt;
              mavenArtifacts.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static GcTypeConfiguration line(GcPlanReport report, String type) {
    return report.configuration().stream()
        .filter(configured -> RepositoryTypeProfile.wireNameOf(type).equals(configured.type()))
        .findFirst()
        .orElseThrow();
  }

  private static eu.wohlben.qits.artifacts.gc.dto.GcTypePlan typePlan(
      GcPlanReport report, String type) {
    List<eu.wohlben.qits.artifacts.gc.dto.GcTypePlan> plans = new ArrayList<>(report.types());
    return plans.stream().filter(plan -> RepositoryTypeProfile.wireNameOf(type).equals(plan.type())).findFirst().orElseThrow();
  }
}
