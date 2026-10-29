package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * One repository type's garbage collection: which of <b>its</b> identities are dead, and which blobs
 * that changes.
 *
 * <p>The seam exists so the substrate can stay policy-free, and it is drawn where it is for one
 * reason: an identity — a tag, a version, a record row — has a meaning only inside its type, while a
 * blob has none anywhere. So a strategy answers only about identities and the blobs they name, and
 * <b>never</b> decides that a blob may be unlinked. Which blobs lost their last reference across all
 * types is {@link BlobSweep}'s answer, and only its answer.
 *
 * <p><b>One strategy per type, and the rule inside it is an engine's.</b> This used to read "sharing
 * no policy code", on the argument that docker's and npm's rules only look alike by coincidence.
 * The user's settlement of 2026-08-05 ({@code artifacts-gc-plan.md}) overturned that: the rule is an
 * engine's — {@link OwnArtifactsStrategy} here, and the cache-eviction engine in
 * qits-platform-mirror, which is where the types it collects went — and configuration says which
 * type runs which. So an implementation of this interface is a <b>binder</b>: it names its
 * type and its {@link GcTypeAdapter}, and carries no rule of its own. What survives the settlement
 * is the count: exactly one strategy may claim a type, and two claimants are a collision the
 * planner reports rather than a merge it performs. Registering one is a CDI bean of this type and
 * nothing else.
 *
 * <p><b>Failing is a supported answer, and it is fail-closed.</b> A strategy that cannot establish
 * its keep-set must throw rather than plan without it. The planner catches it, reports the type as
 * failed, and treats every blob the census attributes to that type as live, so a broken dependency
 * reclaims nothing instead of reclaiming something it cannot vouch for.
 *
 * <p><b>Live pins arrive as an argument, fetched once per run.</b> A strategy does not dial another
 * service itself: {@code GcPinSources} reads qits-platform-deployments and qits-ci at the start of every plan and
 * every sweep, and {@link #plan} is handed the result. Two fetches inside one run can disagree, and
 * a strategy holding its own source is how that happens. A strategy whose keep-set depends on those
 * pins says so with {@link #readsPins()}, and is not planned at all on a run whose pins are
 * incomplete.
 */
public interface GcStrategy {

  /**
   * The STORED type key this strategy collects ({@code OCI_IMAGES}). Exactly one strategy may claim
   * a type. A key rather than an enum constant because repository types are registered openly now —
   * a profile bean claims a key, and this claims the same one.
   */
  String type();

  /**
   * Whether this strategy's keep-set reads {@link GcPins}.
   *
   * <p>Declared rather than inferred, because the consequence is a refusal: on a run where a pin
   * source could not answer, a strategy that reads pins is reported as failed and never asked to
   * plan, while the rest of the report is still computed. A strategy that quietly ignored an
   * incomplete aggregate would plan its type against "nothing is pinned", which is the answer that
   * condemns everything.
   */
  default boolean readsPins() {
    return false;
  }

  /**
   * A standing sentence the reports carry beside this strategy's line, or null.
   *
   * <p>Exists for the strategies whose whole plan needs a caption — the CI stubs use it to name the
   * rule they intend and the fact that nothing has ever produced their content. A strategy with
   * rules that speak for themselves returns null, which is what the default does.
   */
  default String note() {
    return null;
  }

  /**
   * Reads the census and says what would die.
   *
   * <p>Pure and side-effect free: this is called for a dry-run report, and a report that changed the
   * store would be the one thing nobody could review.
   *
   * @param census the store as it stands, including this type's live blob set
   * @param pins every live pin the run read at its start — a keep-class checked before any rule of
   *     this strategy's own, and always {@link GcPins#complete()} when {@link #readsPins()} is true
   * @throws RuntimeException the keep-set cannot be established safely — the type is reported as
   *     failed and nothing of it is planned
   */
  Plan plan(LiveBlobCensus.Census census, GcPins pins);

  /**
   * Deletes the identity rows of a plan this strategy produced <b>moments ago</b> — the execute
   * half of the seam, called only by {@code GcSweepExecutor} and only on a plan computed in the
   * same request. A stored or hand-edited plan must never reach this method: the store moves, and
   * applying a stale plan is how the wrong identity dies.
   *
   * <p><b>The grace window gates identities here, not just blobs at the unlink.</b> The reason is
   * structural and worth keeping in view: a blob may only be swept by <em>losing</em> its last
   * identity row, so deleting a row while the blob's file is still inside the grace window would
   * strand the blob forever — row-less, therefore untouchable, and never reclaimed. An identity
   * whose released blobs include one still in grace is therefore <b>withheld whole</b>: its rows
   * stay, the next run re-plans it, and the run after the window matures deletes row and file
   * together. So is an identity whose <b>own row</b> is inside the window, whatever its blobs' age
   * (qits-1732): see {@link GraceWindow} for why the bytes' age alone is the wrong clock.
   *
   * <p>Failures are collected per identity rather than thrown, because a half-applied type must
   * report what it did — the rows already deleted are deleted, and hiding them behind an exception
   * would leave their blobs unswept with nothing in the report to say why.
   *
   * <p>The default refuses a plan that condemns anything: a strategy whose rules can kill must own
   * its deletion mechanics by overriding this. For the strategies that never condemn — the CI stubs
   * — the default is the correct implementation, applied to the empty set.
   *
   * @param plan this strategy's own freshly computed plan
   * @param grace answers whether a blob's file, or an identity's own row, is still inside the grace
   *     window
   */
  default Applied apply(Plan plan, GraceWindow grace) {
    if (!plan.dead().isEmpty()) {
      throw new IllegalStateException(
          getClass().getSimpleName()
              + " condemned "
              + plan.dead().size()
              + " identities but does not implement apply(); a strategy that can kill must own its"
              + " deletion mechanics");
    }
    return Applied.nothing();
  }

  /**
   * Whether something is younger than the sweep's grace window — a blob's file, or an identity's own
   * row. Supplied by the executor, measured back from the run's one clock; the dry run builds its
   * own from the same {@link #since} so the two judge youth by one comparison.
   *
   * <p><b>Two questions, because an identity is young if its row is OR any of its blobs is
   * (qits-1732).</b> The window exists for the push-to-pin gap: an identity pushed a minute ago is
   * named by no pin source yet — no manifest on main references it, no deployment holds it — and
   * the keep-set, however complete, cannot see it. Measured on the blobs alone the window misses
   * exactly the pushes that need it most: the store dedupes, and {@code BlobStore.promote} does not
   * move {@code stored_at} when new content lands on bytes it already holds, so a tag pushed onto an
   * existing manifest, or a docs version whose files are byte-identical to last week's, is
   * <em>outside</em> the window the moment it lands and would go on the next run unpinned. The row
   * is what the push wrote, with a server-set stamp, so the row stamp is the clock that cannot lie
   * about when the identity arrived. Each adapter answers with its own row's stamp: {@code
   * oci_tag.updated_at} (and the manifest's {@code created_at}), {@code npm_version.created_at},
   * the newest {@code maven_artifact.created_at} of a coordinate, {@code daemon_binary.published_at},
   * {@code docs_site.published_at}, {@code sbom_document.created_at}.
   */
  interface GraceWindow {

    /** Whether a blob's file was written inside the window; an unknown blob is not young. */
    boolean withinGrace(String blobId);

    /**
     * Whether an identity's own row was stamped inside the window; a null stamp — a row that
     * carries none — is not young, and its youth is then its blobs' alone.
     */
    boolean withinGrace(Instant stampedAt);

    /**
     * The window as the executor and the dry run both build it: anything written after {@code
     * graceStartsAt} is young.
     *
     * @param lastWrittenAt when a blob's file was written, null for a blob the store does not hold
     */
    static GraceWindow since(Instant graceStartsAt, Function<String, Instant> lastWrittenAt) {
      return new GraceWindow() {
        @Override
        public boolean withinGrace(String blobId) {
          return withinGrace(lastWrittenAt.apply(blobId));
        }

        @Override
        public boolean withinGrace(Instant stampedAt) {
          return stampedAt != null && stampedAt.isAfter(graceStartsAt);
        }
      };
    }
  }

  /**
   * What {@link #apply} actually did, identity by identity.
   *
   * @param deleted the identities whose rows are gone now
   * @param withheldByGraceWindow identities left whole because a blob they release is still inside
   *     the grace window — not lost, re-planned next run
   * @param errors identities that could not be applied, each with its reason; the rest of the plan
   *     was still applied
   */
  record Applied(
      List<GcIdentity> deleted, List<GcIdentity> withheldByGraceWindow, List<String> errors) {

    public Applied {
      deleted = List.copyOf(deleted);
      withheldByGraceWindow = List.copyOf(withheldByGraceWindow);
      errors = List.copyOf(errors);
    }

    public static Applied nothing() {
      return new Applied(List.of(), List.of(), List.of());
    }
  }

  /**
   * What a strategy hands back: the identities, and the two blob sets the reconciliation needs.
   *
   * <p>The two sets may overlap, and that overlap is the point of asking for both. A layer under a
   * dying tag and a surviving one is released <em>and</em> retained; subtracting is the substrate's
   * job, so a strategy never has to reason about the store beyond its own type.
   *
   * @param dead the identities this run would delete, each naming the rule that condemned it
   * @param kept the identities it would keep, each naming the rule that saved it — the half of a
   *     dry-run report that makes the other half reviewable
   * @param blobsReleased every blob the dead identities reference
   * @param blobsRetained every blob this type still references once the dead ones are gone —
   *     the type's live set <em>after</em> the plan, not the delta
   * @param releasedByRepository the same released blobs, split by the {@code artifact_repository}
   *     row the dead identity that named them lives in. The union of its values is {@link
   *     #blobsReleased}; it exists so one repository's share of a type's plan can be read off
   *     ({@link #scopedTo}) without a second planning pass.
   * @param releasedByIdentity the same released blobs once more, split by the dead identity that
   *     named them. It exists so the dry run can judge the identity grace the way a sweep does —
   *     an identity is withheld <b>whole</b>, so every blob of a young identity stays, the old ones
   *     too — and only the engine knows which blobs those are. Empty for a plan that cannot say — a
   *     hand-built one — and then the dry run judges each blob's own age alone, while a sweep's
   *     pre-unlink re-census, which sees the withheld identity's surviving row, protects the rest.
   * @param stampedByIdentity each dead identity's own row stamp ({@link GcCandidate#stampedAt}),
   *     the second half of that same judgement: a dead identity whose row is inside the window is
   *     withheld by a sweep whatever its blobs' age, and a dry run that did not know the stamp would
   *     promise its bytes. An identity absent here has no stamp to judge, which is not young.
   */
  record Plan(
      List<GcIdentity> dead,
      List<GcIdentity> kept,
      Set<String> blobsReleased,
      Set<String> blobsRetained,
      Map<String, Set<String>> releasedByRepository,
      Map<IdentityKey, Set<String>> releasedByIdentity,
      Map<IdentityKey, Instant> stampedByIdentity) {

    public Plan {
      dead = List.copyOf(dead);
      kept = List.copyOf(kept);
      blobsReleased = Set.copyOf(blobsReleased);
      blobsRetained = Set.copyOf(blobsRetained);
      releasedByRepository = copyOfSets(releasedByRepository);
      releasedByIdentity = copyOfSets(releasedByIdentity);
      stampedByIdentity = Map.copyOf(stampedByIdentity);
    }

    /** A plan attributed per repository but not per identity — what a hand-built plan can say. */
    public Plan(
        List<GcIdentity> dead,
        List<GcIdentity> kept,
        Set<String> blobsReleased,
        Set<String> blobsRetained,
        Map<String, Set<String>> releasedByRepository) {
      this(dead, kept, blobsReleased, blobsRetained, releasedByRepository, Map.of(), Map.of());
    }

    /**
     * A plan stated without per-repository attribution — what a hand-built plan can honestly say.
     *
     * <p>An engine knows which candidate released which blob and populates the map exactly. A plan
     * written out by hand knows only the union, so the attribution is derived rather than invented:
     * every repository named among {@link #dead} is credited with the <b>whole</b> released set.
     * For the single-repository case that is exact. For a plan whose dead identities span two
     * repositories it is deliberately conservative — each scope then retains what the other
     * released, so a scoped view of such a plan reports nothing sweepable rather than guessing
     * whose bytes they were.
     */
    public Plan(
        List<GcIdentity> dead,
        List<GcIdentity> kept,
        Set<String> blobsReleased,
        Set<String> blobsRetained) {
      this(dead, kept, blobsReleased, blobsRetained, attributedWhole(dead, blobsReleased));
    }

    /** A strategy with nothing to do — the honest answer for a type whose rules exist but match no row. */
    public static Plan nothingDies(List<GcIdentity> kept, Set<String> live) {
      return new Plan(List.of(), kept, Set.of(), live, Map.of(), Map.of(), Map.of());
    }

    /**
     * This type's plan as it applies to <b>one</b> repository, and nothing else of the type moves.
     *
     * <p><b>Per-repository collection is this filter, never a second planner.</b> Both own-engine
     * adapters qualify their identity groups with the repository name, so no rule of either engine
     * can reach across two repositories — which is what makes "plan the whole type, then read one
     * repository's share off it" produce exactly the plan a repository-only run would have produced.
     * A second planner would be a second policy, and two policies over one type is the mistake this
     * whole design refuses.
     *
     * <p>The retained set is the part that has to be right: it is this type's own retained set
     * <b>plus every blob released in some other repository</b>, because those identities are
     * standing in this view. Subtracting instead — "released, minus the ones this repository
     * released" — gets the one case wrong that matters: a blob condemned in repository A <em>and</em>
     * in repository B is still named by B's surviving row when only A is swept, and a scoped plan
     * that let it through would unlink bytes B still serves.
     *
     * <p>The result satisfies the same invariant a whole-type plan does — it states the type's whole
     * live set after the plan — so {@code BlobSweep.plan}/{@code execute} consume it unchanged.
     */
    public Plan scopedTo(String repository) {
      Set<String> released = releasedByRepository.getOrDefault(repository, Set.of());
      Set<String> retained = new HashSet<>(blobsRetained);
      for (Map.Entry<String, Set<String>> entry : releasedByRepository.entrySet()) {
        if (!entry.getKey().equals(repository)) {
          retained.addAll(entry.getValue());
        }
      }
      return new Plan(
          in(dead, repository),
          in(kept, repository),
          released,
          retained,
          released.isEmpty() ? Map.of() : Map.of(repository, released),
          in(releasedByIdentity, repository),
          in(stampedByIdentity, repository));
    }

    private static <V> Map<IdentityKey, V> in(Map<IdentityKey, V> byIdentity, String repository) {
      Map<IdentityKey, V> mine = new HashMap<>();
      byIdentity.forEach(
          (key, value) -> {
            if (key.repository().equals(repository)) {
              mine.put(key, value);
            }
          });
      return mine;
    }

    private static List<GcIdentity> in(List<GcIdentity> identities, String repository) {
      return identities.stream().filter(identity -> repository.equals(identity.repository())).toList();
    }

    private static Map<String, Set<String>> attributedWhole(
        List<GcIdentity> dead, Set<String> blobsReleased) {
      if (dead.isEmpty() || blobsReleased.isEmpty()) {
        return Map.of();
      }
      Map<String, Set<String>> attributed = new HashMap<>();
      for (GcIdentity identity : dead) {
        attributed.put(identity.repository(), blobsReleased);
      }
      return attributed;
    }

    private static <K> Map<K, Set<String>> copyOfSets(Map<K, Set<String>> byKey) {
      Map<K, Set<String>> copy = new HashMap<>();
      byKey.forEach((key, blobs) -> copy.put(key, Set.copyOf(blobs)));
      return Map.copyOf(copy);
    }
  }

  /**
   * Which identity, without the rule that judged it — the key a plan's per-identity attribution is
   * held under.
   *
   * <p>The rule is left out on purpose: it is a sentence about the identity, not part of which one
   * it is, and a lookup keyed on it would miss the moment a window is reconfigured or a keep-class
   * renamed.
   */
  record IdentityKey(String repository, String identity) {

    public static IdentityKey of(GcIdentity identity) {
      return new IdentityKey(identity.repository(), identity.identity());
    }
  }
}
