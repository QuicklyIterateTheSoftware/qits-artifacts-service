package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own npm packages, live on the rule the 2026-09-05 sweep settled: <b>every
 * published release stays, whatever its age; anything a dist-tag names stays; and prereleases — the
 * per-push {@code -main.g<sha>} builds — age out at the configured window.</b>
 *
 * <p>This class used to carry npm's whole bespoke rule. The settlement of 2026-08-05 replaced "one
 * bespoke strategy per type" with "two engines, configured per type", so the <b>rule</b> is now
 * {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts — what a release
 * is, which of two is newer, what a dist-tag holds, how a row goes — are {@link
 * NpmPackagesGcAdapter}'s.
 *
 * <p><b>Releases are kept forever again, and that is not the settlement being undone.</b> The
 * settlement replaced "kept forever" with a belt of two plus an access window, on the reading that
 * an older release something still installs is warm. On 2026-09-05 the windows went to {@code P0D}
 * and that reading went with them: with access deciding nothing, the belt was the whole of what
 * stood between a published tarball and a delete. The same evening's sweep took this registry down
 * to three versions of {@code @qits/ui-components} and two of {@code @qits/angular}, broke fifteen
 * frontends' lockfiles and failed two services' release runs on {@code npm ci}. What changed is the
 * input the belt was standing in for, so the rule follows it. A prerelease still dies on the window,
 * which is the half of the settlement that was always about build output. {@link
 * NpmPackagesGcAdapter} carries the argument in full.
 *
 * <p><b>{@link #note()} says so on every report line</b>, because the own engine's configured
 * sentence — "always keep the last 2 released versions … delete the rest once unaccessed" — is what
 * {@code GcRules} echoes for every own type out of the configuration, and for this one it now
 * describes a belt and a window that decide nothing. A reviewer must not be able to read that line
 * without reading this one. {@code maven-packages} carries the same correction for the same reason.
 *
 * <p>The newest-main-build rule and the unmodelled-prerelease backstop both retired into the access
 * window rather than being dropped: what {@code @main} resolves to was published minutes ago and is
 * young by construction, and an {@code -rc.1} somebody made by hand is kept for as long as anything
 * installs it. What is gone is "kept because nothing else claimed it", which was never a reason.
 *
 * <p><b>The tombstone stays, and it is npm's alone.</b> Version immutability is enforced by looking
 * for the row, so deleting one would re-open that version's name for a publish with different bytes
 * — one coordinate resolving to two tarballs over its lifetime. {@code
 * NpmRegistryCollection.collect} writes {@code npm_version_tombstone} in the <b>same transaction</b>
 * as the row deletion and refuses a version a dist-tag still names; both are the mechanism's
 * guarantees rather than this rule's, so no path around them exists to forget.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class NpmPackagesGcStrategy extends OwnGcStrategy {

  /**
   * What a report says about this type ahead of anything the run found, because the configuration
   * echo beside it describes a belt this type no longer runs.
   */
  static final String NOTE =
      "npm-packages does not age-collect published releases. Every release version is kept whatever"
          + " its age and whatever its position in the version order — the configured window governs"
          + " prereleases only (the per-push -main.g<sha> builds), and anything a dist-tag names is"
          + " kept on top of that. Withdrawn on 2026-09-05, after the zero-window sweep took this"
          + " registry down to three versions of @qits/ui-components, broke fifteen frontend"
          + " lockfiles and failed two services' release runs on npm ci; see NpmPackagesGcAdapter"
          + " for the argument.";

  @Inject NpmPackagesGcAdapter packages;

  @Override
  GcTypeAdapter adapter() {
    return packages;
  }

  @Override
  public String note() {
    return NOTE;
  }
}
