package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * What one executed sweep of <b>one repository</b> did — {@link GcRepositoryPlanReport} with the
 * tense changed.
 *
 * <p>Everything {@code GcSweepReport} says about a whole-store receipt holds here word for word,
 * including the one that reads as a failure and is not: a sweep of a repository whose content is
 * younger than the grace window is expected to delete nothing and say so in every figure. That
 * no-op is the proof the mechanism is safe.
 *
 * <p><b>Scope narrows what is planned, never what is protected.</b> The identity rows deleted are
 * this repository's; the blob unlinks that follow are reconciled over the <em>whole</em> store, so
 * a blob any other repository still names is never touched — the scoped plan retains it, the
 * pre-unlink re-census sees the surviving row, and the store's own guard re-checks inside the write
 * lock. Three belts, none of which knows what a repository is.
 *
 * @param repository the {@code artifact_repository} row this run was about
 * @param type its type, in the wire spelling
 * @param executedAt when this run started
 * @param dryRun always false — the field {@link GcRepositoryPlanReport} carries as always-true, in
 *     both so a client can never mistake a plan for a receipt
 * @param graceWindow how long a blob's file must have sat untouched before it may be unlinked,
 *     ISO-8601. It gates identity deletion too: a row deleted over an in-grace blob would strand
 *     the blob as row-less and therefore untouchable.
 * @param aborted why this run deleted nothing at all, or null when it ran. A pin source that could
 *     not answer ends the run before the census — and the rule is <b>all-or-nothing at repository
 *     scope too</b>, because blobs dedupe globally: bytes this repository releases can be the last
 *     local reference to content a pin names by digest.
 * @param pins how this run read its live pins. An aborted receipt still carries it, because the
 *     failed source is the whole story of that receipt.
 * @param strategy the class that collected this repository's type, or null when none claims it
 * @param note the standing caption for this type — the excluded line, the npm-proxy H2 caption
 * @param error the type refused, or identities could not be applied, each with its reason. A
 *     repository nobody collects is <b>not</b> an error status: the receipt says so and reports
 *     zeros, which is the same "report rather than throw" posture the whole-store surface holds.
 * @param deleted this repository's identities whose rows are gone now, each still naming the rule
 *     that condemned it
 * @param withheldByGraceWindow identities left whole because their own row, or a blob they release,
 *     is still inside the window. Not lost: the next run past it takes rows and files together.
 * @param sweep the blob unlinks — what was freed, and every candidate that was not
 * @param untouchable the row-less pool as it stood before this run, restated
 * @param runId this run's id, unique per receipt
 */
public record GcRepositorySweepReport(
    String repository,
    String type,
    Instant executedAt,
    boolean dryRun,
    String graceWindow,
    String aborted,
    List<GcPinSource> pins,
    String strategy,
    String note,
    String error,
    List<GcIdentity> deleted,
    List<GcIdentity> withheldByGraceWindow,
    GcSweepOutcome sweep,
    GcUntouchablePool untouchable,
    String runId) {}
