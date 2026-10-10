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
import java.util.zip.GZIPOutputStream;
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

  /** The daemon repository every deployment carries, and the one consumers name. */
  static final String DAEMONS = ArtifactsRepositorySeeder.DAEMONS;

  /** A name no other suite uses, so the listing holds this state's rows and nothing else. */
  static final String DAEMON = "contract-daemon";

  /** The newer of the two versions; the one a HEAD names. */
  static final String DAEMON_VERSION = "2026.2.0";

  /** The version a publish into the empty daemon creates. */
  static final String NEW_DAEMON_VERSION = "2026.3.0";

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
          + "\"configuredImages\":{\"generatedAt\":\"2026-09-04T20:00:00Z\","
          + "\"pins\":[{\"image\":\"qits/workspace\",\"version\":\"2026.904.160522\","
          + "\"application\":\"qits-workspaces\",\"key\":\"env.QITS_WORKSPACE_IMAGE_VERSION\"}]},"
          + "\"workspaceLaunches\":{\"generatedAt\":\"2026-09-05T06:00:00Z\","
          + "\"pins\":[{\"image\":\"qits/workspace\",\"version\":\"2026.903.120000\",\"launches\":\"workspace\"}]},"
          + "\"projectLaunches\":{\"generatedAt\":\"2026-09-05T06:00:00Z\","
          + "\"pins\":[{\"image\":\"qits/project-agent\",\"version\":\"2026.903.090000\",\"launches\":\"agent\"}]}"
          + "}}";

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
   * Deletes every row on the contract coordinates: the daemon's versions, the sbom, and the docs
   * sites (their files and metadata cascade). Blobs stay — they are content-addressed, the next run
   * writes the same ones, and the GC owns them. The content-hash pom stays too: the maven wire has
   * no delete, and re-publishing the same pom with the same hash is a green no-op.
   */
  public void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              daemonBinaries.list("repository = ?1 and name = ?2", DAEMONS, DAEMON).forEach(
                  daemonBinaries::delete);
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

  private DaemonBinary daemonRow(String version, String content, Instant publishedAt) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    String blobId = store(bytes);
    DaemonBinary row = new DaemonBinary();
    row.repository = DAEMONS;
    row.name = DAEMON;
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
   * gzip header with no time — so the recorded request body is the same on every run.
   */
  static byte[] tarGz(Map<String, String> files) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
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

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
