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
 * @param deleted the identities whose rows are gone now — <b>purged</b>, in the two-phase vocabulary
 *     of qits-1172: each was condemned by an earlier run at least {@code condemnGracePeriod} ago,
 *     and this run's keep-set still left it dead. Each still names the rule that condemned it. The
 *     name is kept rather than renamed, because a consumer reading {@code deleted} must go on
 *     reading what was deleted.
 * @param withheldByGraceWindow identities left whole because a blob they release is still inside
 *     the grace window. Deleting the row first would strand the blob — row-less blobs are
 *     untouchable by construction — so row and file wait out the window together and the next run
 *     takes both.
 * @param condemned dead identities this run condemned for the first time. Nothing of them is
 *     touched: rows, blobs and reads are as they were, and the earliest a later run may purge one is
 *     a full {@code condemnGracePeriod} from now.
 * @param cleared identities whose condemnation this run lifted, because its keep-set no longer
 *     leaves them dead — each naming the rule that saves it now (or that it no longer exists). This
 *     is the self-correction: a keep-set that was wrong yesterday and right today costs nothing.
 * @param awaitingPurge every dead identity this run did not purge because its condemnation is still
 *     younger than the grace — the ones condemned just now included — each with its {@code purgeAt}
 */
public record GcTypeSweepResult(
    String type,
    String strategy,
    String note,
    String error,
    List<GcIdentity> deleted,
    List<GcIdentity> withheldByGraceWindow,
    List<GcIdentity> condemned,
    List<GcIdentity> cleared,
    List<GcCondemnedIdentity> awaitingPurge) {

  /** A type line with no condemnation activity — the refused, unclaimed and aborted shapes. */
  public GcTypeSweepResult(
      String type,
      String strategy,
      String note,
      String error,
      List<GcIdentity> deleted,
      List<GcIdentity> withheldByGraceWindow) {
    this(type, strategy, note, error, deleted, withheldByGraceWindow, List.of(), List.of(), List.of());
  }
}
