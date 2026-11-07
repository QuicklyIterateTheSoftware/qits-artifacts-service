package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.control.DocsRegistryCollection;
import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmRegistryCollection;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.control.OciRegistryCollection;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.control.SbomRegistryCollection;
import eu.wohlben.qits.artifacts.entity.ContentHash;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.gc.dto.ApplicationPurgeReport;
import eu.wohlben.qits.artifacts.gc.dto.PurgedPackage;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.MetadataKeys;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Removes everything a retired application and its repositories published, across every store type
 * (qits-1941) — the decommission behind {@code POST /applications/{application}/purge}.
 *
 * <p><b>Not a collector, and it shares none of the collector's judgement.</b> No grace window, no
 * pin, no belt and no closure is asked: retiring an application is an explicit decision that its
 * packages are gone, and a keep-set exists to protect what something still uses. What it shares
 * with the GC is the <em>mechanism</em> — every row leaves through the same narrow collection door
 * the type's adapter uses, so the tombstone, the cascades and the dist-tag and tag refusals hold
 * exactly as they do for a sweep. Blobs are not touched: a row-less blob is {@link BlobSweep}'s on
 * a later run, where the whole-store census decides whether anything else still names it. It lives
 * in this module because this is the one that may already see every door.
 *
 * <p><b>There is no ownership column, so a package is the application's by NAME, and every match
 * is an equality.</b> {@code qits-system} is a prefix of other repositories' names, so a prefix
 * match would purge siblings. With {@code A} the application and {@code R} each repository named:
 *
 * <ul>
 *   <li><b>docs</b> — every site {@code @<scope>/<A>} or {@code @<scope>/<R>} (changelogs, contracts
 *       and userflows alike), plus every site one of whose versions was published from {@code R}
 *       ({@link MetadataKeys#GIT_REPOSITORY_NAME}). All versions of such a site go.
 *   <li><b>maven</b> — {@code eu.wohlben.qits} artifacts {@code <A>-golden-masters}, {@code <A>},
 *       {@code <R>}, {@code <R>-pacts-<provider>} (R as the consumer) and {@code
 *       <consumer>-pacts-<R>} (R as the provider): every row under the artifact's directory, its
 *       artifact-level metadata files included, and its content hashes.
 *   <li><b>npm</b> — {@code @qits/<A'>-golden-masters}, {@code @qits/<A>}, {@code @qits/<A'>} and
 *       {@code @qits/<R>}, where {@code A'} is {@code A} without one leading {@code qits-}: every
 *       version, its dist-tags first (the door refuses a version a tag names), and its content
 *       hashes. The door writes a tombstone per version, which is right — a decommissioned
 *       coordinate must never come to mean other bytes.
 *   <li><b>oci</b> — the image {@code <A>} in every images repository: every tag, then every manifest.
 *   <li><b>sbom</b> — every document describing a package the rules above name ({@code maven} as
 *       {@code groupId:artifactId}, {@code npm}), the image ({@code docker}, {@code
 *       <images-repository>/<A>}) or a daemon {@code <A>}/{@code <R>}. Matched by the same rules
 *       rather than by what was removed this call, so a document whose artifact is already gone
 *       still goes.
 * </ul>
 *
 * <p><b>Idempotent by construction.</b> Everything is re-derived from the rows standing now, so a
 * second call finds nothing and answers an empty list. Each package is removed in a transaction of
 * its own: a failure part way leaves every package whole or gone, and the next call takes the rest.
 */
@Singleton
public class ApplicationPurge {

  /** The one spelling an application or repository name may have here: lower-case, dashed. */
  static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

  /** The platform's own maven group; nothing outside it is an application's to purge. */
  static final String MAVEN_GROUP = "eu.wohlben.qits";

  /** The {@code content_hash.ecosystem} spellings, as the ledger and the GC adapters write them. */
  private static final String MAVEN_ECOSYSTEM = "maven";

  private static final String NPM_ECOSYSTEM = "npm";
  private static final String MAVEN_GROUP_PATH = MAVEN_GROUP.replace('.', '/');
  private static final String NPM_SCOPE = "@qits/";
  private static final String PACTS = "-pacts-";
  private static final String GOLDEN_MASTERS = "-golden-masters";

  private static final Comparator<PurgedPackage> ORDER =
      Comparator.comparing(PurgedPackage::type)
          .thenComparing(PurgedPackage::repository)
          .thenComparing(PurgedPackage::name);

  @Inject ArtifactRepositoryRepository repositories;
  @Inject DocsSiteRepository docsSites;
  @Inject DocsRegistryCollection docs;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject MavenRegistryCollection maven;
  @Inject NpmVersionRepository npmVersions;
  @Inject NpmDistTagRepository npmDistTags;
  @Inject NpmRegistryCollection npm;
  @Inject OciTagRepository ociTags;
  @Inject OciManifestRepository ociManifests;
  @Inject OciRegistryCollection oci;
  @Inject SbomDocumentRepository sbomDocuments;
  @Inject SbomRegistryCollection sboms;
  @Inject ContentHashRepository contentHashes;

  /**
   * Removes every package of {@code application} and of each of {@code repositoryNames}, and says
   * what went.
   *
   * @throws BadRequestException a name that is not {@link #NAME} — a blank or odd one could only
   *     ever match by accident
   */
  public ApplicationPurgeReport purge(String application, List<String> repositoryNames) {
    requireName("application", application);
    Set<String> named = new TreeSet<>();
    for (String repository : repositoryNames == null ? List.<String>of() : repositoryNames) {
      requireName("repository", repository);
      named.add(repository);
    }
    Targets targets = new Targets(application, named);

    List<ArtifactRepository> stores = repositories.listAll();
    List<String> imageRepositories = namesOf(stores, OciImagesProfile.KEY);
    List<PurgedPackage> removed = new ArrayList<>();
    for (String repository : namesOf(stores, DocsProfile.KEY)) {
      purgeDocs(repository, targets, removed);
    }
    for (String repository : namesOf(stores, MavenPackagesProfile.KEY)) {
      purgeMaven(repository, targets, removed);
    }
    for (String repository : namesOf(stores, NpmPackagesProfile.KEY)) {
      purgeNpm(repository, targets, removed);
    }
    for (String repository : imageRepositories) {
      purgeImage(repository, targets, removed);
    }
    for (String repository : namesOf(stores, SbomProfile.KEY)) {
      purgeSboms(repository, targets, imageRepositories, removed);
    }
    removed.sort(ORDER);
    return new ApplicationPurgeReport(application, List.copyOf(removed));
  }

  private static void requireName(String what, String value) {
    if (value == null || !NAME.matcher(value).matches()) {
      throw new BadRequestException(
          "the " + what + " to purge must match " + NAME.pattern() + ", got '" + value + "'");
    }
  }

  // --- docs ------------------------------------------------------------------------------------

  private void purgeDocs(String repository, Targets targets, List<PurgedPackage> removed) {
    Set<String> sites = new TreeSet<>();
    for (String site : docsSites.listNames(repository)) {
      if (targets.namesSite(site)) {
        sites.add(site);
      }
    }
    // A site published FROM a named repository goes whatever it is called: the metadata is the one
    // place the store records where a bundle came from.
    for (String source : targets.repositories()) {
      sites.addAll(sitesPublishedFrom(repository, source));
    }
    for (String site : sites) {
      List<String> versions =
          docsSites.listVersions(repository, site).stream()
              .map((DocsSite row) -> row.version)
              .sorted()
              .toList();
      if (versions.isEmpty()) {
        continue;
      }
      QuarkusTransaction.requiringNew()
          .run(() -> versions.forEach(version -> docs.collect(repository, site, version)));
      removed.add(new PurgedPackage("docs", repository, site, versions));
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> sitesPublishedFrom(String repository, String source) {
    return docsSites
        .getEntityManager()
        .createNativeQuery(
            "select distinct name from docs_site_metadata"
                + " where repository = ?1 and meta_key = ?2 and meta_value = ?3",
            String.class)
        .setParameter(1, repository)
        .setParameter(2, MetadataKeys.GIT_REPOSITORY_NAME)
        .setParameter(3, source)
        .getResultList();
  }

  // --- maven -----------------------------------------------------------------------------------

  private void purgeMaven(String repository, Targets targets, List<PurgedPackage> removed) {
    Map<String, List<String>> pathsByArtifact = new TreeMap<>();
    Map<String, Set<String>> versionsByArtifact = new TreeMap<>();
    for (String path : mavenArtifacts.listPaths(repository)) {
      String artifactId = platformArtifactIdOf(path);
      if (artifactId == null || !targets.namesMavenArtifact(artifactId)) {
        continue;
      }
      pathsByArtifact.computeIfAbsent(artifactId, key -> new ArrayList<>()).add(path);
      Set<String> versions = versionsByArtifact.computeIfAbsent(artifactId, key -> new TreeSet<>());
      MavenLayout.ArtifactPath parsed = MavenLayout.parse(path);
      if (parsed != null) {
        versions.add(parsed.version());
      }
    }
    // A content hash can outlive its files; it describes a version this store no longer serves.
    for (ContentHash row :
        contentHashes.<ContentHash>list(
            "repository = ?1 and ecosystem = ?2", repository, MAVEN_ECOSYSTEM)) {
      String artifactId = platformArtifactIdOfName(row.packageName);
      if (artifactId != null && targets.namesMavenArtifact(artifactId)) {
        pathsByArtifact.computeIfAbsent(artifactId, key -> new ArrayList<>());
        versionsByArtifact.computeIfAbsent(artifactId, key -> new TreeSet<>()).add(row.version);
      }
    }
    pathsByArtifact.forEach(
        (artifactId, paths) -> {
          String packageName = MAVEN_GROUP + ":" + artifactId;
          // One transaction for the artifact: half a coordinate is a broken resolve, the reason
          // MavenPackagesGcAdapter.delete wraps its coordinate the same way.
          QuarkusTransaction.requiringNew()
              .run(
                  () -> {
                    paths.forEach(path -> maven.collect(repository, path));
                    contentHashes.delete(
                        "repository = ?1 and ecosystem = ?2 and packageName = ?3",
                        repository,
                        MAVEN_ECOSYSTEM,
                        packageName);
                  });
          removed.add(
              new PurgedPackage(
                  "maven",
                  repository,
                  packageName,
                  List.copyOf(versionsByArtifact.get(artifactId))));
        });
  }

  /**
   * The artifactId of a path in the platform's own group, or null for any other group. A path the
   * layout parses decides by its parsed group, so {@code eu/wohlben/qits/x/foo/1/foo-1.jar} is
   * {@code eu.wohlben.qits.x:foo} and never artifact {@code x}. What it cannot parse — the
   * artifact-level {@code maven-metadata.xml} and its checksums — is named by the segment after the
   * group.
   */
  static String platformArtifactIdOf(String path) {
    MavenLayout.ArtifactPath parsed = MavenLayout.parse(path);
    if (parsed != null) {
      return MAVEN_GROUP.equals(parsed.groupId()) ? parsed.artifactId() : null;
    }
    String[] segments = path.split("/");
    if (path.startsWith(MAVEN_GROUP_PATH + "/") && segments.length >= 5) {
      return segments[3];
    }
    return null;
  }

  private static String platformArtifactIdOfName(String packageName) {
    String prefix = MAVEN_GROUP + ":";
    return packageName.startsWith(prefix) ? packageName.substring(prefix.length()) : null;
  }

  // --- npm -------------------------------------------------------------------------------------

  private void purgeNpm(String repository, Targets targets, List<PurgedPackage> removed) {
    for (String packageName : targets.npmPackages()) {
      Set<String> versions = new TreeSet<>();
      List<String> published = new ArrayList<>();
      for (Object[] row : npmVersions.listVersionRows(repository, packageName)) {
        published.add((String) row[0]);
      }
      versions.addAll(published);
      List<ContentHash> hashes =
          contentHashes.list(
              "repository = ?1 and ecosystem = ?2 and packageName = ?3",
              repository,
              NPM_ECOSYSTEM,
              packageName);
      hashes.forEach(hash -> versions.add(hash.version));
      boolean tagged = !npmDistTags.listTags(repository, packageName).isEmpty();
      if (published.isEmpty() && hashes.isEmpty() && !tagged) {
        continue;
      }
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                // The tags first: collect refuses a version a dist-tag still names, because a tag
                // pointing at a version the packument no longer lists is a broken package.
                npmDistTags.delete(
                    "repository = ?1 and packageName = ?2", repository, packageName);
                published.forEach(version -> npm.collect(repository, packageName, version));
                contentHashes.delete(
                    "repository = ?1 and ecosystem = ?2 and packageName = ?3",
                    repository,
                    NPM_ECOSYSTEM,
                    packageName);
              });
      removed.add(new PurgedPackage("npm", repository, packageName, List.copyOf(versions)));
    }
  }

  // --- oci -------------------------------------------------------------------------------------

  private void purgeImage(String repository, Targets targets, List<PurgedPackage> removed) {
    String image = targets.application();
    List<String> tags =
        ociTags.listByImage(repository, image).stream().map((OciTag row) -> row.tag).toList();
    List<String> digests =
        ociManifests.listByImage(repository, image).stream()
            .map((OciManifest m) -> m.digest)
            .toList();
    if (tags.isEmpty() && digests.isEmpty()) {
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Tags first: collectManifest refuses a manifest a tag still names.
              tags.forEach(tag -> oci.collectTag(repository, image, tag));
              digests.forEach(digest -> oci.collectManifest(repository, image, digest));
            });
    Set<String> versions = new TreeSet<>(tags);
    digests.forEach(digest -> versions.add("sha256:" + digest));
    removed.add(new PurgedPackage("oci", repository, image, List.copyOf(versions)));
  }

  // --- sbom ------------------------------------------------------------------------------------

  private void purgeSboms(
      String repository,
      Targets targets,
      List<String> imageRepositories,
      List<PurgedPackage> removed) {
    Map<String, Set<String>> versionsByPackage = new TreeMap<>();
    for (SbomDocument row : sbomDocuments.<SbomDocument>list("repository = ?1", repository)) {
      if (targets.namesSbom(row.packageType, row.packageName, imageRepositories)) {
        versionsByPackage
            .computeIfAbsent(row.packageType + "/" + row.packageName, key -> new TreeSet<>())
            .add(row.version);
      }
    }
    versionsByPackage.forEach(
        (described, versions) -> {
          int slash = described.indexOf('/');
          String packageType = described.substring(0, slash);
          String packageName = described.substring(slash + 1);
          QuarkusTransaction.requiringNew()
              .run(
                  () ->
                      versions.forEach(
                          version -> sboms.collect(repository, packageType, packageName, version)));
          removed.add(new PurgedPackage("sbom", repository, described, List.copyOf(versions)));
        });
  }

  private static List<String> namesOf(List<ArtifactRepository> stores, String type) {
    return stores.stream()
        .filter(store -> type.equals(store.type))
        .map(store -> store.name)
        .sorted()
        .toList();
  }

  /** The application and the repositories named with it, and the rules that match their names. */
  record Targets(String application, Set<String> repositories) {

    /** {@code @<scope>/<A>} or {@code @<scope>/<R>}: the part after the first slash, exactly. */
    boolean namesSite(String site) {
      int slash = site.indexOf('/');
      if (!site.startsWith("@") || slash < 2) {
        return false;
      }
      String name = site.substring(slash + 1);
      return name.equals(application) || repositories.contains(name);
    }

    boolean namesMavenArtifact(String artifactId) {
      if (artifactId.equals(application)
          || artifactId.equals(application + GOLDEN_MASTERS)
          || repositories.contains(artifactId)) {
        return true;
      }
      for (String repository : repositories) {
        String asConsumer = repository + PACTS;
        String asProvider = PACTS + repository;
        if ((artifactId.startsWith(asConsumer) && artifactId.length() > asConsumer.length())
            || (artifactId.endsWith(asProvider) && artifactId.length() > asProvider.length())) {
          return true;
        }
      }
      return false;
    }

    Set<String> npmPackages() {
      String bare = application.startsWith("qits-") ? application.substring(5) : application;
      Set<String> names = new LinkedHashSet<>();
      names.add(NPM_SCOPE + bare + GOLDEN_MASTERS);
      names.add(NPM_SCOPE + application);
      names.add(NPM_SCOPE + bare);
      repositories.forEach(repository -> names.add(NPM_SCOPE + repository));
      return names;
    }

    boolean namesSbom(String packageType, String packageName, List<String> imageRepositories) {
      return switch (packageType) {
        case "maven" -> {
          String artifactId = platformArtifactIdOfName(packageName);
          yield artifactId != null && namesMavenArtifact(artifactId);
        }
        case "npm" -> npmPackages().contains(packageName);
        case "docker" ->
            imageRepositories.stream()
                .anyMatch(images -> packageName.equals(images + "/" + application));
        case "daemon" -> packageName.equals(application) || repositories.contains(packageName);
        default -> false;
      };
    }
  }
}
