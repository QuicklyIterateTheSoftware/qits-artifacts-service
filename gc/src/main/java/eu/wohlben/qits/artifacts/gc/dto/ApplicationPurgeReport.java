package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * What one {@code POST /applications/{application}/purge} removed (qits-1941).
 *
 * @param application the retired application, as the request named it
 * @param removed every package the purge took, ordered by type, repository row and name. Empty on
 *     a store holding nothing of the application's — which is also what a second call answers
 */
public record ApplicationPurgeReport(String application, List<PurgedPackage> removed) {}
