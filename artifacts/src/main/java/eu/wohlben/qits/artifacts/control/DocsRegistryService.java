package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.ArtifactMetadataHeaders;
import eu.wohlben.qits.blobstore.control.MetadataKeys;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.DocsFile;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.DocsSiteId;
import eu.wohlben.qits.artifacts.error.DocsException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The docs registry's database work, as complete units of work.
 *
 * <p>Every public method here is annotated, for the same load-bearing reason {@code
 * NpmRegistryService}'s, {@code MavenRegistryService}'s and {@code DaemonRegistryService}'s are: the
 * callers are raw Vert.x route handlers, which run with <b>no CDI request context and no
 * transaction</b>. Drop an annotation and the docs routes fail with {@code ContextNotActiveException}
 * at runtime only, with a green {@code mvn verify} behind them.
 *
 * <p>Bundle bytes stay deliberately outside all of it — every file is staged into {@code BlobStore}
 * by the route before {@link #publish} is called, so a ten-megabyte upload and fifty digest
 * computations cannot time a transaction out. What is left inside is one existence check and
 * fifty-odd inserts.
 *
 * <p><b>The row write IS the publish</b>, the property {@code DaemonRegistryService} states. There is
 * no path that stores a bundle's bytes without an identity — and, because {@link #publish} writes the
 * site and all of its files in <b>one</b> transaction, no path that stores half a site either. A
 * partially published version is not a state this type can be in.
 */
@ApplicationScoped
public class DocsRegistryService {

  @Inject ArtifactRepositoryRepository repositories;
  @Inject DocsSiteRepository sites;
  @Inject DocsFileRepository files;
  @Inject DocsAccessTracker accessTracker;

  /** One file of a bundle, as the route staged it: the whole of what {@link #publish} needs. */
  public record BundleFile(String path, String blobId, long sizeBytes, String mediaType) {}

  /** A published version, flattened for the serve path. {@code metadata} is never null. */
  public record StoredSite(
      String name,
      String version,
      int fileCount,
      long totalBytes,
      Instant publishedAt,
      Instant accessedAt,
      Map<String, String> metadata) {}

  /** One file of a published version, flattened for the serve path. */
  public record StoredFile(String path, String blobId, long sizeBytes, String mediaType) {}

  /**
   * Resolves the docs repository row.
   *
   * <p>Repositories are not created implicitly, exactly as on {@code /v2}, {@code /artifacts/npm},
   * {@code /artifacts/maven} and {@code /artifacts/daemons}: a missing or wrong-typed row is a 404
   * whose message names the ensure endpoint and the type to ask for. The seeded {@code docs} row
   * means a fresh deployment needs no manual step.
   */
  @ActivateRequestContext
  public String requireDocsRepository(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null || !DocsProfile.KEY.equals(repository.type)) {
      throw new DocsException(
          404,
          "no such docs repository '"
              + name
              + "'; create it with PUT /artifacts/api/repositories/"
              + name
              + " {\"type\":\"docs\"}");
    }
    return repository.type;
  }

  @ActivateRequestContext
  public Optional<StoredSite> find(String repository, String name, String version) {
    return sites.findOne(repository, name, version).map(DocsRegistryService::flatten);
  }

  @ActivateRequestContext
  public Optional<StoredFile> findFile(
      String repository, String name, String version, String path) {
    return files.findOne(repository, name, version, path).map(DocsRegistryService::flattenFile);
  }

  /** Every version of one site, newest first. */
  @ActivateRequestContext
  public List<StoredSite> listVersions(String repository, String name) {
    return sites.listVersions(repository, name).stream()
        .map(DocsRegistryService::flatten)
        .toList();
  }

  /**
   * One site's versions, newest first, narrowed to those whose metadata carries every {@code
   * predicates} entry exactly — {@code ArtifactQueryService}'s semantics on the docs plane. "The
   * latest bundle of branch X" is the first element of the filtered list, because the ordering is
   * the same {@code publishedAt desc} the unfiltered list has.
   *
   * <p>Filtered <b>in Java over one site's rows</b>, deliberately: the cardinality is one site's
   * version count, and a dynamic join per predicate buys nothing at that size — the same trade
   * {@code ArtifactQueryService} documented and chose. An indexed metadata join is the backlog
   * trigger, there as here.
   */
  @ActivateRequestContext
  public List<StoredSite> listVersions(
      String repository, String name, Map<String, String> predicates) {
    return sites.listVersions(repository, name).stream()
        .filter(
            row ->
                predicates.entrySet().stream()
                    .allMatch(p -> p.getValue().equals(row.metadata.get(p.getKey()))))
        .map(DocsRegistryService::flatten)
        .toList();
  }

  /** Every site in the repository, by name — the catalog qits-platform-docs lists. */
  @ActivateRequestContext
  public List<String> listNames(String repository) {
    return sites.listNames(repository);
  }

  /**
   * One version's paths, sorted. What the single-version document lists — and deliberately only
   * that document: the versions <em>list</em> stays path-free, because N versions × hundreds of
   * files is a payload the catalog page never reads.
   */
  @ActivateRequestContext
  public List<String> listFiles(String repository, String name, String version) {
    return files.listPaths(repository, name, version);
  }

  /**
   * One site in the catalog: what it is called, how many versions it has, and its newest.
   *
   * <p>{@code latestRepository} is the {@code git.repository.name} the <b>newest</b> version was
   * published with, and null when that version carries none (qits-2336). The newest version's and
   * no other's: a site that moved repositories is owned by where it is published from now, and an
   * older version naming another repository is history, not a second owner.
   */
  public record CatalogEntry(
      String name,
      int versionCount,
      String latestVersion,
      Instant latestPublishedAt,
      String latestRepository) {}

  /**
   * The whole catalog of one repository — every site with its version count and its newest version.
   *
   * <p><b>It carries no grouping.</b> A reader that wants scopes derives them from the names, which
   * is where a scope lives; deciding here that {@code @qits/ui-components} belongs under
   * {@code @qits} would put a presentation choice in the byte plane and make a second reader with a
   * different idea impossible. The store's job is to say what exists.
   */
  @ActivateRequestContext
  public List<CatalogEntry> listCatalog(String repository) {
    List<CatalogEntry> catalog = new ArrayList<>();
    // One select for the one metadata key the catalog shows, never row.metadata: see
    // DocsSiteRepository.metadataValues on why the collection's batch fetch would read every
    // version's whole map to answer a question about the newest one.
    Map<DocsSiteId, String> repositoryNames =
        sites.metadataValues(repository, MetadataKeys.GIT_REPOSITORY_NAME);
    String current = null;
    int count = 0;
    DocsSite newest = null;
    // The rows arrive by name then newest-first, so the first row of each run IS that site's
    // newest — no comparison needed, and the ordering is the query's single responsibility.
    for (DocsSite row : sites.listAllByRepository(repository)) {
      if (!row.name.equals(current)) {
        if (current != null) {
          catalog.add(catalogEntry(newest, count, repositoryNames));
        }
        current = row.name;
        count = 0;
        newest = row;
      }
      count++;
    }
    if (current != null) {
      catalog.add(catalogEntry(newest, count, repositoryNames));
    }
    return List.copyOf(catalog);
  }

  private static CatalogEntry catalogEntry(
      DocsSite newest, int count, Map<DocsSiteId, String> repositoryNames) {
    return new CatalogEntry(
        newest.name,
        count,
        newest.version,
        newest.publishedAt,
        repositoryNames.get(new DocsSiteId(newest.repository, newest.name, newest.version)));
  }

  /**
   * Records that a file of this version was served — the docs half of the access basis the settled
   * GC strategies read (artifacts-gc-plan.md, "Settlement").
   *
   * <p><b>Any file moves the site's row, and no row exists per file to move instead.</b> Opening a
   * workbench pulls the index, a dozen chunks and eight fonts in one go; spreading that across fifty
   * rows would answer a question nobody asks, and the version is what ages out, so the version is
   * what records being wanted. Coalesced to one write per row per hour inside {@link
   * ArtifactAccessTracker}, which is what keeps a single page load from being fifty updates.
   */
  @ActivateRequestContext
  public void touchSite(String repository, String name, String version) {
    accessTracker.touchDocsSite(
        repository, name, version, Instant.now().truncatedTo(ChronoUnit.MICROS));
  }

  /**
   * Writes one published version and all of its files — the whole of a publish, once the bytes are
   * promoted.
   *
   * <p>The immutability check lives here rather than in the route because it has to be inside the
   * same transaction as the inserts: checking outside it would make two concurrent publishes of the
   * same version a race that both sides win.
   *
   * <p>A re-publish is {@code 409} <b>even for identical bytes</b>, {@code DaemonRegistryService}'s
   * rule and its reasoning verbatim: a docs bundle is one request from one release pipeline, and a
   * second one at the same version means either the version was reused or the release ran twice.
   * That immutability is also what lets qits-docs be stateless — a version-addressed URL that never
   * changes meaning needs no alias table to protect it.
   *
   * <p>An empty bundle is {@code 400} rather than an empty site: a version with no files serves 404
   * for everything including its own index, so it is a failed build being published, not a
   * publishable thing.
   *
   * @throws DocsException {@code 409} when this (name, version) is already published, {@code 400}
   *     when the bundle carries no files
   */
  @ActivateRequestContext
  @Transactional
  public StoredSite publish(
      String repository,
      String name,
      String version,
      List<BundleFile> bundle,
      Map<String, String> metadata) {
    requireMetadataWithinCaps(metadata);
    if (bundle == null || bundle.isEmpty()) {
      throw new DocsException(
          400, "the bundle for " + name + "@" + version + " carries no files — nothing to publish");
    }
    Optional<DocsSite> existing = sites.findOne(repository, name, version);
    if (existing.isPresent()) {
      throw new DocsException(
          409,
          "version "
              + version
              + " of "
              + name
              + " is already published ("
              + existing.get().fileCount
              + " files); docs versions are immutable — publish a new version");
    }

    DocsSite site = new DocsSite();
    site.repository = repository;
    site.name = name;
    site.version = version;
    site.fileCount = bundle.size();
    site.totalBytes = bundle.stream().mapToLong(BundleFile::sizeBytes).sum();
    site.publishedAt = Instant.now();
    if (metadata != null) {
      site.metadata.putAll(metadata);
    }
    sites.persist(site);

    for (BundleFile entry : bundle) {
      DocsFile row = new DocsFile();
      row.repository = repository;
      row.name = name;
      row.version = version;
      row.path = entry.path();
      row.blobId = entry.blobId();
      row.sizeBytes = entry.sizeBytes();
      row.mediaType = entry.mediaType();
      files.persist(row);
    }
    return flatten(site);
  }

  /**
   * Deletes one published version — the whole of a docs collection, and the only way a {@code
   * docs_site} row ever leaves this service.
   *
   * <p><b>Package-private, reached only through {@link DocsRegistryCollection}</b> and called only by
   * the {@code gc} module's {@code DocsGcAdapter} — the same shape and the same reason as {@code
   * NpmRegistryService.collect}, {@code OciRegistryService.collectTag}, {@code
   * DaemonRegistryService.collect} and {@code BlobStore.delete}. There is no client-facing delete on
   * {@code /artifacts/docs} and this does not add one.
   *
   * <p><b>The file rows go with it, and nothing here deletes them.</b> The {@code on delete cascade}
   * in V12 does, which is the point of putting it there: the unit of eviction is the version, and a
   * database that removes the files makes "collect half a site" unreachable by any code path,
   * including a future one written by someone who has not read this. A site that still listed itself
   * while answering 404 for its assets is the failure this shape rules out.
   *
   * <p>The files' blobs are not touched. Blobs dedupe across every repository type and, here,
   * heavily across versions of the same site — so what may be unlinked is never one type's question;
   * the sweep answers it.
   *
   * @throws IllegalStateException no such row — the store moved since the plan was computed, and a
   *     plan that raced a publish must surface rather than delete by coordinates alone
   */
  @ActivateRequestContext
  @Transactional
  void collect(String repository, String name, String version) {
    DocsSite row =
        sites
            .findOne(repository, name, version)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no such docs site "
                            + name
                            + "@"
                            + version
                            + " to collect — the store moved since the plan was computed"));
    sites.delete(row);
  }

  private static StoredSite flatten(DocsSite row) {
    return new StoredSite(
        row.name,
        row.version,
        row.fileCount,
        row.totalBytes,
        row.publishedAt,
        row.accessedAt,
        Map.copyOf(row.metadata));
  }

  /**
   * The wire already refused an over-cap map through {@code ArtifactMetadataHeaders}; this is the
   * defensive restatement at the only write path, so the database column limit is never the
   * enforcer.
   */
  private static void requireMetadataWithinCaps(Map<String, String> metadata) {
    if (metadata == null) {
      return;
    }
    if (metadata.size() > ArtifactMetadataHeaders.MAX_KEYS) {
      throw new DocsException(
          400, "more than " + ArtifactMetadataHeaders.MAX_KEYS + " metadata keys");
    }
    metadata.forEach(
        (key, value) -> {
          if (key == null
              || key.isBlank()
              || key.length() > ArtifactMetadataHeaders.MAX_KEY_LENGTH
              || value == null
              || value.length() > ArtifactMetadataHeaders.MAX_VALUE_LENGTH) {
            throw new DocsException(400, "metadata key or value out of bounds: '" + key + "'");
          }
        });
  }

  private static StoredFile flattenFile(DocsFile row) {
    return new StoredFile(row.path, row.blobId, row.sizeBytes, row.mediaType);
  }
}
