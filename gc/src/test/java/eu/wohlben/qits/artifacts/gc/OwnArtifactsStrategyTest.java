package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The own-artifacts engine: the belt (last two releases per group), the pin, and the window — in
 * that order, which is the order that makes it safe.
 *
 * <p>Plain JUnit against {@link FakeGcTypeAdapter} for the cache engine's reason: what a release is
 * and which of two is newer are the adapter's answers, so a case driven through a real type would
 * test that type's parsing rather than this engine's counting.
 *
 * <p><b>The window here is P90D and no shipped type carries it any more</b>, which is deliberate:
 * this suite is about the engine's counting, and the engine still behaves exactly this way for
 * whatever window it is handed. What the deployed configuration hands it is {@code P0D} — the
 * realignment of 2026-09-05 — and that has a case of its own below, because zero is where the rule's
 * last branch stops being a window at all.
 */
class OwnArtifactsStrategyTest {

  private static final Duration WINDOW = Duration.ofDays(90);
  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private final OwnArtifactsStrategy engine = new OwnArtifactsStrategy();

  @Test
  void theLastTwoReleasesOfAGroupSurviveAnyAgeAndTheThirdOneDoesNot() {
    // The settlement's belt, exactly as worded: last 2, not every release. The third-oldest release
    // is as untouched as the two above it and dies anyway — which is what makes this a collector
    // rather than an archive. An older release someone still installs would be accessed, and the
    // window would then keep it; that is the next case.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("@qits/ui@0.0.1", "@qits/ui", true, daysAgo(400), "v1")
            .add("@qits/ui@0.0.2", "@qits/ui", true, daysAgo(380), "v2")
            .add("@qits/ui@0.0.3", "@qits/ui", true, daysAgo(360), "v3");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of("@qits/ui@0.0.1"), identities(plan.dead()));
    assertEquals(List.of("@qits/ui@0.0.2", "@qits/ui@0.0.3"), identities(plan.kept()));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, plan.kept().get(0).rule());
    assertEquals(Set.of("v1"), plan.blobsReleased());
    assertEquals(Set.of("v2", "v3"), plan.blobsRetained());
  }

  @Test
  void anOlderReleaseStillBeingInstalledSurvivesOnUseRatherThanOnPolicy() {
    // The other half of the settlement's sentence. The 0.0.1 line is three releases down the belt
    // and something resolved it last week, so the window keeps it — and the report says "accessed",
    // not "release", because naming the rule that actually saved it is the point of the report.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("@qits/ui@0.0.1", "@qits/ui", true, daysAgo(7), "v1")
            .add("@qits/ui@0.0.2", "@qits/ui", true, daysAgo(380), "v2")
            .add("@qits/ui@0.0.3", "@qits/ui", true, daysAgo(360), "v3");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of(), plan.dead());
    assertEquals(OwnArtifactsStrategy.keptAccessed(WINDOW), ruleFor(plan.kept(), "@qits/ui@0.0.1"));
  }

  @Test
  void theBeltIsCountedPerIdentityGroupSoOnePackageCannotSpendAnothersReleases() {
    // Groups are why the engine needs the adapter at all for this rule: two releases of one package
    // must not make a second package's only release eligible.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("@qits/ui@1.0.0", "@qits/ui", true, daysAgo(400), "a")
            .add("@qits/ui@1.1.0", "@qits/ui", true, daysAgo(390), "b")
            .add("@qits/angular@0.0.1", "@qits/angular", true, daysAgo(500), "c");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of(), plan.dead());
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "@qits/angular@0.0.1"),
        "a group with fewer releases than the belt keeps all of them");
  }

  @Test
  void aBuildThatIsNotAReleaseGetsNoBeltAndAgesOutLikeAnythingElse() {
    // Own-ness earns version protection; being newest does not. The prerelease here is this group's
    // most recent identity by the adapter's own ordering and dies anyway, because nothing has
    // resolved it inside the window and it is not a release.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("@qits/ui@1.0.0", "@qits/ui", true, daysAgo(400), "a")
            .add("@qits/ui@1.1.0-main.gab854a1", "@qits/ui", false, daysAgo(120), "b");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of("@qits/ui@1.1.0-main.gab854a1"), identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
  }

  @Test
  void aPinKeepsAnIdentityTheBeltAndTheWindowBothCondemned() {
    // The keep-class that has to be checked first: a container running untouched for a year still
    // pulls its image sha on restart, and no access timestamp knows that. Reported under the pin's
    // own name, so the report says which service is holding it.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("qits-ci-daemon@2026.701.1", "qits-ci-daemon", false, daysAgo(365), "old")
            .add("qits-ci-daemon@2026.801.1", "qits-ci-daemon", true, daysAgo(300), "new");
    GcPinned pinned =
        candidate ->
            candidate.identity().endsWith("2026.701.1") ? "pinned by qits-ci daemon ladder" : null;

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, pinned);

    assertEquals(List.of(), plan.dead());
    assertEquals(
        "pinned by qits-ci daemon ladder", ruleFor(plan.kept(), "qits-ci-daemon@2026.701.1"));
    assertEquals(Set.of("old", "new"), plan.blobsRetained());
  }

  @Test
  void whichReleaseIsNewestIsTheAdaptersAnswerAndNotTheEnumerationOrder() {
    // The seam earning its keep: "newest" is semver precedence in one type and a row timestamp in
    // another. This adapter says its identities came newest-first, so the engine must keep the two
    // it enumerates FIRST — an engine reading enumeration order as age keeps the wrong two here.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .addedNewestFirst()
            .add("app:newest", "app", true, daysAgo(380), "a")
            .add("app:middle", "app", true, daysAgo(390), "b")
            .add("app:oldest", "app", true, daysAgo(400), "c");

    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(List.of("app:oldest"), identities(plan.dead()));
  }

  @Test
  void theDeletionMechanicsStayWithTheTypeRatherThanWithTheEngine() {
    // The engine condemns; the adapter removes. Nothing here knows what a row is, which is what
    // lets one engine serve four types without a line of shared deletion code.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter().add("app:old", "app", false, daysAgo(400), "a");
    GcStrategy.Plan plan = engine.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    GcStrategy.Applied applied = adapter.delete(plan, Graces.nothingYoung());

    assertSame(plan, adapter.deleted);
    assertEquals(plan.dead(), applied.deleted());
  }

  @Test
  void atAZeroWindowTheKeepClassesAreTheWholeRetentionPolicy() {
    // The shipped configuration since 2026-09-05, and the doctrine in one case: with the window at
    // zero, an identity lives because something NAMES it and for no other reason. The version pulled
    // one second ago dies beside the one nobody has touched in a year; the belt and the pin are
    // untouched, because they never asked what time it was.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .add("@qits/ui@0.0.1", "@qits/ui", true, daysAgo(400), "v1")
            .add("@qits/ui@0.0.2", "@qits/ui", true, daysAgo(380), "v2")
            .add("@qits/ui@0.0.3", "@qits/ui", true, daysAgo(360), "v3")
            .add("@qits/ui@1.0.0-main.gab854a1", "@qits/ui", false, NOW.minusSeconds(1), "warm")
            .add("@qits/ui@1.0.0-main.gcafe123", "@qits/ui", false, daysAgo(400), "pinned");
    GcPinned pinned =
        candidate -> candidate.identity().endsWith("gcafe123") ? "pinned by something live" : null;

    GcStrategy.Plan plan = engine.plan(adapter, Duration.ZERO, NOW, pinned);

    assertEquals(
        List.of("@qits/ui@0.0.1", "@qits/ui@1.0.0-main.gab854a1"),
        identities(plan.dead()),
        "the third release AND the build pulled a second ago — being warm is not a keep any more");
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(Duration.ZERO),
        ruleFor(plan.dead(), "@qits/ui@1.0.0-main.gab854a1"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "@qits/ui@0.0.3"));
    assertEquals("pinned by something live", ruleFor(plan.kept(), "@qits/ui@1.0.0-main.gcafe123"));
  }

  @Test
  void theRuleSentenceNamesTheBeltAndTheConfiguredWindow() {
    assertTrue(OwnArtifactsStrategy.rule(WINDOW).contains("P90D"));
    assertTrue(OwnArtifactsStrategy.rule(WINDOW).contains("last 2 released versions"));
    assertTrue(
        OwnArtifactsStrategy.rule(WINDOW).contains("use keeps it alive"),
        "a real window keeps what is in use, and the sentence may say so");
    // At zero it must not: the tail of that sentence would be a promise about a window there is
    // none of, and a keep-rule that says something false is the line a reviewer trusts.
    assertTrue(OwnArtifactsStrategy.rule(Duration.ZERO).contains("P0D"));
    assertTrue(
        OwnArtifactsStrategy.rule(Duration.ZERO).contains("keep-classes ARE the retention policy"));
    assertFalse(OwnArtifactsStrategy.rule(Duration.ZERO).contains("use keeps it alive"));
  }

  private static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
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
