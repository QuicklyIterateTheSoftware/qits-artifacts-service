package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * The blob half of a sweep receipt: files actually unlinked, and every candidate that was not.
 *
 * @param blobsUnlinked how many files were unlinked
 * @param bytesReclaimed the data size of the blobs unlinked — what they held, NOT disk returned. A
 *     delete leaves its rows' space inside the chunk table's files, where PostgreSQL reuses it but
 *     never gives it to the filesystem, so the volume sees nothing of this figure until compaction
 *     drops a segment; the receipt's {@code disk} section is what came back (qits-2248)
 * @param withheldByGraceWindow candidates whose file is younger than the grace window, or whose
 *     identity was withheld whole for its own young row or a young sibling blob (qits-1732). They
 *     are not lost, only not yet: their identity rows were withheld with them, and the next run past
 *     the window takes rows and files together.
 * @param withheldBytes the data size of the withheld candidates
 * @param stillReferenced candidates something still named at unlink time — a withheld identity of
 *     another type, or a store that moved between plan and unlink. Refused, which is the mechanism
 *     working.
 * @param alreadyGone candidates with no file left to unlink
 * @param unlinkedBlobIds every digest that was unlinked, in full and sorted — a receipt exists to
 *     be checked against the store by hand, and a count cannot be
 */
public record GcSweepOutcome(
    int blobsUnlinked,
    long bytesReclaimed,
    int withheldByGraceWindow,
    long withheldBytes,
    int stillReferenced,
    int alreadyGone,
    List<String> unlinkedBlobIds) {}
