package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The own-artifacts engine: the last two released versions of every identity group live forever,
 * everything a live pin names lives, and the rest ages out.
 *
 * <p>The settlement's second rule, and the belt in it is deliberately narrow: <b>last 2</b> releases
 * per group, not every release. Anything older survives on a live pin, or — while the window is
 * above zero — on use. That is what makes this a collector at all rather than an archive with extra
 * steps.
 *
 * <p><b>Since 2026-09-05 every own type ships a window of {@code P0D}, and that changes what this
 * engine is.</b> The rule below is unchanged, but with a zero window its last branch condemns
 * everything the keep-classes did not name: retention IS the keep-set now, and access keeps nothing
 * alive by itself. The window stays a configured input rather than being deleted, because it is the
 * one lever a deployment can raise when a keep-class is temporarily missing — a pin source removed
 * is a window owed.
 *
 * <p><b>The order of the three keep-classes is the safety property.</b> A pin is checked first
 * because it is the only fact that comes from outside this service and the only one an access
 * timestamp cannot imply: a container that has been running untouched for months still pulls its
 * image sha on restart. Releases come next, because they are kept whatever their age. The access
 * window is last, and it is the only rule that can condemn anything.
 *
 * <p><b>What a release is, what a group is, and which of two is newer are all the adapter's</b>
 * ({@link GcTypeAdapter}). This engine counts to two. That split is what lets one engine serve
 * npm-packages, oci-images, maven-packages and daemon-binaries without a line of shared policy
 * between those four types — the framing's rule survives the settlement, it just moved: the types no
 * longer share a <em>rule</em>, they share an <em>engine</em>, and each still owns its own facts.
 *
 * <p>Stateless and not a bean; deletion is {@link GcTypeAdapter#delete}, as for the cache engine.
 */
public final class OwnArtifactsStrategy {

  /** How many released versions per identity group are kept whatever their age. */
  public static final int RELEASES_KEPT = 2;

  /**
   * The rule sentence a report echoes for a type configured as own.
   *
   * <p>The tail changes at a zero window because the sentence would otherwise be false: "use keeps
   * it alive" is a promise about a window, and at {@code P0D} there is none to keep it in.
   */
  public static String rule(Duration window) {
    return "own: always keep the last "
        + RELEASES_KEPT
        + " released versions of every identity group and everything a live pin names; delete the"
        + " rest once unaccessed for longer than "
        + GcPlanner.iso(window)
        + ". "
        + (window.isZero()
            ? "The window is zero, so the keep-classes ARE the retention policy: an identity"
                + " nothing names is condemned on the run that finds it, and use keeps nothing"
                + " alive on its own."
            : "An older release still being installed is accessed, so use keeps it alive where"
                + " policy no longer does.");
  }

  static final String KEPT_RELEASE =
      "among the last " + RELEASES_KEPT + " released versions of this identity group — releases are"
          + " kept by policy, not by access";

  static String keptAccessed(Duration window) {
    return "accessed inside the " + GcPlanner.iso(window) + " window";
  }

  static String deadUnaccessed(Duration window) {
    return "superseded and unaccessed for longer than " + GcPlanner.iso(window);
  }

  /**
   * Reads the adapter's identities and says what would die. Deletes nothing.
   *
   * @param adapter the type's own facts — what exists, what a release is, and how it orders two of
   *     them by age
   * @param window the configured window for that type
   * @param now the run's clock, so a plan and its receipt judge every identity against one instant
   * @param pins what a live service is holding on to, checked before every other rule
   */
  public GcStrategy.Plan plan(
      GcTypeAdapter adapter, Duration window, Instant now, GcPinned pins) {
    return plan(adapter, adapter.enumerate(), window, now, pins);
  }

  /**
   * The same rule over an enumeration the caller already has.
   *
   * <p>Exists because a coordinate pin can only be answered over the whole enumeration ({@link
   * GcTypeAdapter#pinnedBy}), and a binder that enumerated a second time here would judge one run
   * against two snapshots of the store.
   */
  public GcStrategy.Plan plan(
      GcTypeAdapter adapter,
      List<GcCandidate> candidates,
      Duration window,
      Instant now,
      GcPinned pins) {
    Instant cut = now.minus(window);
    Set<GcCandidate> keptReleases = lastReleasesPerGroup(candidates, adapter);

    List<GcIdentity> dead = new ArrayList<>();
    List<GcIdentity> kept = new ArrayList<>();
    Set<String> released = new HashSet<>();
    Set<String> retained = new HashSet<>();
    Map<String, Set<String>> releasedByRepository = new HashMap<>();

    for (GcCandidate candidate : candidates) {
      String pin = pins.pinnedBy(candidate);
      if (pin != null) {
        keep(candidate, pin, kept, retained);
      } else if (keptReleases.contains(candidate)) {
        keep(candidate, KEPT_RELEASE, kept, retained);
      } else if (candidate.unaccessedSince(cut)) {
        dead.add(new GcIdentity(candidate.repository(), candidate.identity(), deadUnaccessed(window)));
        released.addAll(candidate.blobs());
        // Which repository let go of which bytes, recorded while the candidate is in hand — the
        // groups are already repository-qualified, so this is a record of a split the rule made.
        releasedByRepository
            .computeIfAbsent(candidate.repository(), repository -> new HashSet<>())
            .addAll(candidate.blobs());
      } else {
        keep(candidate, keptAccessed(window), kept, retained);
      }
    }

    dead.sort(BY_IDENTITY);
    kept.sort(BY_IDENTITY);
    return dead.isEmpty()
        ? GcStrategy.Plan.nothingDies(kept, retained)
        : new GcStrategy.Plan(dead, kept, released, retained, releasedByRepository);
  }

  /**
   * Each group's newest {@link #RELEASES_KEPT} releases, by the adapter's own ordering.
   *
   * <p>Groups are kept in enumeration order and compared by identity, so a group with fewer
   * releases than the belt simply keeps all of them — the honest answer for a package that has
   * published once.
   *
   * <p>Package-visible for one caller, {@code MavenPackagesGcAdapter}, whose closure is seeded from
   * this belt: asking here rather than re-deriving it is what keeps the adapter from carrying a
   * keep-count of its own, and keeps the seeds and the belt one answer rather than two that agree.
   */
  static Set<GcCandidate> lastReleasesPerGroup(
      List<GcCandidate> candidates, GcTypeAdapter adapter) {
    Map<String, List<GcCandidate>> releasesByGroup = new LinkedHashMap<>();
    for (GcCandidate candidate : candidates) {
      if (candidate.released()) {
        releasesByGroup.computeIfAbsent(candidate.group(), group -> new ArrayList<>()).add(candidate);
      }
    }
    Set<GcCandidate> keep = new HashSet<>();
    for (List<GcCandidate> releases : releasesByGroup.values()) {
      releases.sort(adapter.byAge());
      keep.addAll(releases.subList(Math.max(0, releases.size() - RELEASES_KEPT), releases.size()));
    }
    return keep;
  }

  private static void keep(
      GcCandidate candidate, String rule, List<GcIdentity> kept, Set<String> retained) {
    kept.add(new GcIdentity(candidate.repository(), candidate.identity(), rule));
    retained.addAll(candidate.blobs());
  }

  /**
   * A stable report order, so two runs over an unchanged store produce the same lists. It used to
   * live on the cache engine and be borrowed from here; the cache engine went to
   * qits-platform-mirror, so it lives here now.
   */
  static final Comparator<GcIdentity> BY_IDENTITY =
      Comparator.comparing(GcIdentity::repository).thenComparing(GcIdentity::identity);
}
