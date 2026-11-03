package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.SuiteBearers;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.DocsFile;
import eu.wohlben.qits.artifacts.entity.DocsSite;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The browse endpoints on the wire: the two ways a repository can be the wrong subject, and the
 * three names in this service that contain a slash.
 *
 * <p>Those names are the reason this suite exists at all. An OCI image name may have slashes in it
 * ({@code qits/build-images/ci-base} is repository {@code qits}, image {@code
 * build-images/ci-base}), so does every scoped npm package, and so does a docs site
 * ({@code @userflows/qits-artifacts} is one site). A browser sends them
 * percent-encoded and a person pastes them literally, and both have to reach the same row — which is
 * a property of the path templates and of nothing else, so it needs a request to prove. The docs
 * one is the sharpest case: its own WIRE accepts only the literal spelling, so the two surfaces
 * differ here on purpose and only a request says which is which.
 *
 * <p>RestAssured re-encodes a path by default, which would make every assertion below about
 * RestAssured rather than about the routes; {@code urlEncodingEnabled(false)} is what sends the
 * spelling the test names. The same rule the registry and npm suites carry.
 */
@QuarkusTest
class ArtifactBrowseControllerTest {

  private static final String IMAGE = "build-images/browse-it";
  private static final String PACKAGE = "@qits/browse-it";
  private static final String DAEMON = "browse-it-daemon";
  private static final String SITE = "@qits/browse-it-docs";

  @Inject ArtifactRepositoryService repositoryService;
  @Inject BlobStore blobStore;
  @Inject OciManifestRepository manifests;
  @Inject OciTagRepository tags;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject DaemonBinaryRepository daemonBinaries;
  @Inject DocsSiteRepository docsSites;
  @Inject DocsFileRepository docsFiles;

  @BeforeEach
  void seed() {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
    repositoryService.ensure("daemons", DaemonBinariesProfile.KEY);
    repositoryService.ensure("docs", DocsProfile.KEY);

    byte[] config = filled(5, (byte) 7);
    String configDigest = store(config);
    byte[] document =
        ("{\"schemaVersion\":2,\"mediaType\":\""
                + OciMediaTypes.OCI_MANIFEST_V1
                + "\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\","
                + "\"digest\":\"sha256:"
                + configDigest
                + "\",\"size\":5},\"layers\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    String manifestDigest = store(document);
    String tarball = store(filled(42, (byte) 8));
    // The daemon and docs subjects. The font is shared between the site's two versions on purpose:
    // it is what makes the site's size a union rather than a sum, here as in the artifacts module.
    String binary = store(filled(64, (byte) 9));
    String font = store(filled(33, (byte) 10));
    String chunk = store(filled(21, (byte) 11));
    Instant now = Instant.now();

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (manifests.findOne("qits", IMAGE, manifestDigest).isEmpty()) {
                OciManifest manifest = new OciManifest();
                manifest.repository = "qits";
                manifest.imageName = IMAGE;
                manifest.digest = manifestDigest;
                manifest.mediaType = OciMediaTypes.OCI_MANIFEST_V1;
                manifest.size = document.length;
                manifest.createdAt = Instant.now();
                manifests.persist(manifest);
              }
              if (tags.findOne("qits", IMAGE, "v1").isEmpty()) {
                OciTag tag = new OciTag();
                tag.repository = "qits";
                tag.imageName = IMAGE;
                tag.tag = "v1";
                tag.manifestDigest = manifestDigest;
                tag.updatedAt = Instant.now();
                tags.persist(tag);
              }
              if (versions.findOne("npm", PACKAGE, "1.0.0").isEmpty()) {
                NpmVersion version = new NpmVersion();
                version.repository = "npm";
                version.packageName = PACKAGE;
                version.version = "1.0.0";
                version.tarballBlobId = tarball;
                version.manifestJson = "{}";
                version.createdAt = Instant.now();
                versions.persist(version);
              }
              if (distTags.findOne("npm", PACKAGE, "latest").isEmpty()) {
                NpmDistTag tag = new NpmDistTag();
                tag.repository = "npm";
                tag.packageName = PACKAGE;
                tag.tag = "latest";
                tag.version = "1.0.0";
                tag.updatedAt = Instant.now();
                distTags.persist(tag);
              }
              if (daemonBinaries.findOne("daemons", DAEMON, "2026.1.0").isEmpty()) {
                daemonBinaries.persist(
                    daemon("2026.1.0", binary, 64, now.minusSeconds(60)));
              }
              if (daemonBinaries.findOne("daemons", DAEMON, "2026.2.0").isEmpty()) {
                daemonBinaries.persist(
                    daemon("2026.2.0", binary, 64, now.minusSeconds(30)));
              }
              if (docsSites.findOne("docs", SITE, "1.0.0").isEmpty()) {
                docsSites.persist(site("1.0.0", 2, 33 + 21, now.minusSeconds(60)));
                docsFiles.persist(docsFile("1.0.0", "sb-assets/font.woff2", font, 33));
                docsFiles.persist(docsFile("1.0.0", "assets/a.js", chunk, 21));
              }
              if (docsSites.findOne("docs", SITE, "1.0.1").isEmpty()) {
                docsSites.persist(site("1.0.1", 1, 33, now.minusSeconds(30)));
                docsFiles.persist(docsFile("1.0.1", "sb-assets/font.woff2", font, 33));
              }
            });
  }

  @Test
  void theRepositoryListCarriesATypeACountAndASize() {
    // The endpoint that already existed, extended. `type` was always there; the two numbers are new
    // and are what makes the listing a landing page rather than a set of names.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.name", hasItem("qits"))
        .body("repositories.find { it.name == 'qits' }.type", is("oci-images"))
        .body("repositories.find { it.name == 'qits' }.itemCount", greaterThanOrEqualTo(1))
        .body("repositories.find { it.name == 'qits' }.sizeBytes", notNullValue())
        .body("repositories.itemCount", everyItem(greaterThanOrEqualTo(0)));
  }

  @Test
  void imagesAreListedForAnImageRepository() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/qits/images")
        .then()
        .statusCode(200)
        .body("images.name", hasItem(IMAGE))
        .body("images.find { it.name == '" + IMAGE + "' }.tagCount", is(1))
        .body("images.find { it.name == '" + IMAGE + "' }.manifestCount", is(1));
  }

  @Test
  void anImageNameWithASlashResolvesEncodedAndLiteralAlike() {
    // Half of why the template is `.+` rather than a single segment. The size is the manifest plus
    // its 5-byte config, which also proves the footprint reached the stored document.
    for (String spelling : new String[] {IMAGE.replace("/", "%2F"), IMAGE}) {
      given()
          .header("Authorization", SuiteBearers.operator())
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/api/repositories/qits/images/" + spelling + "/tags")
          .then()
          .statusCode(200)
          .body("tags", hasSize(1))
          .body("tags[0].tag", is("v1"))
          .body("tags[0].digest", startsWith("sha256:"))
          .body("tags[0].sizeBytes", greaterThanOrEqualTo(5))
          .body("tags[0].createdAt", notNullValue());
    }
  }

  @Test
  void manifestsExposeUntaggedReachabilityAndNullableAccess() {
    byte[] untaggedDocument =
        ("{\"schemaVersion\":2,\"mediaType\":\"" + OciMediaTypes.OCI_INDEX_V1 + "\",\"manifests\":[]}")
            .getBytes(StandardCharsets.UTF_8);
    String untaggedDigest = store(untaggedDocument);
    QuarkusTransaction.requiringNew().run(() -> {
      OciManifest row = new OciManifest();
      row.repository = "qits";
      row.imageName = IMAGE;
      row.digest = untaggedDigest;
      row.mediaType = OciMediaTypes.OCI_INDEX_V1;
      row.size = untaggedDocument.length;
      row.createdAt = Instant.now();
      manifests.persist(row);
    });
    given()
        .header("Authorization", SuiteBearers.operator())
        .urlEncodingEnabled(false)
        .queryParam("never-accessed", "true")
        .when()
        .get("/artifacts/api/repositories/qits/images/" + IMAGE + "/manifests")
        .then()
        .statusCode(200)
        .body("manifests", hasSize(greaterThanOrEqualTo(2)))
        .body("manifests.digest", hasItem("sha256:" + untaggedDigest))
        .body(
            "manifests.find { it.digest == 'sha256:" + untaggedDigest + "' }.tags",
            hasSize(0))
        .body("manifests.mediaType", everyItem(notNullValue()))
        .body("manifests.createdAt", everyItem(notNullValue()));
  }

  @Test
  void packagesAreListedForAnNpmRepository() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/npm/packages")
        .then()
        .statusCode(200)
        .body("packages.name", hasItem(PACKAGE))
        .body("packages.find { it.name == '" + PACKAGE + "' }.versionCount", is(1))
        .body("packages.find { it.name == '" + PACKAGE + "' }.latest", is("1.0.0"));
  }

  @Test
  void aScopedPackageNameResolvesEncodedAndLiteralAlike() {
    // The other half. npm itself sends the encoded spelling for a packument and the literal one in
    // the tarball urls it follows, so a browse API that accepted only one would be a third rule.
    for (String spelling : new String[] {PACKAGE.replace("/", "%2F"), PACKAGE}) {
      given()
          .header("Authorization", SuiteBearers.operator())
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/api/repositories/npm/packages/" + spelling + "/versions")
          .then()
          .statusCode(200)
          .body("versions", hasSize(1))
          .body("versions[0].version", is("1.0.0"))
          .body("versions[0].tarballSizeBytes", is(42))
          .body("versions[0].distTags", is(java.util.List.of("latest")))
          .body("versions[0].publishedAt", notNullValue());
    }
  }

  @Test
  void daemonsAreListedForTheDaemonRepository() {
    // The URL reads oddly — /repositories/daemons/daemons — and that is the design: the wire has no
    // repository segment (the seeded `daemons` row is the only namespace), but the explorer's
    // subject is a repository throughout, so this type gets no addressing scheme of its own.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/daemons/daemons")
        .then()
        .statusCode(200)
        .body("daemons.name", hasItem(DAEMON))
        .body("daemons.find { it.name == '" + DAEMON + "' }.versionCount", is(2))
        .body("daemons.find { it.name == '" + DAEMON + "' }.latestVersion", is("2026.2.0"))
        .body("daemons.find { it.name == '" + DAEMON + "' }.latestPublishedAt", notNullValue())
        // 64, not 128: both versions are the same bytes, so the daemon's size is their union.
        .body("daemons.find { it.name == '" + DAEMON + "' }.sizeBytes", is(64));
  }

  @Test
  void daemonVersionsAreNewestFirstAndCarryTheWireDigest() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/daemons/daemons/" + DAEMON + "/versions")
        .then()
        .statusCode(200)
        .body("versions", hasSize(2))
        .body("versions[0].version", is("2026.2.0"))
        .body("versions[0].digest", startsWith("sha256:"))
        .body("versions[0].sizeBytes", is(64))
        .body("versions[0].publishedAt", notNullValue())
        .body("versions[0].accessedAt", nullValue())
        .body("versions[1].version", is("2026.1.0"));

    // A daemon nobody published is an empty list, not a 404 — a daemon is not a row.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/daemons/daemons/never-published/versions")
        .then()
        .statusCode(200)
        .body("versions", hasSize(0));
  }

  @Test
  void docsSitesAreListedWithASizeTheOpenWireCatalogDoesNotCarry() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/docs/docs")
        .then()
        .statusCode(200)
        .body("sites.name", hasItem(SITE))
        .body("sites.find { it.name == '" + SITE + "' }.versionCount", is(2))
        .body("sites.find { it.name == '" + SITE + "' }.latestVersion", is("1.0.1"))
        .body("sites.find { it.name == '" + SITE + "' }.latestPublishedAt", notNullValue())
        // 54, not 87: both versions ship the font, and a site is the union of its versions.
        .body("sites.find { it.name == '" + SITE + "' }.sizeBytes", is(54));
  }

  @Test
  void aDocsSiteNameWithASlashResolvesEncodedAndLiteralAlike() {
    // The third name in this service that contains a slash, and the one whose WIRE accepts only the
    // literal spelling — DocsPaths has no percent-encoded separator. This surface is reached from a
    // browser, so it has to answer both, which is a property of the path template and nothing else.
    for (String spelling : new String[] {SITE.replace("/", "%2F"), SITE}) {
      given()
          .header("Authorization", SuiteBearers.operator())
          .urlEncodingEnabled(false)
          .when()
          .get("/artifacts/api/repositories/docs/docs/" + spelling + "/versions")
          .then()
          .statusCode(200)
          .body("versions", hasSize(2))
          .body("versions[0].version", is("1.0.1"))
          .body("versions[0].fileCount", is(1))
          .body("versions[0].sizeBytes", is(33))
          .body("versions[0].publishedAt", notNullValue())
          .body("versions[0].accessedAt", nullValue())
          .body("versions[0].metadata.'git.branch.name'", is("main"))
          .body("versions[1].version", is("1.0.0"))
          .body("versions[1].sizeBytes", is(54));
    }
  }

  @Test
  void anUnknownRepositoryIsFourOhFourOnBothBrowseSurfaces() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/images")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/packages")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/images/anything/tags")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/daemons")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/docs")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/daemons/anything/versions")
        .then()
        .statusCode(404);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/no-such-repo/docs/anything/versions")
        .then()
        .statusCode(404);
  }

  @Test
  void aWrongTypedRepositoryIsFourHundredRatherThanAnEmptyList() {
    // The distinction that matters to a client: an npm repository has no images and never will, so
    // an empty list would read as an image registry that lost its images.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/npm/images")
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/qits/packages")
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/npm/daemons")
        .then()
        .statusCode(400);
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/npm/docs")
        .then()
        .statusCode(400);
  }

  @Test
  void theStoreSummaryNamesEveryFigure() {
    // Values depend on whatever else this JVM's suite has pushed, so the assertion is the contract
    // rather than the arithmetic — that lives in the artifacts module, over a fixture it owns.
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/store/summary")
        .then()
        .statusCode(200)
        .body("ociPerImageSumBytes", greaterThanOrEqualTo(0))
        .body("ociUnionBytes", greaterThanOrEqualTo(0))
        .body("orphanBytes", greaterThanOrEqualTo(0))
        .body("npmPublishedBytes", greaterThanOrEqualTo(0))
        .body("npmProxyTarballBytes", greaterThanOrEqualTo(0))
        .body("npmProxyPackumentBytes", greaterThanOrEqualTo(0))
        .body("mavenPublishedBytes", greaterThanOrEqualTo(0))
        .body("daemonBinaryBytes", greaterThanOrEqualTo(0))
        .body("diskTotalBytes", greaterThanOrEqualTo(0));
  }

  private static DaemonBinary daemon(String version, String blobId, long size, Instant publishedAt) {
    DaemonBinary row = new DaemonBinary();
    row.repository = "daemons";
    row.name = DAEMON;
    row.version = version;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.publishedAt = publishedAt;
    return row;
  }

  private static DocsSite site(
      String version, int fileCount, long totalBytes, Instant publishedAt) {
    DocsSite row = new DocsSite();
    row.repository = "docs";
    row.name = SITE;
    row.version = version;
    row.fileCount = fileCount;
    row.totalBytes = totalBytes;
    row.publishedAt = publishedAt;
    row.metadata.put("git.branch.name", "main");
    return row;
  }

  private static DocsFile docsFile(String version, String path, String blobId, long size) {
    DocsFile row = new DocsFile();
    row.repository = "docs";
    row.name = SITE;
    row.version = version;
    row.path = path;
    row.blobId = blobId;
    row.sizeBytes = size;
    row.mediaType = "application/octet-stream";
    return row;
  }

  private String store(byte[] bytes) {
    BlobStore.StagedBlob staged = blobStore.stage(new ByteArrayInputStream(bytes), Long.MAX_VALUE);
    blobStore.promote(staged);
    return staged.sha256();
  }

  private static byte[] filled(int length, byte value) {
    byte[] bytes = new byte[length];
    Arrays.fill(bytes, value);
    return bytes;
  }
}
