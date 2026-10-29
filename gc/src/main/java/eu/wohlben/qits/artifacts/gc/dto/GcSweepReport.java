package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * What one executed sweep actually did — the plan report's twin, with the tense changed.
 *
 * <p>Where {@code GcPlanReport} says what a run <em>would</em> do, this is the receipt of one that
 * ran: identity rows deleted per type, blob files unlinked, and — just as load-bearing — what was
 * withheld and why. A sweep against a young store is expected to delete nothing and say so in every
 * figure; that no-op is proof the executor is safe, not a failure to look into.
 *
 * @param executedAt when this run started
 * @param dryRun always false — the field {@code GcPlanReport} carries as always-true, kept in both
 *     so a client can never mistake a plan for a receipt or a receipt for a plan
 * @param graceWindow how long a blob's file must have sat untouched before it may be unlinked,
 *     ISO-8601 — the window also gates identity deletion, see {@code GcTypeSweepResult}
 * @param aborted why this run deleted nothing at all, or null when it ran. A pin source that could
 *     not answer — qits-platform-deployments's deployments, qits-ci's daemon ladder — ends the <b>whole</b> run before
 *     the census, because a keep-set assembled without a live pin is a keep-set assembled from
 *     "nothing is pinned"
 * @param pins how this run read its live pins — the same section the dry-run carries, and on a
 *     receipt it is the record of what the keep-set was built from at the moment rows were deleted.
 *     An aborted run still carries it, because the failed source is the whole story of that receipt.
 * @param types one entry per repository type, always all of them, same honesty rule as the plan
 * @param sweep the blob unlinks: what was freed, and what was withheld
 * @param untouchable the row-less pool as it stood before this run — restated on every receipt
 *     because it is the list a reviewer checks the ci-daemon binary against
 * @param runId this run's id, unique per receipt, so a log line, a receipt and whoever quotes it can
 *     be matched up. Present on an aborted receipt too, which deleted nothing.
 */
public record GcSweepReport(
    Instant executedAt,
    boolean dryRun,
    String graceWindow,
    String aborted,
    List<GcPinSource> pins,
    List<GcTypeSweepResult> types,
    GcSweepOutcome sweep,
    GcUntouchablePool untouchable,
    String runId) {}
