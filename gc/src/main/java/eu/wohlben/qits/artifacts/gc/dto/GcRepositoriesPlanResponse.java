package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * Every repository's expected cleanup in <b>one</b> answer — what a listing reads to draw a column.
 *
 * <p>It exists because the alternative is a request per row. A plan costs one census (a walk of the
 * blob volume plus a pass over every protocol table) and <b>two cross-service HTTP calls</b>, so a
 * page that asked per repository would take N censuses and 2N pin fetches to draw one column, and
 * would break the standing "pins are read once per run" rule N times over in the process.
 *
 * <p>It is a <b>derivation</b> of the whole-store plan, not a second computation of it — the same
 * relationship {@code GcPlanSummary} has to the report it summarises. One run, one census, one pin
 * fetch, then the per-repository shares read off it. The identity lists and the blob digests are
 * left out here: the full plan at live scale carries every kept identity with its rule sentence and
 * every digest in full, which is the right amount of detail to review a run and the wrong amount to
 * draw a table.
 *
 * @param generatedAt when the census behind these figures was taken — the same instant the whole
 *     plan reports, because it is the same census
 * @param executable whether a sweep run now could execute anything at all. Run-wide rather than per
 *     row: pins are read once per run and a source that cannot answer aborts a run whole, so no
 *     repository is executable while another is not.
 * @param pinFailures one sentence per pin source that could not answer; empty when executable
 * @param graceWindow how long a blob's file must sit untouched before it may be unlinked, ISO-8601
 * @param repositories one entry per {@code artifact_repository} row, always all of them
 */
public record GcRepositoriesPlanResponse(
    Instant generatedAt,
    boolean executable,
    List<String> pinFailures,
    String graceWindow,
    List<GcRepositoryPlanSummary> repositories) {}
