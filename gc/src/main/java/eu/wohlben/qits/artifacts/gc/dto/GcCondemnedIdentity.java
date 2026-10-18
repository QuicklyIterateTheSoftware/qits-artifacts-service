package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;

/**
 * One identity a sweep found dead and condemned rather than deleted (qits-1172), and when the
 * collector will next be allowed to take it.
 *
 * <p>A condemned identity is <b>whole</b>: its rows stand, its blobs stay referenced, and every read
 * and download answers exactly as before. What the condemnation adds is a clock. A later sweep
 * purges it only once {@code purgeAt} has passed <em>and</em> that run's keep-set still leaves it
 * dead; a run whose keep-set names it again lifts the condemnation by itself, and {@code POST
 * /gc/condemned/{type}/{identity}/restore} lifts it by hand.
 *
 * @param type the repository type, in its wire spelling ({@code daemon-binaries})
 * @param repository the {@code artifact_repository} row the identity lives in
 * @param identity the type's own coordinate, spelled as the dry-run spells it
 * @param condemnedAt when the run that condemned it started
 * @param runId the receipt of that run — {@code runId} on {@code POST /gc/sweep}'s answer
 * @param purgeAt {@code condemnedAt} plus {@code qits.artifacts.gc.condemn-grace-period}: the first
 *     instant a sweep may purge it. Not a promise that one will — the next run past it still has to
 *     find the identity dead, and the blob grace still applies to its bytes.
 */
public record GcCondemnedIdentity(
    String type,
    String repository,
    String identity,
    Instant condemnedAt,
    String runId,
    Instant purgeAt) {}
