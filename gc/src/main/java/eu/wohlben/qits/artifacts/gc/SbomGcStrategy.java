package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Published software bills of materials, on the settled rule: <b>the last released documents of
 * every package stay, and the rest ages out after P90D unread.</b>
 *
 * <p>P90D rather than P30D, for the reason docs, maven and the daemon carry it: an SBOM is read on a
 * maintenance scan's cadence rather than on a build's, and a document of the last releases must
 * outlive a quiet quarter. The belt is what makes that safe to say — the two newest released
 * documents of a package stay whatever their age, so a package nobody scanned all quarter still
 * answers for the versions anyone is running.
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * one <b>document</b> is one identity, what a release is here, what keeps one, how a row goes —
 * are {@link SbomGcAdapter}'s.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class SbomGcStrategy extends OwnGcStrategy {

  @Inject SbomGcAdapter sboms;

  @Override
  GcTypeAdapter adapter() {
    return sboms;
  }
}
