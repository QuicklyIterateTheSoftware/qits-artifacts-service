package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * What one repository type's strategy actually deleted in a sweep, and what it declined to.
 *
 * @param type the repository type, in its wire spelling
 * @param strategy the strategy's class name, or null when none is registered
 * @param note the strategy's standing caption (the CI stubs name their intended rule here), or the
 *     "no strategy registered" line for an unclaimed type. Null when there is nothing to say.
 * @param error the strategy refused to plan (fail-closed — nothing of this type was touched), or
 *     one or more identities could not be applied, each named with its reason. Null when clean.
 * @param deleted the identities whose rows are gone now — found dead by this run's own plan and
 *     deleted on this run (qits-1732) — each still naming the rule that condemned it: the receipt
 *     half of the dry-run's {@code dead} list
 * @param withheldByGraceWindow identities left whole because their own row, or a blob they release,
 *     is still inside the grace window. Deleting the row first would strand a young blob — row-less
 *     blobs are untouchable by construction — and would take an identity pushed moments ago before
 *     any pin source could name it, so row and file wait out the window together and the next run
 *     takes both.
 */
public record GcTypeSweepResult(
    String type,
    String strategy,
    String note,
    String error,
    List<GcIdentity> deleted,
    List<GcIdentity> withheldByGraceWindow) {}
