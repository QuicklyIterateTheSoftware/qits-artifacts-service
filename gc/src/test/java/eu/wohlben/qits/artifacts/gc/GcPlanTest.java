package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanSummary;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanSummary;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The seam and the reconciliation, which is the only hard part of this design.
 *
 * <p>A strategy says which of its own identities die and which blobs that touches. Nothing else. The
 * question "may this blob be unlinked" is answered here, over every type at once, and the cases
 * below are the four ways the answer is no: another type still names it, a surviving identity of the
 * <em>same</em> type still names it, it never had an identity at all, or its file is too young.
 *
 * <p>The strategies here are fakes constructed in the test rather than beans, and that is
 * deliberate: the reconciliation is what these cases are about, and a real strategy would answer
 * with its own policy instead of the shape a case needs. The registered beans are exercised on
 * their own rules in their own suites and appear here only in the first case, as the report's
 * shape — and the "nobody collects this type" line, which no shipped type is in any more, is proved
 * over an empty registration rather than left untested.
 */
@QuarkusTest
class GcPlanTest extends GcFixture {

  /**
   * How many repository types this deployment registers: the two CI profiles from qits-blobstore,
   * the three hosted format profiles from qits-registries, and the three this service contributes
   * — daemon-binaries, docs and now sboms. The cache profiles are excluded from discovery, so a
   * report has eight lines, not ten.
   */
  private static final int REGISTERED_TYPES = 8;

  @Inject GcPlanner planner;

  @Test
  void everyTypeIsClaimedAndEachLineSaysWhichOfThreeAnswersItIs() throws Exception {
    // The report a reviewer sees first. "Nothing to collect", "refused to plan" and a real plan are
    // three different facts, so every type is listed with its own reason rather than omitted. The
    // six types on an engine demonstrate the refusal here — docs and sboms among them, neither of
    // which reads a pin source of its own but both of which still take the digest floor: this suite
    // has no qits-platform-deployments and no qits-ci, and a keep-set that cannot be established
    // reclaims nothing. The two CI
    // stubs are the second — zero rows, a named intended rule, and a note saying the loop has never
    // produced content.
    Store store = seed();

    assertEquals(
        List.of(
            "CiScreenshotsGcStrategy",
            "CiVideosGcStrategy",
            "DaemonBinariesGcStrategy",
            "DocsGcStrategy",
            "MavenPackagesGcStrategy",
            "NpmPackagesGcStrategy",
            "OciImageGcStrategy",
            "SbomGcStrategy"),
        planner.registered().stream().map(GcPlanner::nameOf).sorted().toList());
    GcPlanReport report = planner.plan();

    assertTrue(report.dryRun());
    assertEquals(REGISTERED_TYPES, report.types().size());
    for (GcTypePlan type : report.types()) {
      switch (type.type()) {
        case "oci-images" -> {
          assertEquals("OciImageGcStrategy", type.strategy());
          assertNull(type.note());
          assertNotNull(type.error(), "no qits-platform-deployments here, so the type must abort rather than plan");
        }
        case "daemon-binaries" -> {
          assertEquals("DaemonBinariesGcStrategy", type.strategy());
          assertNull(type.note());
          assertNotNull(
              type.error(),
              "the binary every CI step downloads: no qits-ci, no plan, on purpose");
          assertEquals(0, type.dead().size(), "a refused type plans nothing");
        }
        case "npm-packages" -> {
          assertEquals("NpmPackagesGcStrategy", type.strategy());
          assertNull(type.note());
          assertNotNull(type.error(), "the own engine reads pins, and there are none here");
          assertEquals(0, type.dead().size(), "a refused type plans nothing");
        }
        case "maven-packages" -> {
          assertEquals("MavenPackagesGcStrategy", type.strategy());
          assertNull(type.note(), "the append-only note went with the append-only posture");
          assertNotNull(type.error(), "the own engine reads pins, and there are none here");
          assertEquals(0, type.dead().size(), "a refused type plans nothing");
        }
        case "ci-screenshots" -> {
          assertEquals("CiScreenshotsGcStrategy", type.strategy());
          // Two facts, and the type's own line has to carry both: the configuration excludes it,
          // and the stub names the rule it will eventually get. Without the first, `dead: []` beside
          // a claimed strategy reads as a rule that ran and found nothing.
          assertEquals(GcRules.EXCLUDED_NOTE + CiScreenshotsGcStrategy.NOTE, type.note());
          assertTrue(type.note().contains("branch-scoped"), "the note names the intended rule");
          assertNull(type.error(), "zero rows: the stub plans nothing rather than refusing");
          assertEquals(0, type.dead().size());
        }
        case "ci-videos" -> {
          assertEquals("CiVideosGcStrategy", type.strategy());
          assertEquals(GcRules.EXCLUDED_NOTE + CiVideosGcStrategy.NOTE, type.note());
          assertTrue(type.note().contains("byte"), "the note names the intended rule");
          assertNull(type.error(), "zero rows: the stub plans nothing rather than refusing");
          assertEquals(0, type.dead().size());
        }
        case "docs" -> {
          assertEquals("DocsGcStrategy", type.strategy());
          assertNotNull(type.error(), "the own engine reads pins, and there are none here");
          assertEquals(0, type.dead().size(), "a refused type plans nothing");
        }
        case "sboms" -> {
          assertEquals("SbomGcStrategy", type.strategy());
          // No pin source names an SBOM, and the type is refused here all the same: every
          // own type takes the digest floor, and a pinned blob may be the bytes of a document.
          assertNotNull(type.error(), "the own engine reads pins, and there are none here");
          assertEquals(0, type.dead().size(), "a refused type plans nothing");
        }
        default -> throw new AssertionError("unexpected type in the report: " + type.type());
      }
      assertEquals(0, type.blobsSweepable());
      assertEquals(0L, type.reclaimableBytes());
    }
    assertEquals(0, report.sweep().blobCount());
    assertEquals(0L, report.sweep().reclaimableBytes());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
    assertEquals(ROWLESS, report.untouchable().bytes());
    assertEquals("PT6H", report.graceWindow());
  }

  @Test
  void theSummaryIsWhatAReviewerReadsFirstAndItSaysWhetherThisCanRunAtAll() throws Exception {
    // The report is eight types deep and a review that has to add them up before it can start is a
    // review nobody performs. So the plan leads with the paragraph: can this be executed, what does
    // it cost, and which type is doing the work. Here it cannot — no qits-platform-deployments, no qits-ci — and that
    // has to be the first thing the summary says rather than a flag further down.
    Store store = seed();
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(new GcIdentity("qits", "alpha:v1", "newest build")),
                Set.of(store.layerDoomed()),
                Set.of()));

    GcPlanSummary executable =
        planner.plan(census.take(), List.of(oci), GcPins.none()).summary();

    assertTrue(executable.executable());
    assertTrue(executable.headline().startsWith("a sweep run now would execute this plan"));
    assertEquals(1, executable.identitiesCondemned());
    assertEquals(1, executable.blobsSweepable());
    assertEquals(LAYER_DOOMED, executable.reclaimableBytes());
    assertEquals(GcSummary.bytes(LAYER_DOOMED), executable.reclaimable());
    assertEquals(REGISTERED_TYPES, executable.types().size());
    assertTrue(
        executable.types().stream()
            .anyMatch(line -> line.startsWith("oci-images (own, P0D): 1 identities condemned")),
        "a per-type line carries the configured engine and window beside the outcome: " + executable
            .types());
    assertTrue(
        executable.types().stream()
            .anyMatch(
                line ->
                    line.startsWith("ci-videos (excluded): excluded by configuration")
                        && line.contains("a decision, not a gap")),
        "the excluded types say so where the outcomes are read: " + executable.types());

    GcPlanSummary refused = planner.plan().summary();

    assertFalse(refused.executable());
    assertTrue(refused.headline().startsWith("NOT EXECUTABLE"), refused.headline());
    assertTrue(refused.headline().contains("qits-platform-deployments"), refused.headline());
    assertTrue(
        refused.types().stream()
            .anyMatch(line -> line.endsWith("refused: live pins unavailable")),
        "and the type lines say which refusal produced their zeros: " + refused.types());
  }

  @Test
  void aTypeNoStrategyClaimsIsReportedAsSuchRatherThanOmitted() throws Exception {
    // No shipped type is in this state any more — daemon-binaries was the last one — and the line
    // still has to be right, because it is what a NEWLY CONTRIBUTED profile reads as on its first
    // day.
    // "No plan" and "nothing to collect" are different facts, and a missing entry would read as the
    // second.
    seed();

    GcPlanReport report = planner.plan(census.take(), List.of(), GcPins.none());

    assertEquals(REGISTERED_TYPES, report.types().size());
    for (GcTypePlan type : report.types()) {
      assertNull(type.strategy());
      assertEquals("no strategy registered for " + type.type(), type.note());
      assertEquals(List.of(), type.dead());
    }
    assertEquals(0, report.sweep().blobCount(), "and nothing of an unclaimed type is ever swept");
  }

  @Test
  void aStrategyIsNamedByItsOwnClassEvenWhenCdiHandsOverAClientProxy() {
    // Both shipped strategies are @Singleton, so neither is proxied and the assertion above passes
    // either way — which is exactly why this case exists: the next strategy will make that choice
    // again, and a report reading "Something_ClientProxy" names a class that appears in no source
    // file. The census stands in for a normal-scoped bean because it is one; injecting it gives a
    // real proxy rather than a simulated one.
    assertTrue(
        census.getClass().getSimpleName().endsWith("_ClientProxy"),
        "an @ApplicationScoped bean must be injected as a proxy, or this proves nothing: "
            + census.getClass().getSimpleName());
    assertEquals("LiveBlobCensus", GcPlanner.nameOf(census));
  }

  @Test
  void aBlobAnotherTypeStillNamesSurvivesItsOwnTypesDeletion() throws Exception {
    // The whole reason the sweep is one mechanism and the strategies are five: docker lets go of a
    // layer that npm is still serving as a tarball. Deleting it would 404 a package install.
    Store store = seed();
    LiveBlobCensus.Census taken = census.take();
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "sha tag, no deployment pins it")),
                List.of(new GcIdentity("qits", "alpha:v1", "newest build")),
                Set.of(store.manifestDoomed(), store.layerDoomed(), store.shared(), store.config()),
                Set.of(store.manifestKept(), store.layerKept(), store.config())));

    GcPlanReport report = planner.plan(taken, List.of(oci), GcPins.none());

    assertEquals(
        List.of(store.layerDoomed(), store.manifestDoomed()).stream().sorted().toList(),
        report.sweep().blobIds(),
        "the shared layer stays: npm still names it. The config stays: a kept manifest names it");
    assertEquals((long) LAYER_DOOMED + manifestSize(taken, store.manifestDoomed()),
        report.sweep().reclaimableBytes());
    GcTypePlan ociPlan = typePlan(report, OciImagesProfile.KEY);
    assertEquals(4, ociPlan.blobsReleased(), "released is what the dead identity named");
    assertEquals(2, ociPlan.blobsSweepable(), "sweepable is what nothing else names");
    assertEquals("alpha:v2", ociPlan.dead().get(0).identity());
    assertEquals("newest build", ociPlan.kept().get(0).rule());
  }

  @Test
  void aBlobAKeptIdentityOfTheSameTypeStillNamesSurvives() throws Exception {
    // Intra-type sharing, which is the common case in docker: every rebuild shares its base layers
    // with the tag before it. A strategy reports both sets precisely so it never has to subtract.
    Store store = seed();
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(),
                Set.of(store.config(), store.layerDoomed()),
                Set.of(store.config())));

    GcPlanReport report = planner.plan(census.take(), List.of(oci), GcPins.none());

    assertEquals(List.of(store.layerDoomed()), report.sweep().blobIds());
    assertEquals(LAYER_DOOMED, report.sweep().reclaimableBytes());
  }

  @Test
  void aRowLessBlobIsNeverSweptEvenIfAStrategyNamesIt() throws Exception {
    // The ci-daemon rule, enforced rather than trusted. Structurally a strategy cannot release a
    // blob it never had a row for; a strategy that does it anyway must still not reach the unlink.
    Store store = seed();
    backdate(store.rowless(), java.time.Duration.ofDays(30));
    GcStrategy rogue =
        strategy(
            CiVideosProfile.KEY,
            new GcStrategy.Plan(List.of(), List.of(), Set.of(store.rowless()), Set.of()));

    GcPlanReport report = planner.plan(census.take(), List.of(rogue), GcPins.none());

    assertEquals(List.of(), report.sweep().blobIds());
    assertEquals(List.of(store.rowless()), report.untouchable().blobIds());
    assertTrue(report.untouchable().reason().contains("LOSES its last row"));
  }

  @Test
  void aBlobYoungerThanTheGraceWindowIsWithheldRatherThanSwept() throws Exception {
    // Same plan, one difference: the file was written a moment ago. It is not lost, it is not yet —
    // and the number that says so is in the report, because a silent withholding reads as a bug.
    Store store = seed();
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(),
                Set.of(store.layerDoomed()),
                Set.of()));

    // Undo the fixture's backdating for that one blob: it is now as young as a fresh push.
    backdate(store.layerDoomed(), java.time.Duration.ZERO);
    GcPlanReport report = planner.plan(census.take(), List.of(oci), GcPins.none());

    assertEquals(0, report.sweep().blobCount());
    assertEquals(1, report.sweep().withheldByGraceWindow());
    assertEquals(LAYER_DOOMED, report.sweep().withheldBytes());
    assertEquals(
        LAYER_DOOMED,
        typePlan(report, OciImagesProfile.KEY).reclaimableBytes(),
        "the per-type figure is what the rule frees, not what tonight's run would unlink");
  }

  @Test
  void aStrategyThatCannotEstablishItsKeepSetAbortsItsTypeAndFreesNothingOfIt() throws Exception {
    // Fail-closed, which is the whole reason plan() may throw: the OCI rule's keep-set includes the
    // shas qits-platform-deployments pins, fetched live. CD unreachable must reclaim nothing rather than guess — and
    // must not let another type's plan free a blob the aborted type still names.
    Store store = seed();
    GcStrategy unreachable =
        new GcStrategy() {
          @Override
          public String type() {
            return OciImagesProfile.KEY;
          }

          @Override
          public Plan plan(LiveBlobCensus.Census census, GcPins pins) {
            throw new IllegalStateException("qits-platform-deployments unreachable; refusing to plan on stale pins");
          }
        };
    GcStrategy npm =
        strategy(
            NpmPackagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("npm", "@qits/thing@1.1.0", "superseded prerelease")),
                List.of(),
                Set.of(store.shared()),
                Set.of(store.tarball())));

    GcPlanReport report = planner.plan(census.take(), List.of(unreachable, npm), GcPins.none());

    GcTypePlan aborted = typePlan(report, OciImagesProfile.KEY);
    assertNotNull(aborted.error());
    assertTrue(aborted.error().contains("qits-platform-deployments unreachable"));
    assertEquals(0, aborted.blobsSweepable());
    assertEquals(
        List.of(),
        report.sweep().blobIds(),
        "the aborted type keeps its whole census set, so npm's release frees nothing");
  }

  @Test
  void twoStrategiesForOneTypeIsAPolicyCollisionAndNeitherRuns() throws Exception {
    // A type has exactly one policy. Two beans claiming it is a bug that must not be resolved by
    // merging them — which is what a shared retention framework would have done by design.
    Store store = seed();
    GcStrategy.Plan doomsEverything =
        new GcStrategy.Plan(
            List.of(), List.of(), Set.of(store.layerDoomed(), store.layerKept()), Set.of());

    GcPlanReport report =
        planner.plan(
            census.take(),
            List.of(
                strategy(OciImagesProfile.KEY, doomsEverything),
                strategy(OciImagesProfile.KEY, doomsEverything)),
            GcPins.none());

    GcTypePlan collided = typePlan(report, OciImagesProfile.KEY);
    assertTrue(collided.error().contains("two strategies claim this type"));
    assertEquals(0, report.sweep().blobCount());
    assertFalse(report.sweep().blobIds().contains(store.layerKept()));
  }

  @Test
  void everyRepositoryGetsALineIncludingTheEmptyOnesAndTheOnesNobodyCollects() throws Exception {
    // The honesty-about-absence rule, one level down. A listing drawn from the planned identities
    // would silently lose the repositories with nothing to collect — which is most of them, most
    // of the time — and an absent row reads as "nothing to clean here" rather than as a fact about
    // whether anybody cleans it at all. So the rows come from artifact_repository.
    Store store = seed();
    repositoryService.ensure("empty", OciImagesProfile.KEY);
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(new GcIdentity("qits", "alpha:v2", "superseded")),
                List.of(new GcIdentity("qits", "alpha:v1", "newest build")),
                Set.of(store.layerDoomed()),
                Set.of()));

    GcPlanReport report = planner.plan(census.take(), List.of(oci), GcPins.none());

    assertEquals(
        List.of("empty", "npm", "qits"),
        report.repositories().stream().map(GcRepositoryPlanSummary::repository).toList());

    GcRepositoryPlanSummary qits = repositorySummary(report, "qits");
    assertEquals("oci-images", qits.type(), "the wire form, as every report spells a type");
    assertEquals(1, qits.identitiesCondemned());
    assertEquals(1, qits.identitiesKept());
    assertEquals(1, qits.blobsSweepable());
    assertEquals(LAYER_DOOMED, qits.reclaimableBytes());
    assertNull(qits.error());

    GcRepositoryPlanSummary empty = repositorySummary(report, "empty");
    assertEquals(0, empty.identitiesCondemned());
    assertEquals(0, empty.blobsSweepable());
    assertNull(empty.error(), "its type planned fine; this repository simply holds nothing");

    GcRepositoryPlanSummary npm = repositorySummary(report, "npm");
    assertNull(npm.strategy());
    assertEquals("no strategy registered for npm-packages", npm.note());
    assertEquals(0, npm.blobsSweepable(), "and its zeros come with the reason beside them");
  }

  @Test
  void aBlobTwoRepositoriesBothCondemnDiesInAWholeStoreSweepAndInNeitherScopedOne()
      throws Exception {
    // What "bytes only this repository's cleanup frees" costs, stated rather than smoothed over.
    // Both repositories let go of the same layer, so a whole-store run unlinks it — but each scoped
    // view still sees the other's row standing, and neither may free it alone. The column
    // therefore sums to LESS than the global figure, which is the honest answer and not a bug: the
    // alternative, splitting a shared blob's bytes between them, is a number no run corresponds to.
    Store store = seed();
    repositoryService.ensure("qits2", OciImagesProfile.KEY);
    GcStrategy oci =
        strategy(
            OciImagesProfile.KEY,
            new GcStrategy.Plan(
                List.of(
                    new GcIdentity("qits", "alpha:v2", "superseded"),
                    new GcIdentity("qits2", "beta:v1", "superseded")),
                List.of(),
                Set.of(store.layerDoomed(), store.manifestDoomed()),
                Set.of(),
                Map.of(
                    "qits", Set.of(store.layerDoomed(), store.manifestDoomed()),
                    "qits2", Set.of(store.layerDoomed()))));

    LiveBlobCensus.Census taken = census.take();
    GcPlanReport report = planner.plan(taken, List.of(oci), GcPins.none());

    assertEquals(
        List.of(store.layerDoomed(), store.manifestDoomed()).stream().sorted().toList(),
        report.sweep().blobIds(),
        "a whole-store sweep frees both: nothing that survives names either of them");

    GcRepositoryPlanSummary qits = repositorySummary(report, "qits");
    assertEquals(1, qits.blobsSweepable(), "only the manifest, which qits2 never named");
    assertEquals(manifestSize(taken, store.manifestDoomed()), qits.reclaimableBytes());

    GcRepositoryPlanSummary qits2 = repositorySummary(report, "qits2");
    assertEquals(
        0,
        qits2.blobsSweepable(),
        "the shared layer is retained here, because qits' identity is standing in this view");
    assertEquals(0L, qits2.reclaimableBytes());
  }

  @Test
  void aRepositoryOfARefusedOrExcludedTypeCarriesThatTypesReasonRatherThanASilentZero()
      throws Exception {
    // The two reasons a real deployment's rows read zero, on the rows themselves. oci-images
    // refuses because this suite has no qits-platform-deployments; ci-videos is excluded by configuration and its
    // stub says so. A column of zeros with nothing beside them would make those two look identical
    // to "clean already", which they are not.
    seed();
    repositoryService.ensure("clips", CiVideosProfile.KEY);

    GcPlanReport report = planner.plan();

    GcRepositoryPlanSummary qits = repositorySummary(report, "qits");
    assertEquals("OciImageGcStrategy", qits.strategy());
    assertNotNull(qits.error());
    assertTrue(qits.error().contains("live pins unavailable"), qits.error());
    assertEquals(0, qits.blobsSweepable());

    GcRepositoryPlanSummary clips = repositorySummary(report, "clips");
    assertEquals("CiVideosGcStrategy", clips.strategy());
    assertNull(clips.error(), "zero rows: the stub plans nothing rather than refusing");
    assertTrue(clips.note().startsWith(GcRules.EXCLUDED_NOTE), clips.note());
  }

  @Test
  void oneRepositorysPlanCarriesEverySectionAReviewNeedsAndRefusesAnUnknownName() throws Exception {
    // The review artifact for a scoped sweep. It is the whole-store report's shape at one
    // repository — configuration echo, pins provenance, both blob figures, the row-less pool — and
    // it has to be, because the operator invoking a scoped sweep reads this and nothing else. Here
    // it is a refusal, which is the deployed behaviour under a broken pin source: zeros with the
    // reason attached rather than an empty plan.
    seed();

    GcRepositoryPlanReport report = planner.planForRepository("qits");

    assertEquals("qits", report.repository());
    assertEquals("oci-images", report.type());
    assertTrue(report.dryRun());
    assertFalse(report.executable(), "no qits-platform-deployments and no qits-ci here");
    assertEquals(6, report.pinFailures().size());
    assertEquals(6, report.pins().size(), "the provenance of a keep-set is half of what is reviewed");
    assertEquals("oci-images", report.configuration().type());
    assertEquals("own", report.configuration().strategy());
    assertEquals("OciImageGcStrategy", report.strategy());
    assertTrue(report.error().contains("live pins unavailable"), report.error());
    assertEquals(List.of(), report.dead());
    assertEquals(0, report.sweep().blobCount());
    assertEquals(0, report.structural().blobCount());
    assertEquals("PT6H", report.graceWindow());
    assertNotNull(report.untouchable().reason());

    assertThrows(
        eu.wohlben.qits.blobstore.error.NotFoundException.class,
        () -> planner.planForRepository("no-such-repository"),
        "a name that is not a repository is a 404, never a wider scope");
  }

  private static GcRepositoryPlanSummary repositorySummary(GcPlanReport report, String name) {
    return report.repositories().stream()
        .filter(summary -> name.equals(summary.repository()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no summary for " + name + " in " + report.repositories()));
  }

  private static long manifestSize(LiveBlobCensus.Census census, String digest) {
    return census.onDisk().get(digest);
  }

  private static GcTypePlan typePlan(GcPlanReport report, String type) {
    return report.types().stream()
        .filter(plan -> RepositoryTypeProfile.wireNameOf(type).equals(plan.type()))
        .findFirst()
        .orElseThrow();
  }

  /** A strategy that hands back a fixed answer — the seam is the contract, not the policy. */
  private static GcStrategy strategy(String type, GcStrategy.Plan plan) {
    return new GcStrategy() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public Plan plan(LiveBlobCensus.Census census, GcPins pins) {
        return plan;
      }
    };
  }
}
