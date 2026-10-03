package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own npm packages, live on the rule qits-740 settled on 2026-10-03: <b>a release
 * lives while a manifest pin, the closure of the kept set, a dist-tag or the newest-two belt reaches
 * it; everything else — a release included — goes at the configured window.</b>
 *
 * <p>This class used to carry npm's whole bespoke rule. The settlement of 2026-08-05 replaced "one
 * bespoke strategy per type" with "two engines, configured per type", so the <b>rule</b> is now
 * {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts — what a release
 * is, which of two is newer, what a dist-tag holds, what a kept version needs, how a row goes — are
 * {@link NpmPackagesGcAdapter}'s and {@link NpmKeepClosure}'s.
 *
 * <p><b>Releases were kept forever from 2026-09-05 to 2026-10-03</b>, after the zero-window sweep
 * took this registry down to three versions of {@code @qits/ui-components} and two of {@code
 * @qits/angular}, broke fifteen frontends' lockfiles and failed two services' release runs on
 * {@code npm ci}. The hole was that a frontend's lockfile is reached through a service's submodule
 * gitlink, which no pin source saw. qits-maintenance now follows that gitlink and reports the
 * lockfile as manifest pins (qits-740), and what a pinned version installs is kept with it by the
 * closure, so the belt is no longer the only thing between a needed tarball and a delete. {@link
 * NpmPackagesGcAdapter} carries the argument in full.
 *
 * <p><b>{@link #note()} says so on every report line</b>, because the own engine's configured
 * sentence — "always keep the last 2 released versions … delete the rest once unaccessed" — is what
 * {@code GcRules} echoes for every own type out of the configuration, and it cannot show the
 * closure, the dist-tag keep or the fail-closed rule. {@code maven-packages} carries the same
 * correction for the same reason.
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
   * echo beside it is the own engine's belt sentence and cannot show the closure.
   */
  static final String NOTE =
      "npm-packages collects a published version only when nothing kept still needs it (qits-740)."
          + " Kept, in order: a version a manifest on some repository's main pins — lockfiles reached"
          + " through a service's frontend submodule gitlink included; anything the closure of the"
          + " kept set reaches through dependencies, peerDependencies and optionalDependencies"
          + " ranges or a stored SBOM; anything a dist-tag names; and the newest 2 releases per"
          + " package. If the closure cannot be completed, nothing npm is collected that run. See"
          + " NpmPackagesGcAdapter for why releases were kept forever from 2026-09-05 until then.";

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
