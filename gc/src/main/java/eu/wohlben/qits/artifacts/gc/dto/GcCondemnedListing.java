package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * Everything the collector has condemned and not yet purged — the answer of {@code GET
 * /gc/condemned}.
 *
 * <p>This is the list to read when something might have gone missing tomorrow: every entry is still
 * served today, and every entry a person recognises as in use can be restored before its {@code
 * purgeAt}. A restore buys time, not retention — see {@link GcRestoreReceipt}.
 *
 * @param condemnGracePeriod how long a condemnation must stand before a sweep may purge, ISO-8601
 * @param condemned every standing condemnation, ordered by type, repository and identity
 */
public record GcCondemnedListing(String condemnGracePeriod, List<GcCondemnedIdentity> condemned) {}
