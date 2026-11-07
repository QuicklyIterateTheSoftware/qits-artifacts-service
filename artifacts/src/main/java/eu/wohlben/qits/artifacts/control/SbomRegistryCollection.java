package eu.wohlben.qits.artifacts.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The SBOM store's collection door, opened exactly wide enough for the {@code gc} module and no
 * wider — the fifth sibling of {@code BlobReclaim}, {@code OciRegistryCollection}, {@code
 * NpmRegistryCollection}, {@link DaemonRegistryCollection} and {@link DocsRegistryCollection}.
 *
 * <p><b>A narrow facade rather than a widened method.</b> {@code SbomRegistryService.collect} stays
 * package-private because it is the only way an {@code sbom_document} row ever leaves this service,
 * and there is no client-facing delete on {@code /artifacts/sboms} for it to become one.
 *
 * <p><b>The owners are {@code SbomGcAdapter.delete} and {@code ApplicationPurge}, both in the
 * {@code gc} module, and nothing else.</b> Which documents die is the own engine's rule, or — for
 * the second, since qits-1941 — the decision that an application is retired; this only knows how a
 * row is removed.
 */
@ApplicationScoped
public class SbomRegistryCollection {

  @Inject SbomRegistryService sboms;

  /**
   * Deletes one stored document. See {@code SbomRegistryService.collect}.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  public void collect(String repository, String packageType, String packageName, String version) {
    sboms.collect(repository, packageType, packageName, version);
  }
}
