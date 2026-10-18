package eu.wohlben.qits.artifacts.contracts;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.BlobStore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * <b>qits-artifacts' provider states</b> (qits-1149): each seeds what one consumer situation needs
 * and hands back its parameters. A copy of qits-events-service's class of the same name.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the database is shared by every test in the run.
 *
 * <p>Every state works on fixed coordinates no other suite uses ({@code contract-*}), so a listing
 * holds the state's rows only, and {@link #cleanUp()} removes them by name. The recorded writes
 * (a publish into an empty coordinate) create rows too, so clean-up does not depend on what the
 * state itself created. Every state starts with a clean-up for the same reason.
 *
 * <p>The state names are the ones the consumers' round-1 pacts asked for, reconciled: see {@code
 * /tmp/claude-1000/pact-inventory/round2/qits-artifacts-service-states.md} (ticket qits-1149).
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_DAEMON_WITH_TWO_VERSIONS = "a daemon with two versions";
  public static final String A_DAEMON_NOTHING_PUBLISHED = "a daemon nothing has published";
  public static final String NO_SBOM = "no sbom published for the coordinate";
  public static final String AN_SBOM = "an sbom published for the coordinate";
  public static final String NO_DOCS = "no docs bundle published for the coordinate";
  public static final String A_DOCS_SITE = "a published docs site";
  public static final String A_DOCS_SITE_FROM_TWO_BRANCHES =
      "a docs site published from two branches";
  public static final String CHANGELOGS = "a repository with published changelogs";
  public static final String NO_CHANGELOG = "a repository with no changelog";
  public static final String A_CONTENT_HASH = "a content hash recorded for the coordinate";
  public static final String A_STORE_WITH_CONTENT = "a registry store with content";
  public static final String UNPINNED_IDENTITIES = "a registry with unpinned identities";
  public static final String A_MAVEN_ARTIFACT = "a maven artifact published at a version";
  public static final String NO_MAVEN_ARTIFACT = "no maven artifact published at the version";
  public static final String AN_NPM_PACKAGE = "an npm package published at a version";
  public static final String NO_NPM_PACKAGE = "no npm package with the given name";
  public static final String AN_IMAGE = "an image pushed under a tag";
  public static final String NO_IMAGE = "no image with the given name";
  // round 2: what the qits CLI, the bootstrap and qits-orchestrator asked for besides
  public static final String A_MAVEN_JAR = "a maven jar published at a version";
  public static final String NO_MAVEN_JAR = "no maven jar published at the version";
  public static final String AN_NPM_CONTENT_HASH = "a content hash recorded for an npm package";
  public static final String THE_CLI_AS_A_DAEMON = "the qits CLI published as a daemon";
  public static final String AN_UNFILEABLE_PIN = "a registry handed a pin it cannot file";

  /** The daemon repository every deployment carries, and the one consumers name. */
  static final String DAEMONS = ArtifactsRepositorySeeder.DAEMONS;

  /** A name no other suite uses, so the listing holds this state's rows and nothing else. */
  static final String DAEMON = "contract-daemon";

  /** The newer of the two versions; the one a HEAD names. */
  static final String DAEMON_VERSION = "2026.2.0";

  /** The version a publish into the empty daemon creates. */
  static final String NEW_DAEMON_VERSION = "2026.3.0";

  /**
   * The name {@code qits update} reads its own releases under ({@code ArtifactsReleases.DAEMON_NAME}
   * in qits-platform-access-cli). Not a contract-only name: {@link #cleanUp()} deletes its rows in
   * this suite's database, which no other suite here relies on keeping across tests.
   */
  static final String CLI_DAEMON = "qits-platform-access-cli";

  /** The CLI's published release: a version {@code qits update} accepts as one (digits and dots). */
  static final String CLI_VERSION = "2100.1.0";

  /** The bytes a daemon publish sends — a stand-in for a binary, fixed so the digest is too. */
  public static final String DAEMON_BYTES = "contract-daemon 2026.3.0\n";

  static final String SBOM_TYPE = "maven";
  static final String SBOM_NAME = "eu.wohlben.qits:contract-artifact";
  static final String SBOM_VERSION = "2100.1.0";

  /**
   * The CycloneDX document the sbom states store and a publish sends: one root, two components
   * with purls, and the dependency graph — every member qits-maintenance's ingest reads.
   */
  public static final String SBOM_DOCUMENT =
      "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
          + "\"metadata\":{\"component\":{\"bom-ref\":\"pkg:maven/eu.wohlben.qits/contract-artifact@2100.1.0\","
          + "\"type\":\"library\",\"name\":\"contract-artifact\",\"version\":\"2100.1.0\","
          + "\"purl\":\"pkg:maven/eu.wohlben.qits/contract-artifact@2100.1.0\"}},"
          + "\"components\":["
          + "{\"bom-ref\":\"pkg:maven/eu.wohlben.qits/contract-library@1.0.0\",\"type\":\"library\","
          + "\"name\":\"contract-library\",\"version\":\"1.0.0\","
          + "\"purl\":\"pkg:maven/eu.wohlben.qits/contract-library@1.0.0\"},"
          + "{\"bom-ref\":\"pkg:npm/contract-helper@2.0.0\",\"type\":\"library\","
          + "\"name\":\"contract-helper\",\"version\":\"2.0.0\","
          + "\"purl\":\"pkg:npm/contract-helper@2.0.0\"}],"
          + "\"dependencies\":["
          + "{\"ref\":\"pkg:maven/eu.wohlben.qits/contract-artifact@2100.1.0\","
          + "\"dependsOn\":[\"pkg:maven/eu.wohlben.qits/contract-library@1.0.0\"]},"
          + "{\"ref\":\"pkg:maven/eu.wohlben.qits/contract-library@1.0.0\","
          + "\"dependsOn\":[\"pkg:npm/contract-helper@2.0.0\"]},"
          + "{\"ref\":\"pkg:npm/contract-helper@2.0.0\",\"dependsOn\":[]}]}";

  /** The CycloneDX media type a publish sends. */
  public static final String CYCLONEDX_JSON = "application/vnd.cyclonedx+json";

  static final String DOCS = ArtifactsRepositorySeeder.DOCS;
  static final String DOCS_SITE = "contract-docs-site";
  static final String DOCS_VERSION = "2100.1.0";
  static final String DOCS_FEATURE_VERSION = "2100.2.0";
  static final String DOCS_PATH = "index.html";
  static final String DOCS_BRANCH = "main";
  static final String DOCS_FEATURE_BRANCH = "feature/contract";

  /** The metadata a docs publish carries: the branch, as {@code X-Artifacts-Meta-<key>}. */
  static final String BRANCH_KEY = "git.branch.name";

  /** The repository whose changelogs the changelog states publish, under {@code @changelog/}. */
  static final String CHANGELOG_REPOSITORY = "contract-repository";
  static final String CHANGELOG_VERSION = "2100.1.0";
  static final String CHANGELOG_SITE = "@changelog/" + CHANGELOG_REPOSITORY;

  /** The bundle a docs publish sends: one {@code index.html}, packed byte-stable. */
  public static final byte[] DOCS_BUNDLE =
      tarGz(Map.of(DOCS_PATH, "<!doctype html><title>contract docs</title>\n"));

  static final byte[] CHANGELOG_BUNDLE =
      tarGz(Map.of("CHANGELOG.md", "# Changelog\n\n## 2100.1.0\n\n- A contract entry.\n"));

  static final String HASH_TYPE = "maven";
  static final String HASH_GROUP = "eu.wohlben.qits";
  static final String HASH_ARTIFACT = "contract-artifact";
  static final String HASH_NAME = HASH_GROUP + ":" + HASH_ARTIFACT;
  static final String HASH_VERSION = "2100.1.0";
  static final String CONTENT_HASH = "v1:sha256:" + "c".repeat(64);

  /** The maven coordinate the published-artifact state deploys: a pom, as a release's marker. */
  static final String MAVEN_GROUP = "eu.wohlben.qits";
  static final String MAVEN_ARTIFACT = "contract-maven";
  static final String MAVEN_VERSION = "2100.1.0";

  /**
   * The bytes a jar deploy sends: a stand-in, since the repository stores a file's bytes without
   * reading them. Text, so the index can carry it as the request body a consumer replays.
   */
  public static final String MAVEN_JAR_BYTES = "contract jar 2100.1.0\n";

  /** The coordinate nothing deploys, except the recorded publish into it (cleaned up after). */
  static final String MAVEN_ABSENT_ARTIFACT = "contract-absent";

  /** The npm package the published-package state publishes, unscoped so its path is one segment. */
  static final String NPM_PACKAGE = "contract-package";
  static final String NPM_ABSENT_PACKAGE = "contract-absent-package";
  static final String NPM_VERSION = "2100.1.0";
  static final String NPM_TAG = "main";

  /** The image the pushed-image state pushes, in the {@code qits} images repository. */
  static final String IMAGE = "contract-image";
  static final String ABSENT_IMAGE = "contract-absent-image";
  static final String IMAGE_TAG = "2100.1.0";

  /**
   * The six pin documents qits-orchestrator supplies to the plan and the sweep, each in its
   * source's own shape. None of them names the contract daemon, so its versions are unpinned.
   */
  public static final String GC_PINS =
      "{\"pins\":{"
          + "\"deployments\":{\"pins\":[{\"applicationName\":\"qits-artifacts\",\"shas\":[\"aaaa\",\"bbbb\"]}]},"
          + "\"ciDaemon\":{\"daemonName\":\"qits-ci-daemon\",\"daemonVersion\":\"2026.805.1\","
          + "\"previousDaemonVersion\":\"\",\"source\":\"adopted\"},"
          + "\"dependencies\":{\"generatedAt\":\"2026-09-04T20:00:00Z\","
          + "\"repositories\":[{\"name\":\"qits-githost-service\",\"status\":\"OK\"}],"
          + "\"pins\":[{\"ecosystem\":\"maven\",\"name\":\"eu.wohlben.qits:qits-blobstore\","
          + "\"version\":\"2026.903.85122\",\"repository\":\"qits-githost-service\","
          + "\"manifestPath\":\"pom.xml\"}]},"
          + "\"workspaceLaunches\":{\"generatedAt\":\"2026-09-05T06:00:00Z\","
          + "\"pins\":[{\"image\":\"qits/workspace\",\"version\":\"2026.903.120000\",\"launches\":\"workspace\"}]},"
          + "\"projectLaunches\":{\"generatedAt\":\"2026-09-05T06:00:00Z\","
          + "\"pins\":[{\"image\":\"qits/project-agent\",\"version\":\"2026.903.090000\",\"launches\":\"agent\"}]}"
          + "}}";

  /**
   * {@link #GC_PINS} with qits-maintenance's one pin spelled for an ecosystem this store hosts no
   * repository for ({@code cargo}): the store cannot file it, so the sweep refuses the whole run.
   */
  public static final String GC_PINS_UNFILEABLE =
      GC_PINS.replace("\"ecosystem\":\"maven\"", "\"ecosystem\":\"cargo\"");

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject ArtifactsRepositorySeeder seeder;
  @Inject DaemonBinaryRepository daemonBinaries;
  @Inject DocsSiteRepository docsSites;
  @Inject SbomDocumentRepository sboms;
  @Inject BlobStore blobStore;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(A_DAEMON_WITH_TWO_VERSIONS, this::aDaemonWithTwoVersions);
    states.put(A_DAEMON_NOTHING_PUBLISHED, this::aDaemonNothingPublished);
    states.put(NO_SBOM, this::noSbom);
    states.put(AN_SBOM, this::anSbom);
    states.put(NO_DOCS, this::noDocs);
    states.put(A_DOCS_SITE, this::aDocsSite);
    states.put(A_DOCS_SITE_FROM_TWO_BRANCHES, this::aDocsSiteFromTwoBranches);
    states.put(CHANGELOGS, this::changelogs);
    states.put(NO_CHANGELOG, this::noChangelog);
    states.put(A_CONTENT_HASH, this::aContentHash);
    states.put(A_STORE_WITH_CONTENT, this::aStoreWithContent);
    states.put(UNPINNED_IDENTITIES, this::unpinnedIdentities);
    states.put(A_MAVEN_ARTIFACT, this::aMavenArtifact);
    states.put(NO_MAVEN_ARTIFACT, this::noMavenArtifact);
    states.put(AN_NPM_PACKAGE, this::anNpmPackage);
    states.put(NO_NPM_PACKAGE, this::noNpmPackage);
    states.put(AN_IMAGE, this::anImage);
    states.put(NO_IMAGE, this::noImage);
    states.put(A_MAVEN_JAR, this::aMavenJar);
    states.put(NO_MAVEN_JAR, this::noMavenJar);
    states.put(AN_NPM_CONTENT_HASH, this::anNpmContentHash);
    states.put(THE_CLI_AS_A_DAEMON, this::theCliAsADaemon);
    states.put(AN_UNFILEABLE_PIN, this::anUnfileablePin);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    seeder.ensureDefaults();
    cleanUp();
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /**
   * Deletes every row on the contract coordinates: the daemon's versions (and the CLI's), the sbom, the docs sites
   * (their files and metadata cascade), the {@code contract-maven} and {@code contract-absent} maven
   * files with their content hashes, the npm packages' versions and tags, and the contract images'
   * tags and manifests. The wires have no delete for most of these, so it is SQL on their own
   * tables. Blobs stay — they are content-addressed, the next run writes the same ones, and the GC
   * owns them. The content-hash pom of {@code contract-artifact} stays too: re-publishing the same
   * pom with the same hash is a green no-op.
   */
  public void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              daemonBinaries
                  .list("repository = ?1 and name in ?2", DAEMONS, List.of(DAEMON, CLI_DAEMON))
                  .forEach(daemonBinaries::delete);
              sboms
                  .list(
                      "repository = ?1 and packageType = ?2 and packageName = ?3",
                      ArtifactsRepositorySeeder.SBOMS,
                      SBOM_TYPE,
                      SBOM_NAME)
                  .forEach(sboms::delete);
              docsSites
                  .list(
                      "repository = ?1 and name in ?2",
                      DOCS,
                      List.of(DOCS_SITE, CHANGELOG_SITE))
                  .forEach(docsSites::delete);
              // The registries' tables live in the same persistence unit as the daemon rows.
              var entityManager = daemonBinaries.getEntityManager();
              String group = MAVEN_GROUP.replace('.', '/') + "/";
              entityManager
                  .createNativeQuery(
                      "delete from maven_artifact where repository = ?1 and (path like ?2 or path"
                          + " like ?3)")
                  .setParameter(1, ArtifactsRepositorySeeder.MAVEN)
                  .setParameter(2, group + MAVEN_ARTIFACT + "/%")
                  .setParameter(3, group + MAVEN_ABSENT_ARTIFACT + "/%")
                  .executeUpdate();
              entityManager
                  .createNativeQuery(
                      "delete from content_hash where repository = ?1 and package_name in (?2, ?3)")
                  .setParameter(1, ArtifactsRepositorySeeder.MAVEN)
                  .setParameter(2, MAVEN_GROUP + ":" + MAVEN_ARTIFACT)
                  .setParameter(3, MAVEN_GROUP + ":" + MAVEN_ABSENT_ARTIFACT)
                  .executeUpdate();
              for (String table :
                  List.of("npm_dist_tag", "npm_version", "npm_version_tombstone")) {
                entityManager
                    .createNativeQuery(
                        "delete from " + table + " where repository = ?1 and package_name in (?2, ?3)")
                    .setParameter(1, ArtifactsRepositorySeeder.NPM)
                    .setParameter(2, NPM_PACKAGE)
                    .setParameter(3, NPM_ABSENT_PACKAGE)
                    .executeUpdate();
              }
              entityManager
                  .createNativeQuery(
                      "delete from content_hash where repository = ?1 and package_name in (?2, ?3)")
                  .setParameter(1, ArtifactsRepositorySeeder.NPM)
                  .setParameter(2, NPM_PACKAGE)
                  .setParameter(3, NPM_ABSENT_PACKAGE)
                  .executeUpdate();
              for (String table : List.of("oci_tag", "oci_manifest")) {
                entityManager
                    .createNativeQuery(
                        "delete from " + table + " where repository = ?1 and image_name in (?2, ?3)")
                    .setParameter(1, ArtifactsRepositorySeeder.IMAGES)
                    .setParameter(2, IMAGE)
                    .setParameter(3, ABSENT_IMAGE)
                    .executeUpdate();
              }
            });
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the daemon states -----------------------------------------------------------------------

  /**
   * Two versions of one daemon, the newer published a minute after the older, each with its bytes
   * stored — so the listing reads the rows and a HEAD of either version answers 200 with its digest.
   */
  private Setup aDaemonWithTwoVersions() {
    seedTwoDaemonVersions();
    return new Setup(daemonParams(DAEMON_VERSION), List.of());
  }

  /** The daemon repository exists; the daemon has no version in it. */
  private Setup aDaemonNothingPublished() {
    return new Setup(daemonParams(NEW_DAEMON_VERSION), List.of());
  }

  private void seedTwoDaemonVersions() {
    Instant first = Instant.parse("2100-01-01T00:00:00Z");
    DaemonBinary older = daemonRow("2026.1.0", "contract-daemon 2026.1.0", first);
    DaemonBinary newer =
        daemonRow(DAEMON_VERSION, "contract-daemon " + DAEMON_VERSION, first.plusSeconds(60));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              daemonBinaries.persist(older);
              daemonBinaries.persist(newer);
            });
  }

  private static Map<String, String> daemonParams(String version) {
    Map<String, String> params = new TreeMap<>();
    params.put("daemon", DAEMON);
    params.put("repository", DAEMONS);
    params.put("version", version);
    return params;
  }

  /**
   * One release of the qits CLI in the daemon repository, under the name {@code qits update} reads:
   * the listing names it as the newest, a HEAD says its length and digest, a GET serves its bytes.
   */
  private Setup theCliAsADaemon() {
    DaemonBinary row =
        daemonRow(
            CLI_DAEMON,
            CLI_VERSION,
            CLI_DAEMON + " " + CLI_VERSION + "\n",
            Instant.parse("2100-01-01T00:00:00Z"));
    QuarkusTransaction.requiringNew().run(() -> daemonBinaries.persist(row));
    Map<String, String> params = new TreeMap<>();
    params.put("daemon", CLI_DAEMON);
    params.put("repository", DAEMONS);
    params.put("version", CLI_VERSION);
    return new Setup(params, List.of());
  }

  private DaemonBinary daemonRow(String version, String content, Instant publishedAt) {
    return daemonRow(DAEMON, version, content, publishedAt);
  }

  private DaemonBinary daemonRow(String name, String version, String content, Instant publishedAt) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String blobId = store(bytes);
    DaemonBinary row = new DaemonBinary();
    row.repository = DAEMONS;
    row.name = name;
    row.version = version;
    row.blobId = blobId;
    row.sizeBytes = bytes.length;
    row.publishedAt = publishedAt;
    return row;
  }

  /** Stores bytes in the blob store and answers their id, the sha256 hex. */
  private String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), bytes.length);
    blobStore.promote(staged);
    String expected = sha256(bytes);
    if (!expected.equals(staged.sha256())) {
      throw new IllegalStateException("the blob store hashed " + staged.sha256());
    }
    return expected;
  }

  // --- the sbom states -------------------------------------------------------------------------

  /** The sbom repository exists; the coordinate has no document. */
  private Setup noSbom() {
    return new Setup(sbomParams(), List.of());
  }

  /** {@link #SBOM_DOCUMENT} is published for the coordinate. */
  private Setup anSbom() {
    publishSbom();
    return new Setup(sbomParams(), List.of());
  }

  private void publishSbom() {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType(CYCLONEDX_JSON)
            .body(SBOM_DOCUMENT.getBytes(StandardCharsets.UTF_8))
            .put("/artifacts/sboms/" + SBOM_TYPE + "/" + SBOM_NAME + "/-/" + SBOM_VERSION),
        "the sbom publish");
  }

  private static Map<String, String> sbomParams() {
    Map<String, String> params = new TreeMap<>();
    params.put("coordinate", SBOM_TYPE + "/" + SBOM_NAME);
    params.put("packageName", SBOM_NAME);
    params.put("packageType", SBOM_TYPE);
    params.put("version", SBOM_VERSION);
    return params;
  }

  // --- the docs states -------------------------------------------------------------------------

  /** The docs repository exists; the site has no version at all. */
  private Setup noDocs() {
    return new Setup(docsParams(), List.of());
  }

  /** One version of one site, published from {@code main}, holding {@code index.html}. */
  private Setup aDocsSite() {
    publishDocs(DOCS_SITE, DOCS_VERSION, DOCS_BUNDLE, DOCS_BRANCH);
    return new Setup(docsParams(), List.of());
  }

  /** Two versions of one site: one published from {@code main}, one from a feature branch. */
  private Setup aDocsSiteFromTwoBranches() {
    publishDocs(DOCS_SITE, DOCS_VERSION, DOCS_BUNDLE, DOCS_BRANCH);
    publishDocs(DOCS_SITE, DOCS_FEATURE_VERSION, DOCS_BUNDLE, DOCS_FEATURE_BRANCH);
    Map<String, String> params = new TreeMap<>();
    params.put("branch", DOCS_BRANCH);
    params.put("site", DOCS_SITE);
    return new Setup(params, List.of());
  }

  /** One changelog bundle for one repository, under the {@code @changelog/} scope. */
  private Setup changelogs() {
    publishDocs(CHANGELOG_SITE, CHANGELOG_VERSION, CHANGELOG_BUNDLE, DOCS_BRANCH);
    return new Setup(changelogParams(), List.of());
  }

  /** The docs repository exists; the repository has no changelog bundle. */
  private Setup noChangelog() {
    return new Setup(changelogParams(), List.of());
  }

  private void publishDocs(String site, String version, byte[] bundle, String branch) {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/gzip")
            .header("X-Artifacts-Meta-" + BRANCH_KEY, branch)
            .body(bundle)
            .put("/artifacts/docs/" + DOCS + "/" + site + "/-/" + version),
        "the docs publish of " + site + "@" + version);
  }

  private static Map<String, String> docsParams() {
    Map<String, String> params = new TreeMap<>();
    params.put("path", DOCS_PATH);
    params.put("site", DOCS_SITE);
    params.put("version", DOCS_VERSION);
    return params;
  }

  private static Map<String, String> changelogParams() {
    Map<String, String> params = new TreeMap<>();
    params.put("repository", CHANGELOG_REPOSITORY);
    params.put("version", CHANGELOG_VERSION);
    return params;
  }

  // --- the content hash ------------------------------------------------------------------------

  /**
   * One maven version whose pom was published with a content hash. The pom is the version's
   * "published" marker, so it alone is enough; it is the only version of the name, so it is also the
   * newest.
   */
  private Setup aContentHash() {
    String path =
        "/artifacts/maven/"
            + ArtifactsRepositorySeeder.MAVEN
            + "/"
            + HASH_GROUP.replace('.', '/')
            + "/"
            + HASH_ARTIFACT
            + "/"
            + HASH_VERSION
            + "/"
            + HASH_ARTIFACT
            + "-"
            + HASH_VERSION
            + ".pom";
    String pom =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n"
            + "  <modelVersion>4.0.0</modelVersion>\n"
            + "  <groupId>" + HASH_GROUP + "</groupId>\n"
            + "  <artifactId>" + HASH_ARTIFACT + "</artifactId>\n"
            + "  <version>" + HASH_VERSION + "</version>\n"
            + "</project>\n";
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/xml")
            .header("X-Artifacts-Content-Hash", CONTENT_HASH)
            .body(pom.getBytes(StandardCharsets.UTF_8))
            .put(path),
        "the pom publish");
    Map<String, String> params = new TreeMap<>();
    params.put("name", HASH_NAME);
    params.put("type", HASH_TYPE);
    params.put("version", HASH_VERSION);
    return new Setup(params, List.of());
  }

  /**
   * One npm version published with a content hash, as {@code qits artifacts publish npm} sends it
   * ({@code X-Artifacts-Content-Hash} on the publish document's PUT). It is the package's only
   * version, so it is also the newest.
   */
  private Setup anNpmContentHash() {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .header("X-Artifacts-Content-Hash", CONTENT_HASH)
            .body(npmPublishDocument(NPM_PACKAGE).getBytes(StandardCharsets.UTF_8))
            .put("/artifacts/npm/" + ArtifactsRepositorySeeder.NPM + "/" + NPM_PACKAGE),
        "the npm publish with a content hash");
    Map<String, String> params = new TreeMap<>();
    params.put("name", NPM_PACKAGE);
    params.put("type", "npm");
    params.put("version", NPM_VERSION);
    return new Setup(params, List.of());
  }

  // --- the store and its GC --------------------------------------------------------------------

  /** A docs site, an sbom and a daemon with two versions, so every figure has something behind it. */
  private Setup aStoreWithContent() {
    publishDocs(DOCS_SITE, DOCS_VERSION, DOCS_BUNDLE, DOCS_BRANCH);
    publishSbom();
    seedTwoDaemonVersions();
    return new Setup(new TreeMap<>(), List.of());
  }

  /**
   * A daemon with two versions that none of the supplied pins names. Both are younger than the
   * grace window, so a plan counts them and a sweep withholds them: nothing is deleted.
   */
  private Setup unpinnedIdentities() {
    seedTwoDaemonVersions();
    return new Setup(new TreeMap<>(), List.of());
  }

  /**
   * Nothing to seed: a sweep handed {@link #GC_PINS_UNFILEABLE} refuses before its census, so the
   * receipt is the same whatever the store holds — {@code aborted} names the pin, nothing is deleted.
   */
  private Setup anUnfileablePin() {
    return new Setup(new TreeMap<>(), List.of());
  }

  // --- the registry protocols: maven, npm, OCI --------------------------------------------------

  /**
   * One maven version deployed: its pom, the file every presence probe asks for. The repository
   * derives {@code maven-metadata.xml} from the deployed rows, so the version listing has this one
   * version in it.
   */
  private Setup aMavenArtifact() {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/xml")
            .body(mavenPom(MAVEN_ARTIFACT).getBytes(StandardCharsets.UTF_8))
            .put(mavenPomPath(MAVEN_ARTIFACT)),
        "the maven pom deploy");
    return new Setup(mavenParams(MAVEN_ARTIFACT), List.of());
  }

  /** Nothing is deployed under the coordinate, at any version, and no content hash names it. */
  private Setup noMavenArtifact() {
    return new Setup(mavenParams(MAVEN_ABSENT_ARTIFACT), List.of());
  }

  /**
   * One maven version deployed whole, as a release deploys it: its jar, then its pom (the version's
   * "published" marker). The jar is what the bootstrap asks for.
   */
  private Setup aMavenJar() {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/java-archive")
            .body(MAVEN_JAR_BYTES.getBytes(StandardCharsets.UTF_8))
            .put(mavenFilePath(MAVEN_ARTIFACT, ".jar")),
        "the maven jar deploy");
    return aMavenArtifact();
  }

  /** {@link #noMavenArtifact()}: no jar either, the one a publish PUTs before its pom. */
  private Setup noMavenJar() {
    return noMavenArtifact();
  }

  /** The pom a deploy of {@code artifactId} at {@link #MAVEN_VERSION} sends. */
  public static String mavenPom(String artifactId) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n"
        + "  <modelVersion>4.0.0</modelVersion>\n"
        + "  <groupId>" + MAVEN_GROUP + "</groupId>\n"
        + "  <artifactId>" + artifactId + "</artifactId>\n"
        + "  <version>" + MAVEN_VERSION + "</version>\n"
        + "</project>\n";
  }

  private static String mavenPomPath(String artifactId) {
    return mavenFilePath(artifactId, ".pom");
  }

  private static String mavenFilePath(String artifactId, String extension) {
    return "/artifacts/maven/" + ArtifactsRepositorySeeder.MAVEN + "/"
        + MAVEN_GROUP.replace('.', '/') + "/" + artifactId + "/" + MAVEN_VERSION + "/"
        + artifactId + "-" + MAVEN_VERSION + extension;
  }

  private static Map<String, String> mavenParams(String artifactId) {
    Map<String, String> params = new TreeMap<>();
    params.put("artifact", artifactId);
    params.put("groupPath", MAVEN_GROUP.replace('.', '/'));
    params.put("name", MAVEN_GROUP + ":" + artifactId);
    params.put("type", "maven");
    params.put("version", MAVEN_VERSION);
    return params;
  }

  /**
   * One npm version published, as {@code npm publish} sends it, with {@code latest} on it. The
   * tarball is packed byte-stable, so its shasum and integrity in the packument are too.
   */
  private Setup anNpmPackage() {
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body(npmPublishDocument(NPM_PACKAGE).getBytes(StandardCharsets.UTF_8))
            .put("/artifacts/npm/" + ArtifactsRepositorySeeder.NPM + "/" + NPM_PACKAGE),
        "the npm publish");
    return new Setup(npmParams(NPM_PACKAGE), List.of());
  }

  /** No version of the package was ever published. */
  private Setup noNpmPackage() {
    return new Setup(npmParams(NPM_ABSENT_PACKAGE), List.of());
  }

  /** The tarball of {@code name} at {@link #NPM_VERSION}: {@code package/package.json}, packed byte-stable. */
  static byte[] npmTarball(String name) {
    return tarGz(Map.of("package/package.json", npmManifest(name) + "\n"));
  }

  private static String npmManifest(String name) {
    return "{\"name\":\"" + name + "\",\"version\":\"" + NPM_VERSION
        + "\",\"description\":\"a contract package\",\"license\":\"MIT\"}";
  }

  /** The document {@code npm publish} PUTs for {@code name}: manifest, dist-tag and attachment. */
  public static String npmPublishDocument(String name) {
    byte[] tarball = npmTarball(name);
    String manifest = npmManifest(name);
    String version =
        manifest.substring(0, manifest.length() - 1)
            + ",\"_id\":\"" + name + "@" + NPM_VERSION + "\",\"dist\":{\"shasum\":\""
            + hex("SHA-1", tarball) + "\",\"integrity\":\"sha512-"
            + java.util.Base64.getEncoder().encodeToString(digest("SHA-512", tarball)) + "\"}}";
    return "{\"_id\":\"" + name + "\",\"name\":\"" + name + "\","
        + "\"dist-tags\":{\"latest\":\"" + NPM_VERSION + "\"},"
        + "\"versions\":{\"" + NPM_VERSION + "\":" + version + "},"
        + "\"access\":\"public\","
        + "\"_attachments\":{\"" + name + "-" + NPM_VERSION + ".tgz\":{"
        + "\"content_type\":\"application/octet-stream\",\"data\":\""
        + java.util.Base64.getEncoder().encodeToString(tarball) + "\",\"length\":"
        + tarball.length + "}}}";
  }

  /**
   * The npm states' params. {@code name} repeats {@code package}: qits-ci's and qits-maintenance's
   * presence and latest-version rows read a package by {@code name}, as they read every ecosystem.
   */
  private static Map<String, String> npmParams(String name) {
    Map<String, String> params = new TreeMap<>();
    params.put("name", name);
    params.put("package", name);
    params.put("tag", NPM_TAG);
    params.put("tarball", name + "-" + NPM_VERSION + ".tgz");
    params.put("version", NPM_VERSION);
    return params;
  }

  /**
   * One single-architecture image pushed under {@link #IMAGE_TAG}, as a client pushes it: the
   * config and layer blobs, then the manifest naming both. The layer is packed byte-stable, so the
   * manifest's digest — what {@code Docker-Content-Digest} answers — is the same on every machine.
   */
  private Setup anImage() {
    String name = ArtifactsRepositorySeeder.IMAGES + "/" + IMAGE;
    byte[] layerTar = tar(Map.of("contract.txt", "a contract image\n"));
    byte[] layer = storedGzip(layerTar);
    byte[] config =
        ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"config\":{},"
                + "\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[\"sha256:" + sha256(layerTar)
                + "\"]}}")
            .getBytes(StandardCharsets.UTF_8);
    for (byte[] blob : List.of(config, layer)) {
      expect(
          201,
          given()
              .urlEncodingEnabled(false)
              .contentType("application/octet-stream")
              .body(blob)
              .post("/v2/" + name + "/blobs/uploads/?digest=sha256:" + sha256(blob)),
          "the blob upload");
    }
    String manifest =
        "{\"schemaVersion\":2,\"mediaType\":\"" + OCI_MANIFEST + "\","
            + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
            + "\"digest\":\"sha256:" + sha256(config) + "\",\"size\":" + config.length + "},"
            + "\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar+gzip\","
            + "\"digest\":\"sha256:" + sha256(layer) + "\",\"size\":" + layer.length + "}]}";
    expect(
        201,
        given()
            .urlEncodingEnabled(false)
            .contentType(OCI_MANIFEST)
            .body(manifest.getBytes(StandardCharsets.UTF_8))
            .put("/v2/" + name + "/manifests/" + IMAGE_TAG),
        "the manifest push");
    return new Setup(imageParams(IMAGE), List.of());
  }

  /** The images repository exists; no image of the name was ever pushed. */
  private Setup noImage() {
    return new Setup(imageParams(ABSENT_IMAGE), List.of());
  }

  /** The media type the pushed manifest carries. */
  static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

  private static Map<String, String> imageParams(String image) {
    Map<String, String> params = new TreeMap<>();
    params.put("name", ArtifactsRepositorySeeder.IMAGES + "/" + image);
    params.put("tag", IMAGE_TAG);
    return params;
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static void expect(int status, Response response, String what) {
    if (response.statusCode() != status) {
      throw new IllegalStateException(
          what + " answered " + response.statusCode() + ", expected " + status + ": "
              + response.asString());
    }
  }

  /**
   * A {@code .tar.gz} of the files, byte-stable: fixed entry order, mtime, owner and mode, and a
   * gzip written by {@link #storedGzip} — so the recorded request body is the same on every run and
   * on every machine.
   */
  static byte[] tarGz(Map<String, String> files) {
    return storedGzip(tar(files));
  }

  /** The tar {@link #tarGz} gzips: fixed entry order, mtime, owner and mode. */
  static byte[] tar(Map<String, String> files) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
      for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
        byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(file.getKey(), true);
        entry.setSize(content.length);
        entry.setModTime(new Date(0));
        entry.setMode(0644);
        entry.setUserId(0);
        entry.setGroupId(0);
        entry.setUserName("");
        entry.setGroupName("");
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not build the bundle", e);
    }
    return out.toByteArray();
  }

  /**
   * A gzip of the bytes with stored (uncompressed) deflate blocks. {@code GZIPOutputStream} is not
   * byte-stable: its deflate stream depends on the JVM's zlib build, and the CI image's differs
   * from a workstation's. Stored blocks have one encoding only.
   */
  static byte[] storedGzip(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // magic, deflate, no flags, no mtime, no extra flags, OS unknown
    out.writeBytes(new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff});
    int offset = 0;
    do {
      int length = Math.min(0xffff, data.length - offset);
      boolean last = offset + length == data.length;
      out.write(last ? 1 : 0);
      out.write(length & 0xff);
      out.write(length >>> 8);
      out.write(~length & 0xff);
      out.write((~length >>> 8) & 0xff);
      out.write(data, offset, length);
      offset += length;
    } while (offset < data.length);
    CRC32 crc = new CRC32();
    crc.update(data);
    writeIntLe(out, (int) crc.getValue());
    writeIntLe(out, data.length);
    return out.toByteArray();
  }

  private static void writeIntLe(ByteArrayOutputStream out, int value) {
    for (int shift = 0; shift < 32; shift += 8) {
      out.write((value >>> shift) & 0xff);
    }
  }

  private static String sha256(byte[] bytes) {
    return hex("SHA-256", bytes);
  }

  private static String hex(String algorithm, byte[] bytes) {
    return HexFormat.of().formatHex(digest(algorithm, bytes));
  }

  private static byte[] digest(String algorithm, byte[] bytes) {
    try {
      return MessageDigest.getInstance(algorithm).digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
