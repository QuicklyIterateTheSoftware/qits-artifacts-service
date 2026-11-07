package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.SuiteBearers;
import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.MetadataKeys;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /applications/{application}/purge} (qits-1941): a retired application's packages go
 * from every store type, and nothing whose name merely LOOKS like the application's goes with them.
 *
 * <p>The look-alikes are the point of the seeding. There is no ownership column, so the rules are
 * name equalities; each unrelated row here is one a prefix or substring match would have taken —
 * {@code @contracts/xy} and {@code @contracts/x-other} beside {@code @contracts/x}, {@code
 * xy-golden-masters} beside {@code x-golden-masters}, {@code x-other-service-pacts-y} beside the
 * application's own pacts, the group {@code eu.wohlben.qits.x} beside the artifact {@code x}, the
 * image {@code xy} beside {@code x}. The rows are written straight into the tables: what is on trial
 * is which rows the purge selects and that it removes them through the collection doors, not how a
 * publish lands. The service module has no table reset, so the suite removes its own rows after
 * every case.
 */
@QuarkusTest
class ApplicationPurgeTest {

  private static final String PURGE = "/artifacts/api/applications/{application}/purge";
  private static final String BODY = "{\"repositories\":[\"x-service\",\"x-frontend\"]}";

  private static final String DOCS = ArtifactsRepositorySeeder.DOCS;
  private static final String MAVEN = ArtifactsRepositorySeeder.MAVEN;
  private static final String NPM = ArtifactsRepositorySeeder.NPM;
  private static final String IMAGES = ArtifactsRepositorySeeder.IMAGES;
  private static final String SBOMS = ArtifactsRepositorySeeder.SBOMS;

  private static final List<String> SITES =
      List.of("@contracts/x", "@changelog/x-service", "@changelog/z-thing", "@contracts/xy",
          "@contracts/x-other");
  private static final List<String> ARTIFACTS =
      List.of("x-golden-masters", "x-service-pacts-y-service", "y-service-pacts-x-service",
          "xy-golden-masters", "x-other-service-pacts-y");
  private static final String GROUP = "eu.wohlben.qits";

  /**
   * Every file seeded: one release pom per artifact, a second version and a jar of the
   * application's golden masters, an artifact-level file outside every version directory (it goes
   * with the artifact), and the GROUP {@code eu.wohlben.qits.x}, which is not the artifact {@code x}.
   */
  private static final List<String> MAVEN_PATHS =
      java.util.stream.Stream.concat(
              ARTIFACTS.stream()
                  .map(artifactId -> artifactId + "/2100.1.0/" + artifactId + "-2100.1.0.pom"),
              java.util.stream.Stream.of(
                  "x-golden-masters/2100.1.0/x-golden-masters-2100.1.0.jar",
                  "x-golden-masters/2100.2.0/x-golden-masters-2100.2.0.pom",
                  "x-golden-masters/maven-metadata.xml.sha1",
                  "x/foo/2100.1.0/foo-2100.1.0.pom"))
          .map(underGroup -> "eu/wohlben/qits/" + underGroup)
          .toList();

  private static final List<String> SBOM_NAMES =
      List.of("qits/x", "qits/xy", GROUP + ":x-golden-masters");

  private static final List<String> NPM_PACKAGES =
      List.of("@qits/x-golden-masters", "@qits/xy-golden-masters");
  private static final List<String> IMAGE_NAMES = List.of("x", "xy");

  private static final String MANIFEST_X = "1".repeat(64);
  private static final String MANIFEST_XY = "2".repeat(64);
  private static final String BLOB = "f".repeat(64);
  private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

  @Inject ArtifactsRepositorySeeder seeder;
  @Inject DocsSiteRepository sites;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject NpmVersionRepository npmVersions;
  @Inject NpmDistTagRepository npmDistTags;
  @Inject NpmVersionTombstoneRepository npmTombstones;
  @Inject OciTagRepository ociTags;
  @Inject OciManifestRepository ociManifests;
  @Inject SbomDocumentRepository sbomDocuments;
  @Inject ContentHashRepository contentHashes;

  @BeforeEach
  void seed() {
    seeder.ensureDefaults();
    cleanUp();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              site("@contracts/x", "2100.1.0", null);
              site("@changelog/x-service", "2100.1.0", null);
              site("@changelog/x-service", "2100.2.0", null);
              // Named for nothing here, but published FROM a named repository.
              site("@changelog/z-thing", "2100.1.0", "x-frontend");
              site("@contracts/xy", "2100.1.0", null);
              site("@contracts/x-other", "2100.1.0", "x-other");

              MAVEN_PATHS.forEach(this::mavenFile);
              for (String artifactId : ARTIFACTS) {
                contentHash(MAVEN, "maven", GROUP + ":" + artifactId, "2100.1.0");
              }

              for (String name : NPM_PACKAGES) {
                npmVersion(name, "2100.1.0");
                npmDistTag(name, "latest", "2100.1.0");
                contentHash(NPM, "npm", name, "2100.1.0");
              }

              image("x", "2100.1.0", MANIFEST_X);
              image("xy", "2100.1.0", MANIFEST_XY);

              sbom("docker", "qits/x", "2100.1.0");
              sbom("maven", "eu.wohlben.qits:x-golden-masters", "2100.1.0");
              sbom("docker", "qits/xy", "2100.1.0");
            });
  }

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // Bulk deletes: the files and metadata of a docs version cascade in the schema.
              sites.delete("repository = ?1 and name in ?2", DOCS, SITES);
              mavenArtifacts.delete("repository = ?1 and path in ?2", MAVEN, MAVEN_PATHS);
              contentHashes.delete(
                  "repository = ?1 and packageName in ?2",
                  MAVEN,
                  ARTIFACTS.stream().map(artifactId -> GROUP + ":" + artifactId).toList());
              contentHashes.delete("repository = ?1 and packageName in ?2", NPM, NPM_PACKAGES);
              npmDistTags.delete("repository = ?1 and packageName in ?2", NPM, NPM_PACKAGES);
              npmVersions.delete("repository = ?1 and packageName in ?2", NPM, NPM_PACKAGES);
              npmTombstones.delete("repository = ?1 and packageName in ?2", NPM, NPM_PACKAGES);
              ociTags.delete("repository = ?1 and imageName in ?2", IMAGES, IMAGE_NAMES);
              ociManifests.delete("repository = ?1 and imageName in ?2", IMAGES, IMAGE_NAMES);
              sbomDocuments.delete("repository = ?1 and packageName in ?2", SBOMS, SBOM_NAMES);
            });
  }

  @Test
  void thePurgeTakesExactlyTheApplicationsPackagesAndSaysSo() {
    List<Map<String, Object>> removed =
        given()
            .header("Authorization", SuiteBearers.platform())
            .contentType(ContentType.JSON)
            .body(BODY)
            .when()
            .post(PURGE, "x")
            .then()
            .statusCode(200)
            .body("application", is("x"))
            .extract()
            .jsonPath()
            .getList("removed");

    assertThat(
        removed.stream().map(ApplicationPurgeTest::line).toList(),
        contains(
            "docs docs @changelog/x-service [2100.1.0, 2100.2.0]",
            "docs docs @changelog/z-thing [2100.1.0]",
            "docs docs @contracts/x [2100.1.0]",
            "maven maven eu.wohlben.qits:x-golden-masters [2100.1.0, 2100.2.0]",
            "maven maven eu.wohlben.qits:x-service-pacts-y-service [2100.1.0]",
            "maven maven eu.wohlben.qits:y-service-pacts-x-service [2100.1.0]",
            "npm npm @qits/x-golden-masters [2100.1.0]",
            "oci qits x [2100.1.0, sha256:" + MANIFEST_X + "]",
            "sbom sboms docker/qits/x [2100.1.0]",
            "sbom sboms maven/eu.wohlben.qits:x-golden-masters [2100.1.0]"));

    // What went is gone from the tables, and every look-alike is still standing.
    assertThat(sites.listNames(DOCS), hasItems("@contracts/xy", "@contracts/x-other"));
    assertThat(sites.listNames(DOCS), not(hasItem("@contracts/x")));
    assertThat(sites.listNames(DOCS), not(hasItem("@changelog/x-service")));
    assertThat(sites.listNames(DOCS), not(hasItem("@changelog/z-thing")));
    assertThat(
        mavenArtifacts.listPaths(MAVEN).stream().filter(MAVEN_PATHS::contains).toList(),
        containsInAnyOrder(
            "eu/wohlben/qits/xy-golden-masters/2100.1.0/xy-golden-masters-2100.1.0.pom",
            "eu/wohlben/qits/x-other-service-pacts-y/2100.1.0/x-other-service-pacts-y-2100.1.0.pom",
            "eu/wohlben/qits/x/foo/2100.1.0/foo-2100.1.0.pom"));
    assertFalse(
        contentHashes.findOne(MAVEN, "maven", GROUP + ":x-golden-masters", "2100.1.0").isPresent());
    assertTrue(
        contentHashes.findOne(MAVEN, "maven", GROUP + ":xy-golden-masters", "2100.1.0").isPresent());
    assertFalse(npmVersions.findOne(NPM, "@qits/x-golden-masters", "2100.1.0").isPresent());
    assertThat(npmDistTags.listTags(NPM, "@qits/x-golden-masters"), empty());
    // The door's tombstone: a decommissioned coordinate never comes to mean other bytes.
    assertTrue(npmTombstones.findOne(NPM, "@qits/x-golden-masters", "2100.1.0").isPresent());
    assertFalse(
        contentHashes.findOne(NPM, "npm", "@qits/x-golden-masters", "2100.1.0").isPresent());
    assertTrue(npmVersions.findOne(NPM, "@qits/xy-golden-masters", "2100.1.0").isPresent());
    assertThat(npmDistTags.listTags(NPM, "@qits/xy-golden-masters"), hasSize(1));
    assertTrue(
        contentHashes.findOne(NPM, "npm", "@qits/xy-golden-masters", "2100.1.0").isPresent());
    assertThat(ociTags.listByImage(IMAGES, "x"), empty());
    assertThat(ociManifests.listByImage(IMAGES, "x"), empty());
    assertThat(ociTags.listByImage(IMAGES, "xy"), hasSize(1));
    assertThat(ociManifests.listByImage(IMAGES, "xy"), hasSize(1));
    assertFalse(sbomDocuments.findOne(SBOMS, "docker", "qits/x", "2100.1.0").isPresent());
    assertTrue(sbomDocuments.findOne(SBOMS, "docker", "qits/xy", "2100.1.0").isPresent());
  }

  @Test
  void aSecondPurgeFindsNothingAndSaysSo() {
    purgeAsThePlatform().body("removed", not(empty()));
    purgeAsThePlatform().body("application", is("x")).body("removed", empty());
  }

  @Test
  void onlyThePlatformMayPurgeAndARefusalRemovesNothing() {
    for (String caller :
        List.of(
            SuiteBearers.operator(),
            SuiteBearers.bearer("an-agent", "qits:agent"),
            SuiteBearers.ciRun())) {
      given()
          .header("Authorization", caller)
          .contentType(ContentType.JSON)
          .body(BODY)
          .when()
          .post(PURGE, "x")
          .then()
          .statusCode(403);
    }
    given().contentType(ContentType.JSON).body(BODY).when().post(PURGE, "x").then().statusCode(401);

    assertThat(sites.listNames(DOCS), hasItem("@contracts/x"));
    assertThat(ociTags.listByImage(IMAGES, "x"), hasSize(1));
  }

  @Test
  void withoutABodyOnlyTheApplicationsOwnNamesMatch() {
    // No repositories named: the application's own site, golden masters, npm package and image
    // still go, and nothing a repository rule would have reached does.
    List<Map<String, Object>> removed =
        given()
            .header("Authorization", SuiteBearers.platform())
            .when()
            .post(PURGE, "x")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("removed");
    assertThat(
        removed.stream().map(entry -> entry.get("name")).toList(),
        contains(
            "@contracts/x",
            "eu.wohlben.qits:x-golden-masters",
            "@qits/x-golden-masters",
            "x",
            "docker/qits/x",
            "maven/eu.wohlben.qits:x-golden-masters"));
  }

  @Test
  void anOddNameOrBodyIsRefusedBeforeAnythingIsRemoved() {
    for (String body :
        List.of(
            "{\"repositories\":[\"\"]}",
            "{\"repositories\":[\"X-Service\"]}",
            "{\"repositories\":[\"-x\"]}",
            "{\"repositories\":\"x-service\"}",
            "not json")) {
      given()
          .header("Authorization", SuiteBearers.platform())
          .contentType(ContentType.JSON)
          .body(body)
          .when()
          .post(PURGE, "x")
          .then()
          .statusCode(400);
    }
    given()
        .header("Authorization", SuiteBearers.platform())
        .when()
        .post(PURGE, "X")
        .then()
        .statusCode(400);
    assertThat(sites.listNames(DOCS), hasItem("@contracts/x"));
  }

  private io.restassured.response.ValidatableResponse purgeAsThePlatform() {
    return given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(BODY)
        .when()
        .post(PURGE, "x")
        .then()
        .statusCode(200);
  }

  private static String line(Map<String, Object> entry) {
    return entry.get("type")
        + " "
        + entry.get("repository")
        + " "
        + entry.get("name")
        + " "
        + entry.get("versions");
  }

  // --- the rows ----------------------------------------------------------------------------------

  private void site(String name, String version, String publishedFrom) {
    DocsSite row = new DocsSite();
    row.repository = DOCS;
    row.name = name;
    row.version = version;
    row.publishedAt = AT;
    if (publishedFrom != null) {
      row.metadata.put(MetadataKeys.GIT_REPOSITORY_NAME, publishedFrom);
    }
    sites.persist(row);
  }

  private void mavenFile(String path) {
    MavenArtifact row = new MavenArtifact();
    row.repository = MAVEN;
    row.path = path;
    row.blobId = BLOB;
    row.sizeBytes = 1;
    row.createdAt = AT;
    mavenArtifacts.persist(row);
  }

  private void contentHash(String repository, String ecosystem, String name, String version) {
    contentHashes.insertIfAbsent(repository, ecosystem, name, version, "v1:sha256:" + BLOB, AT);
  }

  private void npmVersion(String name, String version) {
    NpmVersion row = new NpmVersion();
    row.repository = NPM;
    row.packageName = name;
    row.version = version;
    row.tarballBlobId = BLOB;
    row.manifestJson = "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}";
    row.createdAt = AT;
    npmVersions.persist(row);
  }

  private void npmDistTag(String name, String tag, String version) {
    NpmDistTag row = new NpmDistTag();
    row.repository = NPM;
    row.packageName = name;
    row.tag = tag;
    row.version = version;
    row.updatedAt = AT;
    npmDistTags.persist(row);
  }

  private void image(String name, String tag, String digest) {
    OciManifest manifest = new OciManifest();
    manifest.repository = IMAGES;
    manifest.imageName = name;
    manifest.digest = digest;
    manifest.mediaType = "application/vnd.oci.image.manifest.v1+json";
    manifest.size = 1;
    manifest.createdAt = AT;
    ociManifests.persist(manifest);
    OciTag row = new OciTag();
    row.repository = IMAGES;
    row.imageName = name;
    row.tag = tag;
    row.manifestDigest = digest;
    row.updatedAt = AT;
    ociTags.persist(row);
  }

  private void sbom(String type, String name, String version) {
    SbomDocument row = new SbomDocument();
    row.repository = SBOMS;
    row.packageType = type;
    row.packageName = name;
    row.version = version;
    row.blobId = BLOB;
    row.sizeBytes = 1;
    row.specVersion = "1.6";
    row.createdAt = AT;
    sbomDocuments.persist(row);
  }
}
