package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.gc.dto.GcCondemnedIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositorySweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two-phase sweep (bug qits-1172): a run condemns what it finds dead, a later run purges it, and
 * everything in between is reversible.
 *
 * <p>The first case is the hole itself, reproduced: a daemon binary published long enough ago that
 * its bytes are past the six-hour blob grace, left out of one run's keep-set. Before the fix one
 * sweep took row and blob for good; the case asserts that it is condemned, still whole, and purged
 * only by a run past the condemn grace. The rest are the rules that make the condemnation safe to
 * lean on — a keep-set that names the identity again lifts it, a scoped run touches its own
 * repository's condemnations and nobody else's, an aborted run changes none, and the dry-run shows
 * which dead identities a sweep would purge rather than condemn.
 *
 * <p>The suite runs on the shipped {@code P1D}, and a day is crossed by ageing the condemnation
 * rows ({@link #ageCondemnationsPastGrace}), the way every GC suite here crosses the blob grace by
 * backdating blobs rather than configuring the window away. Per type, the same promise is asserted
 * once in each adapter suite through {@link #condemnedFirstThenPurged}.
 */
@QuarkusTest
class GcCondemnationTest extends GcFixture {

  @Inject DaemonBinariesGcStrategy strategy;
  @Inject GcPlanner planner;

  private static final String CI = "qits-ci-daemon";
  private static final String OLD = "2026.601.10";
  private static final String OLD_IDENTITY = CI + "@" + OLD;
  private static final String OTHER_REPO = "daemons-two";

  @Test
  void anOldDaemonAWrongKeepSetLeavesOutIsCondemnedNotDeletedAndPurgedOnlyPastTheGrace()
      throws Exception {
    // The bug, as it happened: a version whose bytes were written seven hours ago — past the PT6H
    // blob grace, so nothing on the old path protected it — and a keep-set (GcPins.none()) that does
    // not name it. One sweep used to delete the row and unlink the binary.
    String oldBlob = olderThanTheBlobGrace(11);
    seedLadder(DAEMON_REPO, oldBlob);

    GcSweepReport first = sweep(GcPins.none());

    GcTypeSweepResult line = daemonLine(first);
    assertNull(line.error());
    assertEquals(List.of(OLD_IDENTITY), named(line.condemned()), "condemned on the run that finds it");
    assertEquals(List.of(), line.deleted(), "and NOT deleted on it — this was the hole");
    assertEquals(List.of(), line.withheldByGraceWindow(), "the blob grace is not what saved it");
    assertEquals(1, line.awaitingPurge().size());
    GcCondemnedIdentity waiting = line.awaitingPurge().get(0);
    assertEquals("daemon-binaries", waiting.type());
    assertEquals(DAEMON_REPO, waiting.repository());
    assertEquals(OLD_IDENTITY, waiting.identity());
    assertEquals(first.runId(), waiting.runId(), "the condemnation names the receipt that wrote it");
    assertEquals(first.executedAt(), waiting.condemnedAt());
    assertEquals(first.executedAt().plus(Duration.ofDays(1)), waiting.purgeAt());
    assertEquals("P1D", first.condemnGracePeriod());
    assertTrue(daemonStored(DAEMON_REPO), "the row stands, so the binary is still served");
    assertTrue(blobStore.exists(oldBlob), "and its bytes stay on disk");
    assertEquals(0, first.sweep().blobsUnlinked());
    assertEquals(
        0,
        first.sweep().stillReferenced(),
        "a condemned identity's blob is on the retained side, not a candidate the re-census catches");

    // A second run inside the grace: still dead, still standing, and the clock is NOT restarted —
    // re-stamping on every run would mean nothing is ever purged.
    GcSweepReport second = sweep(GcPins.none());
    GcTypeSweepResult again = daemonLine(second);
    assertEquals(List.of(), again.condemned(), "already condemned, so not condemned afresh");
    assertEquals(List.of(), again.deleted());
    assertEquals(waiting.condemnedAt(), again.awaitingPurge().get(0).condemnedAt());
    assertEquals(first.runId(), again.awaitingPurge().get(0).runId());
    assertTrue(daemonStored(DAEMON_REPO));

    // Past the grace, and still dead: the purge is today's delete path, row and blob.
    ageCondemnationsPastGrace();
    GcSweepReport third = sweep(GcPins.none());
    GcTypeSweepResult purged = daemonLine(third);
    assertEquals(List.of(OLD_IDENTITY), named(purged.deleted()));
    assertEquals(List.of(), purged.awaitingPurge());
    assertFalse(daemonStored(DAEMON_REPO), "purged");
    assertFalse(blobStore.exists(oldBlob), "and its binary unlinked");
    assertEquals(List.of(oldBlob), third.sweep().unlinkedBlobIds());
    assertEquals(0, condemnedCount(), "the condemnation went with the identity");
  }

  @Test
  void aRunWhoseKeepSetNamesItAgainLiftsTheCondemnationAndARestoreStartsAFreshFullGrace()
      throws Exception {
    // The self-correction: yesterday's keep-set was wrong, today's is right, and that costs nothing.
    String oldBlob = olderThanTheBlobGrace(21);
    seedLadder(DAEMON_REPO, oldBlob);
    sweep(GcPins.none());
    assertEquals(1, condemnedCount());

    ageCondemnationsPastGrace();
    GcSweepReport kept = sweep(ladder(OLD));
    GcTypeSweepResult line = daemonLine(kept);
    assertEquals(List.of(OLD_IDENTITY), named(line.cleared()), "lifted by the keep-set, not by hand");
    assertEquals(GcPins.BY_CI, line.cleared().get(0).rule(), "naming the rule that saves it now");
    assertEquals(List.of(), line.deleted(), "a ripe condemnation the keep-set names is never purged");
    assertEquals(0, condemnedCount());
    assertTrue(daemonStored(DAEMON_REPO));

    // Left out again later: condemned afresh, with a fresh clock — never purged on the old one.
    GcSweepReport dropped = sweep(GcPins.none());
    GcTypeSweepResult recondemned = daemonLine(dropped);
    assertEquals(List.of(OLD_IDENTITY), named(recondemned.condemned()));
    assertEquals(List.of(), recondemned.deleted());
    assertEquals(
        dropped.executedAt().plus(Duration.ofDays(1)),
        recondemned.awaitingPurge().get(0).purgeAt(),
        "a full grace from the run that condemned it again");
    assertTrue(daemonStored(DAEMON_REPO));
  }

  @Test
  void aRunThatAbortsOnAnUnreadablePinSourceChangesNoCondemnation() throws Exception {
    // The whole-run abort, which this suite's closed-port pin urls force: over before any plan, so
    // over before any condemnation could be written, lifted or purged — at either scope.
    String oldBlob = olderThanTheBlobGrace(31);
    seedLadder(DAEMON_REPO, oldBlob);
    sweep(GcPins.none());
    ageCondemnationsPastGrace();
    GcCondemnations.Condemnation before = onlyCondemnation();

    GcSweepReport aborted = sweeps.sweep();
    GcRepositorySweepReport abortedScoped = sweeps.sweep(DAEMON_REPO);

    assertNotNull(aborted.aborted());
    assertNotNull(abortedScoped.aborted());
    assertNotNull(aborted.runId(), "an aborted run is still a run with a receipt");
    assertEquals(before, onlyCondemnation(), "the ripe condemnation is untouched");
    assertTrue(daemonStored(DAEMON_REPO), "and nothing was purged");
    assertTrue(blobStore.exists(oldBlob));
  }

  @Test
  void aScopedRunCondemnsClearsAndPurgesInsideItsOwnRepositoryAndNowhereElse() throws Exception {
    // Two daemon repositories holding the same identity, both dead under the same keep-set. A run
    // scoped to one is about that one: another repository's condemnation was judged by a plan this
    // run did not apply.
    ArtifactRepository mine = repositoryService.ensure(DAEMON_REPO, DaemonBinariesProfile.KEY);
    ArtifactRepository theirs = repositoryService.ensure(OTHER_REPO, DaemonBinariesProfile.KEY);
    String myBlob = olderThanTheBlobGrace(41);
    String theirBlob = olderThanTheBlobGrace(42);
    seedLadder(DAEMON_REPO, myBlob);
    seedLadder(OTHER_REPO, theirBlob);

    // Condemn: a scoped run condemns its own repository's dead identity only.
    GcRepositorySweepReport condemnedMine = sweepScoped(mine, GcPins.none());
    assertEquals(List.of(OLD_IDENTITY), named(condemnedMine.condemned()));
    assertEquals(Set.of(DAEMON_REPO), repositoriesCondemned(), "the twin was not condemned");

    // The whole store condemns the twin too.
    sweep(GcPins.none());
    assertEquals(Set.of(DAEMON_REPO, OTHER_REPO), repositoriesCondemned());

    // Clear: a scoped run whose keep-set names the version lifts its own repository's condemnation
    // and leaves the twin's standing, although the same pin would name it.
    GcRepositorySweepReport clearedTheirs = sweepScoped(theirs, ladder(OLD));
    assertEquals(List.of(OLD_IDENTITY), named(clearedTheirs.cleared()));
    assertEquals(OTHER_REPO, clearedTheirs.cleared().get(0).repository());
    assertEquals(Set.of(DAEMON_REPO), repositoriesCondemned());

    // Purge: past the grace, a run scoped to the twin takes nothing of mine — it re-condemns its
    // own with a fresh clock, and my ripe condemnation is not its to act on.
    ageCondemnationsPastGrace();
    GcRepositorySweepReport scopedTheirs = sweepScoped(theirs, GcPins.none());
    assertEquals(List.of(), scopedTheirs.deleted());
    assertEquals(List.of(OLD_IDENTITY), named(scopedTheirs.condemned()));
    assertTrue(daemonStored(DAEMON_REPO), "out of scope, ripe or not");
    assertTrue(blobStore.exists(myBlob));

    // And a run scoped to mine purges mine and leaves the twin's fresh condemnation standing.
    GcRepositorySweepReport scopedMine = sweepScoped(mine, GcPins.none());
    assertEquals(List.of(OLD_IDENTITY), named(scopedMine.deleted()));
    assertFalse(daemonStored(DAEMON_REPO));
    assertFalse(blobStore.exists(myBlob));
    assertTrue(daemonStored(OTHER_REPO));
    assertTrue(blobStore.exists(theirBlob));
    assertEquals(Set.of(OTHER_REPO), repositoriesCondemned());
  }

  @Test
  void theDryRunStillReportsEveryDeadIdentityAndMarksTheOnesAlreadyCondemned() throws Exception {
    // A plan reads condemnations and never writes one, and `dead` keeps meaning what the rule says:
    // `condemned` is its split into "a sweep now purges" (purgeAt passed) and "a sweep now
    // condemns" (everything else in dead).
    String oldBlob = olderThanTheBlobGrace(51);
    seedLadder(DAEMON_REPO, oldBlob);

    GcTypePlan before = daemonPlan();
    assertEquals(List.of(OLD_IDENTITY), named(before.dead()));
    assertEquals(List.of(), before.condemned(), "not condemned yet: a sweep now would condemn it");
    assertEquals(0, condemnedCount(), "and reading the plan condemned nothing");

    GcSweepReport swept = sweep(GcPins.none());
    GcTypePlan after = daemonPlan();
    assertEquals(List.of(OLD_IDENTITY), named(after.dead()), "still dead, still reported as dead");
    assertEquals(1, after.condemned().size());
    assertEquals(swept.runId(), after.condemned().get(0).runId());
    assertTrue(
        after.condemned().get(0).purgeAt().isAfter(Instant.now()),
        "inside the grace: a sweep now would leave it standing");
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** A stored binary written seven hours ago: past the PT6H blob grace, which is the whole point. */
  private String olderThanTheBlobGrace(int size) throws Exception {
    String blobId = store(filled(size, (byte) size));
    backdate(blobId, Duration.ofHours(7));
    return blobId;
  }

  /** The old version plus the two newest releases the belt keeps, in one repository. */
  private void seedLadder(String repository, String oldBlob) throws Exception {
    repositoryService.ensure(repository, DaemonBinariesProfile.KEY);
    daemonRow(repository, OLD, oldBlob, Instant.now().minus(Duration.ofDays(30)));
    daemonRow(repository, "2026.701.20", store(filled(60, (byte) 60)), Instant.now());
    daemonRow(repository, "2026.801.30", store(filled(61, (byte) 61)), Instant.now());
  }

  private void daemonRow(String repository, String version, String blobId, Instant publishedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              DaemonBinary row = new DaemonBinary();
              row.repository = repository;
              row.name = CI;
              row.version = version;
              row.blobId = blobId;
              row.sizeBytes = 1;
              row.publishedAt = publishedAt;
              daemonBinaries.persist(row);
            });
  }

  /** The aggregate a run would have read, with qits-ci naming these rungs of its ladder. */
  private static GcPins ladder(String... versions) {
    return new GcPins(Map.of(), CI, Set.of(versions), Set.of(), List.of());
  }

  private GcSweepReport sweep(GcPins pins) {
    return sweeps.execute(census.take(), List.of(strategy), pins);
  }

  private GcRepositorySweepReport sweepScoped(ArtifactRepository row, GcPins pins) {
    return sweeps.execute(row, census.take(), List.of(strategy), pins);
  }

  private GcTypeSweepResult daemonLine(GcSweepReport report) {
    return sweepLine(report, DaemonBinariesProfile.KEY);
  }

  private GcTypePlan daemonPlan() {
    GcPlanReport report = planner.plan(census.take(), List.of(strategy), GcPins.none());
    return report.types().stream()
        .filter(
            line -> RepositoryTypeProfile.wireNameOf(DaemonBinariesProfile.KEY).equals(line.type()))
        .findFirst()
        .orElseThrow();
  }

  private boolean daemonStored(String repository) {
    daemonBinaries.getEntityManager().clear();
    return daemonBinaries.findOne(repository, CI, OLD).isPresent();
  }

  private GcCondemnations.Condemnation onlyCondemnation() {
    List<GcCondemnations.Condemnation> all = condemnations.all();
    assertEquals(1, all.size(), all.toString());
    return all.get(0);
  }

  private Set<String> repositoriesCondemned() {
    return Set.copyOf(
        condemnations.all().stream().map(GcCondemnations.Condemnation::repository).toList());
  }
}
