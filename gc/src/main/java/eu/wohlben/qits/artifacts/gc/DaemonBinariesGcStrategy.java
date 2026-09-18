package eu.wohlben.qits.artifacts.gc;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The platform's own daemon binaries, live on the settled rule: <b>the last two versions of every
 * daemon stay, both rungs of qits-ci's ladder stay, every binary a pinned coordinate carries stays,
 * and the rest ages out after P90D unaccessed.</b>
 *
 * <p><b>The third clause was owed from the day the window went to zero and was paid on
 * 2026-09-18.</b> At {@code P0D} retention IS the keep-set, and this type's keep-set knew about one
 * daemon: qits-ci's. Every other daemon binary on the platform was held up by a belt of two and by
 * nothing else, while its actual pins sat in poms — {@code qits-platform-access-cli} in qits-ci's
 * and in qits-workspace-oci's — where nothing here could see them. Two versions of cadence is not a
 * keep-set, and the rot showed up as release pipelines 404ing on a version somebody had deliberately
 * pinned. qits-platform-maintenance now derives those as {@code daemon} pins from the coordinate
 * that carries them, and {@link DaemonBinariesGcAdapter} honours them beside the ladder.
 *
 * <p>This type reported "no strategy registered for daemon-binaries" until now, and that was the
 * honest report of a decision waiting on a fact: the keep-set is partly qits-ci's answer, and a
 * strategy shipped ahead of {@code GET /ci/api/daemon} would have planned the one blob class a
 * running service <em>executes</em> against "nothing is pinned". The pin source landed, so the
 * report line changes from a decision's absence to a decision's outcome.
 *
 * <p>The rule is {@link OwnArtifactsStrategy}'s, the wiring {@link OwnGcStrategy}'s, and the facts —
 * one row is one identity, every row is a release, what qits-ci pins, how a row goes — are {@link
 * DaemonBinariesGcAdapter}'s.
 *
 * <p><b>The row-less legacy blobs are still untouchable and nothing here changes that.</b> A blob
 * becomes a candidate only by losing its last identity row, so bytes that never had one cannot be
 * reached by any sweep. The settlement's answer to them is an ops action, once, by hand — not an
 * allowlist here and not an adoption path in the service.
 *
 * <p>{@code @Singleton} rather than {@code @ApplicationScoped}, for the report's sake: a
 * normal-scoped bean answers {@code getClass().getSimpleName()} through its client proxy.
 */
@Singleton
public class DaemonBinariesGcStrategy extends OwnGcStrategy {

  @Inject DaemonBinariesGcAdapter daemons;

  @Override
  GcTypeAdapter adapter() {
    return daemons;
  }
}
