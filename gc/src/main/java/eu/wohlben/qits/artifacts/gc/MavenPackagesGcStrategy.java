package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own maven repository, on the rule of 2026-10-03 (qits-739): <b>a release lives
 * while a manifest pin, the SBOM-and-pom closure of the kept set, or the last-two belt reaches it;
 * the newest deployable set of every snapshot line stays; everything else goes at the configured
 * window — and if the closure cannot be completed, nothing maven goes at all that run.</b>
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * what a coordinate is, what a release is, which of two versions is newer, what a kept coordinate
 * needs to resolve, what a resolver would break without, how a row goes — are {@link
 * MavenPackagesGcAdapter}'s, with the walk itself in {@link MavenKeepClosure}.
 *
 * <p><b>The configuration echo is right again for this type, and {@link #note()} adds what it
 * cannot say.</b> From 2026-09-05 to 2026-10-03 the echo's "keep the last 2 … delete the rest" was
 * wrong here, because every release was kept; the note corrected it. Now the belt is real, and what
 * the echo cannot express is the closure in front of it and the fail-closed rule behind it, so the
 * note names both. {@code MavenPackagesGcAdapter}'s javadoc carries the argument in full, the
 * 2026-09-05 outage included.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class MavenPackagesGcStrategy extends OwnGcStrategy {

  /**
   * What a report says about this type ahead of anything the run found: the keep-classes the
   * configuration echo's belt sentence does not show.
   */
  static final String NOTE =
      "maven-packages keeps a release while something kept still needs it: a manifest pin on some"
          + " repository's main, the closure of the kept set (every hosted coordinate named by a"
          + " kept coordinate's SBOM, its parent pom or imported BOMs, and — for a coordinate with"
          + " no SBOM — its non-test pom dependencies), or the last 2 releases per artifact."
          + " Everything else, releases included, goes at the window; the newest deployable set of"
          + " every snapshot line is kept structurally. If the closure cannot be completed — a pom"
          + " or SBOM unreadable, a version unresolvable — nothing maven is collected that run, and"
          + " every line says why. See MavenPackagesGcAdapter.";

  @Inject MavenPackagesGcAdapter packages;

  @Override
  GcTypeAdapter adapter() {
    return packages;
  }

  @Override
  public String note() {
    return NOTE;
  }
}
