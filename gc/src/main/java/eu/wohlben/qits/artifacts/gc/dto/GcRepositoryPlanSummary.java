package eu.wohlben.qits.artifacts.gc.dto;


/**
 * What collecting <b>one repository</b> would do, in the figures a listing can draw.
 *
 * <p>One of these exists for every {@code artifact_repository} row on every run, including the rows
 * nobody collects and the rows whose type refused to plan. That is the plan report's
 * honesty-about-absence rule applied one level down: a repository missing from the list would read
 * as "nothing to clean here", and "nobody cleans this" is a different fact.
 *
 * <p><b>These figures do not add up, and the difference is not a rounding error.</b> Each is the
 * reconciliation over the whole store with <em>only this repository's</em> dead identities applied
 * and everything else — other repositories of the same type included — left standing. So a blob
 * condemned in two repositories at once counts in <b>neither</b> figure, while a global sweep would
 * free it: the sum of the column is a lower bound on what a whole-store run reclaims, never a
 * total. That is what "bytes only this repository's cleanup frees" means, and the alternative —
 * splitting a shared blob's bytes proportionally — would be the first number in this feature that
 * corresponds to no operation anyone can run.
 *
 * @param repository the {@code artifact_repository} row's name
 * @param type its type, in the wire spelling
 * @param strategy the class that would collect it, or null when no strategy claims its type
 * @param note why there is nothing to say — the excluded line, the npm-proxy H2 caption. Null when
 *     a rule ran and had something to report.
 * @param error the type refused to plan, with the reason: live pins unavailable, a policy
 *     collision, a strategy that could not establish its keep-set. Fail-closed, so the figures
 *     beside it are zeros that mean "refused", not "nothing to collect".
 * @param identitiesCondemned how many of <b>this repository's</b> identities the rule condemns
 * @param identitiesKept how many it keeps, each with its own reason on the detail report
 * @param blobsSweepable how many blobs would lose their last reference across the whole store when
 *     only this repository's dead identities are applied — the <b>structural</b> figure, with the
 *     grace window deliberately not applied, so a repository pushed this morning does not read as
 *     having nothing to clean
 * @param reclaimableBytes what those blobs occupy on disk
 * @param withheldByGraceWindow how many of them a run <em>now</em> would leave alone because their
 *     files are younger than the grace window, or because the dead identity naming them is withheld
 *     whole for its own young row or a young sibling blob (qits-1732). Not lost, only not yet.
 * @param withheldBytes what the withheld ones occupy
 */
public record GcRepositoryPlanSummary(
    String repository,
    String type,
    String strategy,
    String note,
    String error,
    int identitiesCondemned,
    int identitiesKept,
    int blobsSweepable,
    long reclaimableBytes,
    int withheldByGraceWindow,
    long withheldBytes) {}
