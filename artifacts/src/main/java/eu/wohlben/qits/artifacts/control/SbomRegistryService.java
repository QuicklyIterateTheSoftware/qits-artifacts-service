package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.error.SbomException;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The SBOM store's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * DaemonRegistryService}'s and {@code DocsRegistryService}'s are: the callers are raw Vert.x route
 * handlers, which run with <b>no CDI request context and no transaction</b>. Drop an annotation and
 * the SBOM routes fail with {@code ContextNotActiveException} at runtime only, with a green {@code
 * mvn verify} behind them.
 *
 * <p>Document bytes stay deliberately outside all of it — they are staged into {@code BlobStore} by
 * the route before {@link #publish} is called.
 */
@ApplicationScoped
public class SbomRegistryService {

  /**
   * The four artifact types a {@code SoftwareRelease} can declare, verbatim as qits-ci's {@code
   * CiArtifact.Type} spells them on the wire. Validated here rather than in the path grammar so an
   * unknown type is a 400 naming the allowed set, not a 404 that reads as "no such route".
   */
  public static final Set<String> PACKAGE_TYPES = Set.of("npm", "maven", "docker", "daemon");

  @Inject ArtifactRepositoryRepository repositories;
  @Inject SbomDocumentRepository documents;
  @Inject SbomAccessTracker accessTracker;

  /** A stored document, flattened for the serve path. */
  public record StoredSbom(
      String packageType,
      String packageName,
      String version,
      String blobId,
      long sizeBytes,
      String specVersion,
      Instant createdAt) {}

  /** {@link #publish}'s answer: the row that stands, and whether this call created it. */
  public record Published(StoredSbom stored, boolean alreadyPublished) {}

  /**
   * Resolves the SBOM repository row.
   *
   * <p>Repositories are not created implicitly, exactly as on every other wire: a missing or
   * wrong-typed row is a 404 whose message names the ensure endpoint and the type to ask for. The
   * seeded {@code sboms} row means a fresh deployment needs no manual step.
   */
  @ActivateRequestContext
  public void requireSbomRepository(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null || !SbomProfile.KEY.equals(repository.type)) {
      throw new SbomException(
          404,
          "no such sbom repository '"
              + name
              + "'; create it with PUT /artifacts/api/repositories/"
              + name
              + " {\"type\":\"sboms\"}");
    }
  }

  @ActivateRequestContext
  public Optional<StoredSbom> find(
      String repository, String packageType, String packageName, String version) {
    return documents
        .findOne(repository, packageType, packageName, version)
        .map(SbomRegistryService::flatten);
  }

  @ActivateRequestContext
  public List<StoredSbom> listVersions(String repository, String packageType, String packageName) {
    return documents.listVersions(repository, packageType, packageName).stream()
        .map(SbomRegistryService::flatten)
        .toList();
  }

  /**
   * Records that a GET served this document — the access basis the GC window reads. What keeps a
   * release's document is its artifact still being stored ({@code SbomGcAdapter.pinnedBy}), not
   * this: maintenance reads a document once, at ingest.
   *
   * <p>Coalesced to one write per row per hour inside {@link SbomAccessTracker}.
   */
  @ActivateRequestContext
  public void touchDocument(
      String repository, String packageType, String packageName, String version) {
    accessTracker.touchDocument(
        repository, packageType, packageName, version, Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  /**
   * Writes one stored document — the whole of a publish, once the bytes are promoted.
   *
   * <p><b>First-write-wins, and that differs from the daemon 409 on purpose.</b> A daemon re-publish
   * at one version means a release ran twice, which is worth failing loudly — the SBOM PUT sits
   * <em>inside</em> that same release run, one step after the artifact publish, and release steps
   * are retried and replayed as a matter of course (a bootstrap replay, a re-fired flaked run). A
   * 409 here would turn every replay of a green release into a red one over a document that is
   * already exactly where it belongs. So the existing row stands, the re-sent body is discarded,
   * and the answer says {@code alreadyPublished} — the property that matters (the stored document
   * can never <em>change</em>) holds either way, and it is the property that keeps this surface
   * safe to leave tokenless.
   *
   * <p>The check lives inside the transaction so two concurrent publishes of one identity cannot
   * both insert; the loser of the race reads the winner's row and reports it as already published.
   */
  @ActivateRequestContext
  @Transactional
  public Published publish(
      String repository,
      String packageType,
      String packageName,
      String version,
      String blobId,
      long sizeBytes,
      String specVersion) {
    Optional<SbomDocument> existing =
        documents.findOne(repository, packageType, packageName, version);
    if (existing.isPresent()) {
      return new Published(flatten(existing.get()), true);
    }
    SbomDocument row = new SbomDocument();
    row.repository = repository;
    row.packageType = packageType;
    row.packageName = packageName;
    row.version = version;
    row.blobId = blobId;
    row.sizeBytes = sizeBytes;
    row.specVersion = specVersion;
    row.createdAt = Instant.now();
    documents.persist(row);
    return new Published(flatten(row), false);
  }

  /**
   * Deletes one stored document — the whole of an SBOM collection, and the only way an {@code
   * sbom_document} row ever leaves this service.
   *
   * <p><b>Package-private, reached only through {@link SbomRegistryCollection}</b> and called only
   * by the {@code gc} module's {@code SbomGcAdapter} — the same shape and the same reason as every
   * other collect funnel. There is no client-facing delete on {@code /artifacts/sboms} and this
   * does not add one.
   *
   * <p><b>No tombstone, deliberately.</b> A collected identity re-opens for a publish, and that is
   * the wanted behaviour: the next replay of that release (or a backfill run) restores the document,
   * where a tombstone would refuse it forever to protect a lockfile nothing here has.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed
   */
  @ActivateRequestContext
  @Transactional
  void collect(String repository, String packageType, String packageName, String version) {
    SbomDocument row =
        documents
            .findOne(repository, packageType, packageName, version)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no such sbom document "
                            + packageType
                            + "/"
                            + packageName
                            + "@"
                            + version
                            + " to collect — the store moved since the plan was computed"));
    documents.delete(row);
  }

  private static StoredSbom flatten(SbomDocument row) {
    return new StoredSbom(
        row.packageType,
        row.packageName,
        row.version,
        row.blobId,
        row.sizeBytes,
        row.specVersion,
        row.createdAt);
  }
}
