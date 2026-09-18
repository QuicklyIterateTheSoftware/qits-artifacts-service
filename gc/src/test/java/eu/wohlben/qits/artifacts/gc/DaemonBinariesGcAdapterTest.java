package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
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
 * The own engine over the daemon type's facts — the first strategy this type has ever had.
 *
 * <p>The hazard here is not disk, it is that the platform <b>executes</b> what this type holds: CI
 * downloads a daemon binary and runs it, so a version deleted out from under qits-ci's ladder breaks
 * every build at once. Every case below is therefore about a keep — the belt, both rungs of the pin,
 * the digest spelling of a pin — with exactly one about a kill, and one about the row-less blobs
 * that must stay out of reach whatever anything plans.
 */
@QuarkusTest
class DaemonBinariesGcAdapterTest extends GcFixture {

  /** The configured window for this type — zero since 2026-09-05, so nothing is kept by age. */
  private static final Duration WINDOW = Duration.ZERO;

  private static final String CI = "qits-ci-daemon";

  /** The daemon qits-ci's ladder says nothing about, and the one the rot was measured on. */
  private static final String CLI = "qits-platform-access-cli";

  @Inject DaemonBinariesGcStrategy strategy;

  @Test
  void theLastTwoVersionsOfADaemonStayAndTheOlderOnesAgeOut() throws Exception {
    // Every row of this type is a release — publishes come from the release pipeline and versions
    // are immutable — so the settlement's belt is the whole structural rule here: last 2 per daemon,
    // whatever their age, and the rest only once nothing has launched them for P3D.
    repository();
    String oldest = blob(11);
    daemonRow(CI, "2026.601.10", oldest, daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(12), daysAgo(300), null);
    daemonRow(CI, "2026.801.30", blob(13), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(CI + "@2026.601.10"), identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), CI + "@2026.701.20"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), CI + "@2026.801.30"));
    assertEquals(Set.of(oldest), plan.blobsReleased(), "one version, one binary — no closure here");
  }

  @Test
  void anAdoptedDigestVersionRanksBelowEveryCalverRatherThanAboveThem() throws Exception {
    // The adopted rows carry the blob's own digest hex as their version, so QITS_CI_DAEMON_VERSION
    // doubles as a coordinate. Compared as a number against a calver it would rank as the newest
    // thing here, which is exactly backwards: it is the legacy bottom rung by construction.
    repository();
    String adopted = blob(21);
    daemonRow(CI, "f".repeat(64), adopted, daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(22), daysAgo(300), null);
    daemonRow(CI, "2026.801.30", blob(23), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(CI + "@" + "f".repeat(64)), identities(plan.dead()));
    assertEquals(Set.of(adopted), plan.blobsReleased());
  }

  @Test
  void bothRungsOfTheCiLadderAreKeptHoweverFarDownTheBeltTheySit() throws Exception {
    // qits-ci answers with the version a run would launch and the fallback beneath it, and neither
    // is a fact any timestamp implies: a runner that has not started in months still fetches its
    // rung the moment one does. So the pin is checked before the belt and before the window, and the
    // report names qits-ci rather than merely saying "kept".
    repository();
    daemonRow(CI, "2026.501.1", blob(31), daysAgo(500), null);
    daemonRow(CI, "2026.502.2", blob(32), daysAgo(450), null);
    daemonRow(CI, "2026.801.30", blob(33), daysAgo(200), null);
    daemonRow(CI, "2026.802.40", blob(34), daysAgo(100), null);

    GcStrategy.Plan plan =
        strategy.plan(census.take(), ladder(CI, "2026.501.1", "2026.502.2"));

    assertEquals(List.of(), plan.dead(), "the belt keeps the top two and the ladder keeps the rest");
    assertEquals(GcPins.BY_CI, ruleFor(plan.kept(), CI + "@2026.501.1"));
    assertEquals(GcPins.BY_CI, ruleFor(plan.kept(), CI + "@2026.502.2"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), CI + "@2026.802.40"));
  }

  @Test
  void aDaemonACarrierCoordinatePinsSurvivesAVersionTheBeltWouldHaveSpent() throws Exception {
    // The gap the ladder cannot cover, and the whole point of the second source. qits-ci's ladder is
    // about qits-ci-daemon; this daemon is the `qits` CLI, whose version is pinned in qits-ci's pom
    // and in qits-workspace-oci's — lines in a manifest, which nothing in this store can see. At a
    // zero window the belt of two is then the entire keep-set, so the pinned version is condemned on
    // the run that finds it and every pipeline fetching it 404s. That is not a hypothetical: it is
    // what happened, and it is why the daemon row exists.
    //
    // The first assertion is the belt WITHOUT the pin, so the keep below is provably the pin's.
    repository();
    daemonRow(CLI, "2026.901.10", blob(121), daysAgo(400), null);
    daemonRow(CLI, "2026.917.20", blob(122), daysAgo(200), null);
    daemonRow(CLI, "2026.918.30", blob(123), daysAgo(100), null);

    assertEquals(
        List.of(CLI + "@2026.901.10"),
        identities(strategy.plan(census.take(), GcPins.none()).dead()),
        "the belt spends it, because the belt is two and this is the third");

    GcStrategy.Plan plan = strategy.plan(census.take(), carrying(CLI + "@2026.901.10"));

    assertEquals(List.of(), plan.dead());
    assertEquals(GcPins.BY_CARRIED_DAEMON, ruleFor(plan.kept(), CLI + "@2026.901.10"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), CLI + "@2026.918.30"),
        "and the belt still speaks for the two it always kept — the pin widens, it does not replace");
  }

  @Test
  void aCarrierPinForOneDaemonSaysNothingAboutAnotherDaemonsIdenticalVersion() throws Exception {
    // The identity is `name@version` and the NAME is half of it. Two daemons released by the same
    // repository on the same day carry the same version string, so a keep-set joined on the version
    // alone would save a binary nobody pinned — and, worse, would read on the receipt as though
    // somebody had.
    repository();
    daemonRow(CI, "2026.901.10", blob(131), daysAgo(400), null);
    daemonRow(CI, "2026.917.20", blob(132), daysAgo(200), null);
    daemonRow(CI, "2026.918.30", blob(133), daysAgo(100), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), carrying(CLI + "@2026.901.10"));

    assertEquals(
        List.of(CI + "@2026.901.10"),
        identities(plan.dead()),
        "the pinned version belongs to a different daemon, so this one is unpinned as before");
  }

  @Test
  void theLadderAnswersForItsOwnRungEvenWhenACarrierPinsTheSameVersion() throws Exception {
    // Both sources can name one identity, and a receipt states one reason. The ladder wins, and the
    // order is a decision about which claim is the stronger one to print: "a runner would launch
    // this" is about a process that exists, "a pom names a coordinate that shipped this" is about
    // source. Neither keep is in doubt; which sentence a reviewer reads is.
    repository();
    daemonRow(CI, "2026.901.10", blob(141), daysAgo(400), null);
    daemonRow(CI, "2026.917.20", blob(142), daysAgo(200), null);
    daemonRow(CI, "2026.918.30", blob(143), daysAgo(100), null);

    GcStrategy.Plan plan =
        strategy.plan(
            census.take(),
            new GcPins(
                Map.of(),
                CI,
                Set.of("2026.901.10"),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(CI + "@2026.901.10"),
                Set.of(),
                Set.of(),
                Set.of(),
                List.of()));

    assertEquals(List.of(), plan.dead());
    assertEquals(GcPins.BY_CI, ruleFor(plan.kept(), CI + "@2026.901.10"));
  }

  @Test
  void aPinSpelledAsADigestKeepsWhicheverRowNamesThoseBytes() throws Exception {
    // The historic spelling, and the reason it is the binder's floor rather than this type's
    // business: QITS_CI_DAEMON_VERSION has been a sha256 digest since the daemon shipped, and it
    // names BYTES. The row whose blob those bytes are is kept even though the version strings do not
    // match anything the ladder said.
    repository();
    String pinned = blob(41);
    daemonRow(CI, "2026.501.1", pinned, daysAgo(500), null);
    daemonRow(CI, "2026.801.30", blob(42), daysAgo(200), null);
    daemonRow(CI, "2026.802.40", blob(43), daysAgo(100), null);

    GcStrategy.Plan plan =
        strategy.plan(
            census.take(),
            new GcPins(Map.of(), CI, Set.of(), Set.of(pinned), List.of()));

    assertEquals(List.of(), plan.dead());
    assertEquals(GcPins.BY_CI, ruleFor(plan.kept(), CI + "@2026.501.1"));
  }

  @Test
  void aDaemonNobodyLaunchesIsNotSavedByAnotherDaemonsReleases() throws Exception {
    // The belt counts per daemon, so a second daemon publishing on a fast cadence must not spend the
    // first one's slots — and a daemon with fewer versions than the belt keeps all of them.
    repository();
    daemonRow(CI, "2026.601.10", blob(51), daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(52), daysAgo(300), null);
    daemonRow("qits-workspace-daemon", "2026.101.1", blob(53), daysAgo(600), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "qits-workspace-daemon@2026.101.1"));
  }

  @Test
  void aDownloadYesterdayIsNoLongerAKeepAndTheLadderIsWhatSpeaksForAnOldRung()
      throws Exception {
    // THE FLIP of 2026-09-05, on the one type where getting it wrong breaks every build at once.
    // This case used to keep the rung fetched yesterday under the window's own sentence; at P0D a
    // fetch is a timestamp and a timestamp cannot say whether a runner will start tomorrow. What can
    // is qits-ci, which answers with both rungs of its ladder on every run — so the second half of
    // this case is the same store with the ladder read.
    repository();
    daemonRow(CI, "2026.501.1", blob(61), daysAgo(500), daysAgo(1));
    daemonRow(CI, "2026.502.2", blob(62), daysAgo(500), null);
    daemonRow(CI, "2026.801.30", blob(63), daysAgo(200), null);
    daemonRow(CI, "2026.802.40", blob(64), daysAgo(100), null);

    GcStrategy.Plan onAccessAlone = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(CI + "@2026.501.1", CI + "@2026.502.2"),
        identities(onAccessAlone.dead()).stream().sorted().toList());
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(onAccessAlone.dead(), CI + "@2026.501.1"));

    GcStrategy.Plan pinned = strategy.plan(census.take(), ladder(CI, "2026.501.1"));

    assertEquals(List.of(CI + "@2026.502.2"), identities(pinned.dead()));
    assertEquals(GcPins.BY_CI, ruleFor(pinned.kept(), CI + "@2026.501.1"));
  }

  @Test
  void aFreshlyPublishedVersionNobodyHasDownloadedYetReadsAsYoungRatherThanAsNeverRead()
      throws Exception {
    // accessed_at is null until the first version-addressed GET, and V11 deliberately backfilled
    // nothing. Publication has to count as the first access or every publish would be eligible from
    // its first minute — this is that fold, asserted rather than assumed.
    repository();
    daemonRow(CI, "2026.601.10", blob(71), daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(72), daysAgo(300), null);
    daemonRow(CI, "2026.803.50", blob(73), Instant.now(), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), CI + "@2026.803.50"),
        "the belt is what keeps it — at a zero window the fold buys a fresh publish nothing, and"
            + " what protects its BYTES until a pin names them is the grace window instead");
    assertEquals(List.of(CI + "@2026.601.10"), identities(plan.dead()));
  }

  @Test
  void aCondemnedVersionLosesItsRowThroughTheFunnelAndGainsNoTombstone() throws Exception {
    // The execute half. There is no tombstone concept on this type and that is a decision: a daemon
    // version is resolved by a pin a bootstrap re-reads, not by a lockfile, so a re-release at the
    // same version after a collection is legitimate rather than a silent content swap.
    repository();
    String doomed = blob(81);
    backdate(doomed, Duration.ofDays(30));
    daemonRow(CI, "2026.601.10", doomed, daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(82), daysAgo(300), null);
    daemonRow(CI, "2026.801.30", blob(83), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(CI + "@2026.601.10"), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    daemonBinaries.getEntityManager().clear();
    assertTrue(
        daemonBinaries.findOne(DAEMON_REPO, CI, "2026.601.10").isEmpty(), "the row is gone");
    assertTrue(daemonBinaries.findOne(DAEMON_REPO, CI, "2026.701.20").isPresent());
  }

  @Test
  void aBinaryStillInsideTheGraceWindowWithholdsTheWholeIdentityRowIntact() throws Exception {
    // The strand hazard: a row deleted over a young file leaves the file row-less, and row-less
    // blobs are untouchable by construction — so it would never be reclaimed at all.
    repository();
    String doomed = blob(91);
    daemonRow(CI, "2026.601.10", doomed, daysAgo(400), null);
    daemonRow(CI, "2026.701.20", blob(92), daysAgo(300), null);
    daemonRow(CI, "2026.801.30", blob(93), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(doomed));

    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of(CI + "@2026.601.10"), identities(applied.withheldByGraceWindow()));
    daemonBinaries.getEntityManager().clear();
    assertTrue(daemonBinaries.findOne(DAEMON_REPO, CI, "2026.601.10").isPresent());
  }

  @Test
  void theRowLessLegacyBlobsStayOutOfReachBecauseNoIdentityEverNamedThem() throws Exception {
    // The settlement's answer to the three orphaned ELF blobs is an ops action, once, by hand — not
    // an adoption path here and not an allowlist. What makes that safe is structural rather than
    // careful: a blob becomes a candidate only by LOSING its last identity row, and these never had
    // one, so nothing this type plans can reach them.
    repository();
    String rowless = blob(101);
    daemonRow(CI, "2026.801.30", blob(102), daysAgo(200), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertFalse(plan.blobsReleased().contains(rowless));
    assertFalse(plan.blobsRetained().contains(rowless));
  }

  @Test
  void anIncompletePinAggregateIsRefusedRatherThanReadAsNothingIsPinned() throws Exception {
    // The one type where planning against "nothing is pinned" would delete the binary every CI step
    // downloads. qits-ci unreachable refuses this type outright, and the run aborts around it.
    repository();
    daemonRow(CI, "2026.601.10", blob(111), daysAgo(400), null);

    LiveBlobCensus.Census taken = census.take();
    GcPins broken =
        new GcPins(
            Map.of(),
            "",
            Set.of(),
            Set.of(),
            List.of("qits-ci daemon pin: unreachable at http://qits-ci:8080/ci/api"));

    IllegalStateException aborted =
        assertThrows(IllegalStateException.class, () -> strategy.plan(taken, broken));
    assertTrue(aborted.getMessage().contains("qits-ci"));
    assertTrue(strategy.readsPins());
  }

  @Test
  void anEmptyRepositoryPlansNothingRatherThanFailing() throws Exception {
    repository();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(DaemonBinariesProfile.KEY, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(taken.live(DaemonBinariesProfile.KEY).keySet(), plan.blobsRetained());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void repository() {
    repositoryService.ensure(DAEMON_REPO, DaemonBinariesProfile.KEY);
  }

  private String blob(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  /** The aggregate a run would have read, with qits-ci naming both rungs of its ladder. */
  private static GcPins ladder(String daemon, String... versions) {
    return new GcPins(Map.of(), daemon, Set.of(versions), Set.of(), List.of());
  }

  /**
   * The aggregate a run would have read, with these daemon identities carried by a coordinate some
   * repository's manifest on main references — and with qits-ci's ladder empty, so a keep here can
   * only be the carrier's.
   */
  private static GcPins carrying(String... identities) {
    return new GcPins(
        Map.of(),
        "",
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(identities),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of());
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
