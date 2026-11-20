package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.dto.DaemonSummary;
import eu.wohlben.qits.artifacts.dto.DaemonVersionSummary;
import eu.wohlben.qits.artifacts.dto.DocsSiteSummary;
import eu.wohlben.qits.artifacts.dto.DocsVersionSummary;
import eu.wohlben.qits.artifacts.dto.ImageSummary;
import eu.wohlben.qits.artifacts.dto.ImageManifestSummary;
import eu.wohlben.qits.artifacts.dto.ImageTagSummary;
import eu.wohlben.qits.artifacts.dto.PackageSummary;
import eu.wohlben.qits.artifacts.dto.PackageVersionSummary;
import eu.wohlben.qits.artifacts.dto.MavenPackageSummary;
import eu.wohlben.qits.artifacts.dto.MavenVersionSummary;
import eu.wohlben.qits.artifacts.dto.RepositorySummary;
import eu.wohlben.qits.artifacts.dto.StoreSummary;
import eu.wohlben.qits.blobstore.control.ArtifactListFilter;
import eu.wohlben.qits.blobstore.control.BlobDiskIndex;
import eu.wohlben.qits.blobstore.control.CiScreenshotsProfile;
import eu.wohlben.qits.blobstore.control.CiVideosProfile;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.error.NotFoundException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The read side of the store: what is in which repository, and what it costs.
 *
 * <p>Everything here is an enumeration the protocol routes never had to do. {@code /v2/_catalog} is
 * refused by design, npm's {@code /-/all} is absent, and the four JSON operations this service
 * shipped with can ensure a repository, list the five of them, and move bytes. Nothing could say
 * what was inside one.
 *
 * <p>The enumerations are cheap — every one of them rides an index that already exists. The sizes
 * are the only new machinery, and their whole discipline is in {@link OciManifestFootprints} and
 * {@link BlobDiskIndex}: <b>a size is a union, never a sum</b>, because the blob store dedupes
 * globally and adding two overlapping figures on this store overstates it by 2.6×.
 *
 * <p>Read-only, and unguarded like its neighbours: {@code ArtifactsTokenFilter} covers write methods
 * only, and {@code /artifacts/api} is already on the gateway's public paths.
 */
@ApplicationScoped
public class ArtifactExplorerService {

  private static final String LATEST = "latest";

  /**
   * The digest prefix, spelled here rather than borrowed from {@code OciDigest}.
   *
   * <p>{@code DaemonRoutes} writes this literal into its {@code Docker-Content-Digest} header and
   * into its publish receipt, and the daemon plane is not OCI — routing a daemon's digest through
   * the registry's helper would make one type's wire spelling depend on another's. The two are the
   * same four characters and must stay so; a daemon browse listing an operator pins from has to
   * match the header the download echoed.
   */
  private static final String SHA256 = "sha256:";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject ArtifactRecordRepository records;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject DaemonBinaryRepository daemonBinaries;
  @Inject DocsSiteRepository docsSites;
  @Inject DocsFileRepository docsFiles;
  @Inject SbomDocumentRepository sbomDocuments;
  @Inject OciManifestFootprints footprints;
  @Inject BlobDiskIndex diskIndex;
  @Inject LiveBlobCensus census;
  @Inject RepositoryTypeProfiles repositoryTypes;

  /**
   * Every repository this service serves, with the one count and the one size its type can answer.
   *
   * <p><b>A row whose type no profile on this classpath claims is not listed.</b> The migration
   * chain is carried through the byte-plane split unchanged, so a fresh database still prefills the
   * three OCI mirror namespaces V7 created — rows that belong to qits-platform-mirror now. Listing
   * one would mean answering "how many things are in it" about a type this service cannot read, and
   * every honest answer to that is a refusal. Omitting them says the true thing: this service serves
   * the hosted plane, and those rows are somebody else's.
   */
  public List<RepositorySummary> listRepositories() {
    List<RepositorySummary> summaries = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (repositoryTypes.find(repository.type).isEmpty()) {
        continue;
      }
      summaries.add(
          new RepositorySummary(
              repository.name,
              wireName(repository),
              repository.createdAt,
              itemCount(repository),
              sizeOf(repository)));
    }
    return summaries;
  }

  /**
   * The images of a hosted OCI repository.
   *
   * <p>Mirror namespaces are not browsable here any more — they are qits-platform-mirror's, with its
   * own explorer. The query is unchanged: {@code oci_manifest} was always one table over both types.
   *
   * @throws NotFoundException there is no such repository
   * @throws BadRequestException it is not an image repository
   */
  public List<ImageSummary> listImages(String repository) {
    requireOci(repository);
    List<ImageSummary> images = new ArrayList<>();
    for (String image : manifests.listImageNames(repository)) {
      images.add(
          new ImageSummary(
              image,
              tags.countByImage(repository, image),
              manifests.listByImage(repository, image).size(),
              OciManifestFootprints.sum(
                  footprints.union(manifests.listByImage(repository, image)))));
    }
    return images;
  }

  /**
   * The tags of one image, lexically.
   *
   * <p>An unknown image is an empty list rather than a 404, which is the same answer {@code
   * /v2/<name>/tags/list} gives and for the same reason: an image is not a row, so there is nothing
   * to be absent. Only the repository can be unknown.
   */
  public List<ImageTagSummary> listTags(String repository, String image) {
    return listTags(repository, image, ArtifactListFilter.NONE);
  }

  public List<ImageTagSummary> listTags(
      String repository, String image, ArtifactListFilter filter) {
    requireOci(repository);
    Map<String, OciManifest> byDigest = new HashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }
    List<ImageTagSummary> summaries = new ArrayList<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      OciManifest manifest = byDigest.get(tag.manifestDigest);
      long size =
          manifest == null ? 0L : OciManifestFootprints.sum(footprints.of(manifest));
      if (filter.matches(size, tag.updatedAt, tag.accessedAt)) {
        summaries.add(
            new ImageTagSummary(
                tag.tag, OciDigest.wire(tag.manifestDigest), size, tag.updatedAt, tag.accessedAt));
      }
    }
    return summaries;
  }

  /** Every manifest, including untagged manifests that a digest pull can still access. */
  public List<ImageManifestSummary> listManifests(
      String repository, String image, ArtifactListFilter filter) {
    requireOci(repository);
    Map<String, List<String>> tagsByDigest = new HashMap<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      tagsByDigest.computeIfAbsent(tag.manifestDigest, ignored -> new ArrayList<>()).add(tag.tag);
    }
    List<ImageManifestSummary> summaries = new ArrayList<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      long size = OciManifestFootprints.sum(footprints.of(manifest));
      if (filter.matches(size, manifest.createdAt, manifest.accessedAt)) {
        summaries.add(
            new ImageManifestSummary(
                OciDigest.wire(manifest.digest),
                manifest.mediaType,
                size,
                manifest.createdAt,
                manifest.accessedAt,
                List.copyOf(tagsByDigest.getOrDefault(manifest.digest, List.of()))));
      }
    }
    return summaries;
  }

  /**
   * The packages of a hosted npm repository.
   *
   * <p>Cache namespaces are qits-platform-mirror's now. See {@code
   * NpmVersionRepository.listPackageNames} for why the packument table is not the source.
   */
  public List<PackageSummary> listPackages(String repository) {
    requireNpm(repository);
    List<PackageSummary> packages = new ArrayList<>();
    for (String name : versions.listPackageNames(repository)) {
      packages.add(
          new PackageSummary(
              name,
              versions.countVersions(repository, name),
              distTags
                  .findOne(repository, name, LATEST)
                  .map(tag -> tag.version)
                  .orElse(null)));
    }
    return packages;
  }

  /** Every version of one package, with the dist-tags naming it. */
  public List<PackageVersionSummary> listVersions(String repository, String packageName) {
    requireNpm(repository);
    Map<String, List<String>> tagsByVersion = new HashMap<>();
    for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
      tagsByVersion.computeIfAbsent(tag.version, version -> new ArrayList<>()).add(tag.tag);
    }
    Map<String, Long> onDisk = diskIndex.sizes();
    List<PackageVersionSummary> summaries = new ArrayList<>();
    for (Object[] row : versions.listVersionRows(repository, packageName)) {
      String version = (String) row[0];
      summaries.add(
          new PackageVersionSummary(
              version,
              onDisk.get((String) row[1]),
              (Instant) row[2],
              (Instant) row[3],
              tagsByVersion.getOrDefault(version, List.of())));
    }
    return summaries;
  }

  /** Maven's layout normalized into coordinate rows rather than exposing an opaque path list. */
  public List<MavenPackageSummary> listMavenPackages(String repository) {
    requireMaven(repository);
    Map<String, Set<String>> versionsByPackage = new TreeMap<>();
    Map<String, Long> sizes = new TreeMap<>();
    for (MavenArtifact artifact : mavenArtifacts.list("repository", repository)) {
      MavenPath path = MavenPath.parse(artifact.path);
      if (path == null) continue;
      versionsByPackage.computeIfAbsent(path.coordinate(), ignored -> new HashSet<>()).add(path.version());
      sizes.merge(path.coordinate(), artifact.sizeBytes, Long::sum);
    }
    return versionsByPackage.entrySet().stream()
        .map(entry -> new MavenPackageSummary(entry.getKey(), entry.getValue().size(), sizes.get(entry.getKey())))
        .toList();
  }

  /** Every published version of one Maven coordinate, including its jar, pom and sidecars. */
  public List<MavenVersionSummary> listMavenVersions(String repository, String coordinate) {
    requireMaven(repository);
    Map<String, MavenVersionBuilder> byVersion = new TreeMap<>();
    for (MavenArtifact artifact : mavenArtifacts.list("repository", repository)) {
      MavenPath path = MavenPath.parse(artifact.path);
      if (path == null || !path.coordinate().equals(coordinate)) continue;
      byVersion.computeIfAbsent(path.version(), MavenVersionBuilder::new).add(path.file(), artifact);
    }
    return byVersion.values().stream().map(MavenVersionBuilder::build).toList();
  }

  /**
   * The daemons of the {@code daemon-binaries} repository, by name.
   *
   * <p>There is no daemon table, so this is a distinct scan over {@code daemon_binary} — the shape
   * {@link #listImages} has over {@code oci_manifest}. The wire has no repository segment (the
   * seeded {@code daemons} row is the only namespace, {@code DaemonPaths}' one departure from npm's
   * grammar), but the listing hangs off a repository like every other browse surface here: the
   * explorer's subject is an {@code artifact_repository} row throughout, and inventing a second
   * addressing scheme for the one type whose wire happens to elide it would make the SPA's
   * repository drill-down a special case.
   *
   * <p>The size per daemon is the union over its own distinct blobs, so Σ(per daemon) may exceed
   * {@link #sizeOf}'s figure for the repository — two daemons built from identical bytes share one
   * blob. That is the same honest over-count a per-image sum has against the store union.
   *
   * @throws NotFoundException there is no such repository
   * @throws BadRequestException it is not a daemon repository
   */
  public List<DaemonSummary> listDaemons(String repository) {
    requireDaemons(repository);
    List<DaemonSummary> daemons = new ArrayList<>();
    for (String name : daemonBinaries.listNames(repository)) {
      // Newest first, so the head row IS the latest — the ordering is the query's responsibility,
      // and a daemon with no versions cannot be enumerated because the name came from a row.
      List<DaemonBinary> published = daemonBinaries.listVersions(repository, name);
      DaemonBinary newest = published.getFirst();
      daemons.add(
          new DaemonSummary(
              name,
              published.size(),
              newest.version,
              newest.publishedAt,
              daemonBytes(repository, name)));
    }
    return daemons;
  }

  /**
   * Every published version of one daemon, newest first.
   *
   * <p>The order is {@code published_at desc}, matching {@code DaemonRegistryService.listVersions} —
   * the wire listing and this one must agree, or an operator comparing the two reads a release
   * history that changed shape depending on which URL asked for it.
   *
   * <p>An unknown daemon is an empty list rather than a 404, {@link #listTags}' stance: a daemon is
   * not a row, so there is nothing to be absent. Only the repository can be unknown.
   */
  public List<DaemonVersionSummary> listDaemonVersions(String repository, String name) {
    requireDaemons(repository);
    List<DaemonVersionSummary> versions = new ArrayList<>();
    for (DaemonBinary row : daemonBinaries.listVersions(repository, name)) {
      versions.add(
          new DaemonVersionSummary(
              row.version,
              SHA256 + row.blobId,
              row.sizeBytes,
              row.publishedAt,
              row.accessedAt));
    }
    return versions;
  }

  /**
   * The documentation sites of a {@code docs} repository, by name.
   *
   * <p><b>Deliberately not a delegation to {@code DocsRegistryService.listCatalog}</b>, although the
   * fold is the same one: the wire catalog answers what exists and this answers what it costs, and
   * the catalog's omission of a size is a decision rather than a gap — qits-platform-docs renders it
   * on an open, tokenless route and a byte figure there would be an inventory of the store to
   * anybody who can reach it. Sharing the record would make the size either present on both or
   * absent from both. What <em>is</em> shared is the reasoning: the rows arrive by name then
   * newest-first, so the first row of each run is that site's newest and no comparison is needed.
   *
   * <p><b>{@code DocsSite.metadata} is not touched here, and must not be.</b> It is {@code LAZY} for
   * exactly this reader — one repository's every version — so initialising it would be one extra
   * query per row, which the {@code @BatchSize(50)} mitigates rather than removes. The version
   * listing below is where metadata is read, over one site's handful of rows.
   *
   * @throws NotFoundException there is no such repository
   * @throws BadRequestException it is not a docs repository
   */
  public List<DocsSiteSummary> listDocsSites(String repository) {
    requireDocs(repository);
    List<DocsSiteSummary> sites = new ArrayList<>();
    String current = null;
    long count = 0;
    DocsSite newest = null;
    for (DocsSite row : docsSites.listAllByRepository(repository)) {
      if (!row.name.equals(current)) {
        if (current != null) {
          sites.add(siteSummary(repository, current, count, newest));
        }
        current = row.name;
        count = 0;
        newest = row;
      }
      count++;
    }
    if (current != null) {
      sites.add(siteSummary(repository, current, count, newest));
    }
    return sites;
  }

  /**
   * Every published version of one site, newest first, with the metadata its publisher rode in on.
   *
   * <p>This is the reader {@code DocsSite.metadata}'s laziness was written for: one site's versions,
   * initialised inside the request context and batched fifty at a time, which is why touching it
   * here is right and touching it in {@link #listDocsSites} would not be.
   *
   * <p>The order is {@code published_at desc}, matching {@code DocsRegistryService.listVersions} and
   * the daemon listing above — the wire and the explorer must not disagree about a release history.
   *
   * <p>An unknown site is an empty list rather than a 404, for the reason a version <em>is</em> a
   * row and a site is not: only the repository can be absent.
   */
  public List<DocsVersionSummary> listDocsVersions(String repository, String name) {
    requireDocs(repository);
    Map<String, Map<String, Long>> blobsByVersion = new HashMap<>();
    for (Object[] row : docsFiles.listDistinctBlobsByVersion(repository, name)) {
      blobsByVersion
          .computeIfAbsent((String) row[0], ignored -> new TreeMap<>())
          .putIfAbsent((String) row[1], (Long) row[2]);
    }
    List<DocsVersionSummary> versions = new ArrayList<>();
    for (DocsSite row : docsSites.listVersions(repository, name)) {
      versions.add(
          new DocsVersionSummary(
              row.version,
              row.fileCount,
              // The version's own union, NOT row.totalBytes — that column is the sum over the
              // bundle as published and double-counts bytes shipped at two paths.
              OciManifestFootprints.sum(blobsByVersion.getOrDefault(row.version, Map.of())),
              row.publishedAt,
              row.accessedAt,
              // Sorted by key, as the docs wire answers it: Map.copyOf iterates in an order salted
              // per JVM, which would reorder the JSON from one boot to the next (qits-2328).
              Collections.unmodifiableMap(new TreeMap<>(row.metadata))));
    }
    return versions;
  }

  /** One run of the {@link #listDocsSites} fold, priced. */
  private DocsSiteSummary siteSummary(
      String repository, String name, long versionCount, DocsSite newest) {
    return new DocsSiteSummary(
        name, versionCount, newest.version, newest.publishedAt, docsBytes(repository, name));
  }

  /**
   * The figures that do not reconcile, and the gaps between them.
   *
   * <p>One disk walk and one pass over the manifests answers all of it, and that pass is {@link
   * LiveBlobCensus} — the same reading garbage collection plans from. Deliberately not a second
   * computation of the same thing: the set this panel calls live and the set a sweep protects have
   * to be one set, or the day they drift is the day a sweep deletes something this page called
   * referenced.
   */
  public StoreSummary storeSummary() {
    LiveBlobCensus.Census taken = census.take();
    // The four cache figures are STRUCTURALLY zero here, not merely empty: this service registers
    // no cache type, so no repository row can carry one and the census has nothing to attribute.
    // They keep their places in the record rather than being removed, because the panel's whole
    // argument is that every way of counting is named — and "nothing cached" is an answer a reader
    // needs, whereas a missing row reads as a figure someone forgot. qits-platform-mirror reports
    // its own.
    return new StoreSummary(
        taken.ociPerImageSumBytes(),
        taken.liveBytes(OciImagesProfile.KEY),
        0L,
        taken.rowlessBytes(),
        taken.liveBytes(NpmPackagesProfile.KEY),
        0L,
        0L,
        taken.liveBytes(MavenPackagesProfile.KEY),
        0L,
        taken.liveBytes(DaemonBinariesProfile.KEY),
        taken.liveBytes(DocsProfile.KEY),
        taken.liveBytes(SbomProfile.KEY),
        taken.diskTotalBytes());
  }

  /**
   * Resolves a repository, or says which of the two ways it is wrong.
   *
   * <p>The split matters to a client: 404 means "no such name", 400 means "that name exists and is
   * not this kind of thing" — an npm repository has no images and never will, and reporting that as
   * an empty list would look like an image registry that lost its images.
   */
  private ArtifactRepository requireOci(String name) {
    ArtifactRepository repository = require(name);
    if (!OciImagesProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "Repository '" + name + "' is " + wireName(repository) + ", not oci-images");
    }
    return repository;
  }

  private ArtifactRepository requireNpm(String name) {
    ArtifactRepository repository = require(name);
    if (!NpmPackagesProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "Repository '" + name + "' is " + wireName(repository) + ", not npm-packages");
    }
    return repository;
  }

  private ArtifactRepository requireMaven(String name) {
    ArtifactRepository repository = require(name);
    if (!MavenPackagesProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "Repository '" + name + "' is " + wireName(repository) + ", not maven-packages");
    }
    return repository;
  }

  private ArtifactRepository requireDaemons(String name) {
    ArtifactRepository repository = require(name);
    if (!DaemonBinariesProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "Repository '" + name + "' is " + wireName(repository) + ", not daemon-binaries");
    }
    return repository;
  }

  private ArtifactRepository requireDocs(String name) {
    ArtifactRepository repository = require(name);
    if (!DocsProfile.KEY.equals(repository.type)) {
      throw new BadRequestException(
          "Repository '" + name + "' is " + wireName(repository) + ", not docs");
    }
    return repository;
  }

  private record MavenPath(String coordinate, String version, String file) {
    static MavenPath parse(String path) {
      String[] parts = path.split("/");
      if (parts.length < 4 || path.endsWith("maven-metadata.xml")) return null;
      String artifact = parts[parts.length - 3];
      String group = String.join(".", java.util.Arrays.copyOf(parts, parts.length - 3));
      return new MavenPath(group + ":" + artifact, parts[parts.length - 2], parts[parts.length - 1]);
    }
  }

  private static final class MavenVersionBuilder {
    private final String version;
    private final List<String> files = new ArrayList<>();
    private long size;
    private Instant publishedAt;
    MavenVersionBuilder(String version) { this.version = version; }
    void add(String file, MavenArtifact artifact) {
      files.add(file); size += artifact.sizeBytes;
      if (publishedAt == null || artifact.createdAt.isBefore(publishedAt)) publishedAt = artifact.createdAt;
    }
    MavenVersionSummary build() { return new MavenVersionSummary(version, files, size, publishedAt); }
  }

  /** A repository's type as the API spells it — the kebab form, never the stored key. */
  private static String wireName(ArtifactRepository repository) {
    return RepositoryTypeProfile.wireNameOf(repository.type);
  }

  private ArtifactRepository require(String name) {
    ArtifactRepository repository = name == null ? null : repositories.findById(name);
    if (repository == null) {
      throw new NotFoundException("No such artifacts repository: " + name);
    }
    return repository;
  }

  /**
   * The one count this repository's type can answer.
   *
   * <p>A switch over the STORED type key, and it needs a default arm now that the type set is open:
   * a row can carry a key no profile on this classpath claims (a cache row left behind by the split,
   * say). Reporting a count of zero for one would read as an empty repository, so it refuses instead
   * — the same stance {@code RepositoryTypeProfiles.require} takes.
   */
  private long itemCount(ArtifactRepository repository) {
    return switch (repository.type) {
      case OciImagesProfile.KEY -> manifests.countImages(repository.name);
      case NpmPackagesProfile.KEY -> versions.countPackages(repository.name);
      // Deployed files — one number with a type-dependent meaning, the standing convention.
      case MavenPackagesProfile.KEY -> mavenArtifacts.countByRepository(repository.name);
      // Published versions across every daemon this repository holds — one number, the same
      // type-dependent meaning the line above has.
      case DaemonBinariesProfile.KEY -> daemonBinaries.countByRepository(repository.name);
      // Published VERSIONS, not files. A docs bundle is fifty-odd paths and none of them is an
      // identity, so counting files here would report a number no other view of this type uses.
      case DocsProfile.KEY -> docsSites.countByRepository(repository.name);
      case CiScreenshotsProfile.KEY, CiVideosProfile.KEY ->
          records.countByRepository(repository.name);
      // Stored documents — one number, the same type-dependent meaning as the lines above. This
      // arm is the one every @QuarkusTest here would miss: the suites wipe rows and seed their
      // own, so a seeded repository of a type these switches did not serve 400'd the whole
      // listing only in the PACKAGED process — every browser story timed out on an empty table.
      case SbomProfile.KEY -> sbomDocuments.countByRepository(repository.name);
      default -> throw unservable(repository);
    };
  }

  private Long sizeOf(ArtifactRepository repository) {
    return switch (repository.type) {
      case OciImagesProfile.KEY ->
          OciManifestFootprints.sum(footprints.union(manifests.listByRepository(repository.name)));
      case NpmPackagesProfile.KEY ->
          tarballBytes(List.of(repository.name), diskIndex.sizes(), new HashSet<>());
      // The union over distinct blob ids, sized from the rows — the one protocol table that has it.
      case MavenPackagesProfile.KEY -> mavenBytes(repository.name);
      case DaemonBinariesProfile.KEY -> daemonBytes(repository.name);
      case DocsProfile.KEY -> docsBytes(repository.name);
      case CiScreenshotsProfile.KEY, CiVideosProfile.KEY -> recordBytes(repository.name);
      case SbomProfile.KEY -> sbomBytes(repository.name);
      default -> throw unservable(repository);
    };
  }

  /** Distinct content of the SBOM store — the daemon half verbatim, sized from the rows. */
  private long sbomBytes(String repository) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : sbomDocuments.listDistinctBlobs(repository)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /** A row whose type this deployment serves no view of — named, never silently counted as empty. */
  private static BadRequestException unservable(ArtifactRepository repository) {
    return new BadRequestException(
        "Repository '"
            + repository.name
            + "' carries type "
            + wireName(repository)
            + ", which this service does not serve");
  }

  /** The npm half of a union: distinct tarballs, sized from disk, with what they name collected. */
  private long tarballBytes(
      List<String> repositoryNames, Map<String, Long> onDisk, Set<String> referenced) {
    long total = 0;
    for (String blobId : versions.listTarballBlobIds(repositoryNames)) {
      referenced.add(blobId);
      total += onDisk.getOrDefault(blobId, 0L);
    }
    return total;
  }

  /** Distinct content of a CI repository. The record table is the one place a size sits by an id. */
  private long recordBytes(String repository) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : records.listDistinctBlobs(repository)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /** Distinct content of a maven repository, sized from the rows on the same pattern as the CI half. */
  private long mavenBytes(String repository) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : mavenArtifacts.listDistinctBlobs(repository)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /**
   * Distinct content of a docs repository — the daemon half verbatim, sized from the rows.
   *
   * <p>The {@code distinct} here does more work than in its two siblings: a bundle's fonts and
   * unchanged chunks repeat across every version that references them, so the row count and the blob
   * count differ by design and summing rows would overstate the disk several times over.
   */
  private long docsBytes(String repository) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : docsFiles.listDistinctBlobs(repository)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /**
   * Distinct content of one site — the repository union, narrowed.
   *
   * <p>Σ over the sites of a repository is ≥ {@link #docsBytes(String)}, and the gap is whatever two
   * sites share (a font vendored by both). Neither figure is wrong: this one answers "what would
   * this site cost on its own", the other answers "what does this repository occupy".
   */
  private long docsBytes(String repository, String name) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : docsFiles.listDistinctBlobs(repository, name)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /** Distinct content of a daemon repository — the maven half verbatim, sized from the rows. */
  private long daemonBytes(String repository) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : daemonBinaries.listDistinctBlobs(repository)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }

  /** Distinct content of one daemon — the repository union narrowed, {@link #docsBytes}' twin. */
  private long daemonBytes(String repository, String name) {
    Map<String, Long> distinct = new TreeMap<>();
    for (Object[] blob : daemonBinaries.listDistinctBlobs(repository, name)) {
      distinct.putIfAbsent((String) blob[0], (Long) blob[1]);
    }
    return OciManifestFootprints.sum(distinct);
  }
}
