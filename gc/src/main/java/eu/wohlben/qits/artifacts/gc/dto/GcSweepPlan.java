package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * The one figure that is actually disk: blobs that would lose their last reference across every
 * type, with every registered strategy's plan applied at once.
 *
 * <p>Not the sum of the per-type figures, and lower than it whenever two types release the same
 * content: a blob dies once. The per-type numbers answer "what does this strategy buy on its own";
 * this one answers "what would a sweep free tonight".
 *
 * @param blobCount how many files would be unlinked
 * @param reclaimableBytes what they occupy on disk — the real reclaim, before the grace window
 * @param withheldByGraceWindow blobs that qualify but whose file is younger than the grace window,
 *     or that belong to a dead identity a sweep withholds whole because its own row stamp or another
 *     of its blobs is (qits-1732). They are not lost, only not yet: the next run takes them. The window is what closes the race
 *     where a client's blob-exists probe answers "have it" for a blob about to be unlinked.
 * @param withheldBytes what those occupy
 * @param blobIds every digest that would be unlinked, in full and sorted. A sweep report exists to
 *     be checked against the store by hand before anything runs, and a count cannot be.
 */
public record GcSweepPlan(
    int blobCount,
    long reclaimableBytes,
    int withheldByGraceWindow,
    long withheldBytes,
    List<String> blobIds) {}
