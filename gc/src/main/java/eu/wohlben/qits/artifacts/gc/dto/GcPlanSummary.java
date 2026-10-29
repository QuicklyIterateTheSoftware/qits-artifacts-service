package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * The first thing a human reads: can this be executed, what would it cost, and what does each type
 * contribute.
 *
 * <p>Everything here is derived from the report below it and nothing is computed twice — the
 * summary is a reading of the plan, never a second opinion about it. It exists because the plan is
 * long and a review that has to add up eight types' figures before it can start is a review that
 * does not happen. The one figure that is disk is {@link #reclaimableBytes}, and it is the sweep's
 * cross-type number rather than the sum of the per-type ones: a blob dies once.
 *
 * @param executable whether a sweep run now could execute this plan — false when a pin source could
 *     not answer, in which case every figure below is what the plan <em>would</em> say, not what a
 *     run tonight would do
 * @param headline the whole plan in one sentence, including the reason it cannot run when it cannot
 * @param identitiesCondemned how many identity rows the types would delete, across all of them
 * @param blobsSweepable how many blob files would lose their last reference
 * @param reclaimableBytes what those files occupy on disk
 * @param reclaimable the same figure in units a human reads, so nobody counts digits
 * @param withheldByGraceWindow blobs that qualify but that a run now would leave alone: their file
 *     is younger than the grace window, or they belong to a dead identity a sweep withholds whole
 *     because its own row or another of its blobs is (qits-1732) — not lost, only not yet
 * @param types one line per repository type, in the enum's order: its configured engine and window,
 *     what it condemns, what that frees, and the type's own note when it carries one. A type nobody
 *     collects says so here rather than being left out.
 */
public record GcPlanSummary(
    boolean executable,
    String headline,
    int identitiesCondemned,
    int blobsSweepable,
    long reclaimableBytes,
    String reclaimable,
    int withheldByGraceWindow,
    List<String> types) {}
