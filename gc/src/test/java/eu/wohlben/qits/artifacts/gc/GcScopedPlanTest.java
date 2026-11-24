package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reading one repository's share off a type's plan — the whole of the per-repository figures.
 *
 * <p>Plain JUnit, because there is no store in the question: a scoped plan is an algebraic reading
 * of a plan the engines already produced, and the reason it is safe is that both engines record
 * <em>which</em> repository let go of which blob while the candidate is still in hand. What these
 * cases pin is that recording and the one subtraction that must never happen.
 */
class GcScopedPlanTest {

  private static final Duration WINDOW = Duration.ofDays(30);
  private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

  private final OwnArtifactsStrategy own = new OwnArtifactsStrategy();

  @Test
  void aBlobCondemnedInTwoRepositoriesStaysRetainedInEachScopedPlan() {
    // The correctness core, and the case that decides the shape of the whole feature. The same
    // bytes are cached under two mirror namespaces and both have gone cold, so the type's plan
    // condemns both identities and releases that blob once. Scoped to one namespace, the OTHER
    // namespace's row is still standing — so the blob must come back as RETAINED there, and in the
    // other scope too. The naive reading ("released, minus what the other repositories released")
    // gets exactly this case wrong and unlinks bytes a live row still serves.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .addIn("quay", "quay/tool:1", "quay/tool", false, daysAgo(40), "shared", "quay-only")
            .addIn("hub", "hub/tool:1", "hub/tool", false, daysAgo(40), "shared", "hub-only");

    GcStrategy.Plan plan = own.plan(adapter, WINDOW, NOW, GcPinned.NONE);

    assertEquals(Set.of("shared", "quay-only", "hub-only"), plan.blobsReleased());
    assertEquals(
        Map.of(
            "quay", Set.of("shared", "quay-only"),
            "hub", Set.of("shared", "hub-only")),
        plan.releasedByRepository(),
        "each engine records which repository released which blob, while it still knows");

    GcStrategy.Plan quay = plan.scopedTo("quay");

    assertEquals(List.of("quay/tool:1"), identities(quay.dead()));
    assertEquals(Set.of("shared", "quay-only"), quay.blobsReleased());
    assertTrue(
        quay.blobsRetained().contains("shared"),
        "hub's row still names it, and hub is not being swept: " + quay.blobsRetained());
    assertTrue(
        !quay.blobsRetained().contains("quay-only"),
        "nothing else names this one, so quay's scope may free it");

    GcStrategy.Plan hub = plan.scopedTo("hub");

    assertEquals(Set.of("shared", "hub-only"), hub.blobsReleased());
    assertTrue(
        hub.blobsRetained().contains("shared"),
        "and symmetrically: neither scope may free bytes the other still rows");
  }

  @Test
  void aScopedPlanStatesTheWholeTypesLiveSetSoTheSweepConsumesItUnchanged() {
    // The invariant that lets BlobSweep take a scoped plan with no changes at all: a type's plan
    // states what the type still references AFTER the plan, not a delta. Scoping narrows the dead
    // list and widens the retained set — never the other way round — so everything outside the
    // scope reads to the reconciliation exactly as a census would have reported it.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .addIn("npm", "@qits/a@1.0.0", "npm/@qits/a", false, daysAgo(40), "cold-a")
            .addIn("npm", "@qits/a@2.0.0", "npm/@qits/a", false, daysAgo(1), "warm-a")
            .addIn("other", "@other/b@1.0.0", "other/@other/b", false, daysAgo(40), "cold-b");

    GcStrategy.Plan scoped = own.plan(adapter, WINDOW, NOW, GcPinned.NONE).scopedTo("npm");

    assertEquals(List.of("@qits/a@1.0.0"), identities(scoped.dead()));
    assertEquals(List.of("@qits/a@2.0.0"), identities(scoped.kept()), "kept is scoped too");
    assertEquals(Set.of("cold-a"), scoped.blobsReleased());
    assertEquals(
        Set.of("warm-a", "cold-b"),
        scoped.blobsRetained(),
        "the other repository's condemned blob is live in this view, because its row is");
    assertEquals(
        Map.of("npm", Set.of("cold-a")),
        scoped.releasedByRepository(),
        "a scoped plan is a plan: it carries its own attribution and can be scoped again");
  }

  @Test
  void scopingAPlanThatCondemnsNothingOfTheRepositoryIsAnEmptyPlanRatherThanAnError() {
    // The common case on a real store: most repositories have nothing to collect on most runs, and
    // the honest answer is a plan with an empty dead list whose retained set still accounts for
    // everything the type holds. A repository absent from the map is not a special case.
    FakeGcTypeAdapter adapter =
        new FakeGcTypeAdapter()
            .addIn("quay", "quay/tool:1", "quay/tool", false, daysAgo(40), "cold");

    GcStrategy.Plan hub = own.plan(adapter, WINDOW, NOW, GcPinned.NONE).scopedTo("hub");

    assertEquals(List.of(), hub.dead());
    assertEquals(List.of(), hub.kept());
    assertEquals(Set.of(), hub.blobsReleased());
    assertEquals(Set.of("cold"), hub.blobsRetained(), "quay's row is standing in hub's view");
  }

  @Test
  void aPlanWrittenByHandAttributesItsWholeReleasedSetToEveryRepositoryItCondemnsIn() {
    // Hand-built plans exist — the sweep's mechanism cases build one to stand in for a policy bug —
    // and they know only the union. The derivation is exact for the single-repository case, and
    // deliberately conservative beyond it: each scope then retains what the other released, so such
    // a plan reports nothing sweepable rather than guessing whose bytes they were.
    GcStrategy.Plan one =
        new GcStrategy.Plan(
            List.of(new GcIdentity("npm", "@qits/a@1.0.0", "superseded")),
            List.of(),
            Set.of("tarball"),
            Set.of());

    assertEquals(Map.of("npm", Set.of("tarball")), one.releasedByRepository());
    assertEquals(Set.of("tarball"), one.scopedTo("npm").blobsReleased());

    GcStrategy.Plan two =
        new GcStrategy.Plan(
            List.of(
                new GcIdentity("npm", "@qits/a@1.0.0", "superseded"),
                new GcIdentity("other", "@other/b@1.0.0", "superseded")),
            List.of(),
            Set.of("tarball"),
            Set.of());

    assertTrue(
        two.scopedTo("npm").blobsRetained().contains("tarball"),
        "no attribution means no scope may free anything");
  }

  private static Instant daysAgo(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }
}
