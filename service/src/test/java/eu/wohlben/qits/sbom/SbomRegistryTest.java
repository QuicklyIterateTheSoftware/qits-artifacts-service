package eu.wohlben.qits.sbom;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.SuiteBearers;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SBOM wire, end to end.
 *
 * <p>Absolute paths throughout, deliberately: {@code /artifacts/sboms} is a literal in {@code
 * SbomPaths} and no config key moves it, so — exactly like {@code RegistryTest}, {@code
 * NpmRegistryTest}, {@code MavenRegistryTest}, {@code DaemonRegistryTest} and {@code
 * DocsRegistryTest} — this suite is the only thing that would notice it drifting.
 *
 * <p>Every case names its own version. The service module's suite has no table reset between tests
 * and no blob wipe, and a stored document is immutable, so a shared coordinate would make these
 * order-dependent in exactly the way first-write-wins is designed to absorb — which would hide the
 * one case that is <em>about</em> a second write.
 *
 * <p>The three name shapes are the point of half of it: a maven {@code packageName} carries a colon
 * inside one segment, an npm one carries a scope, a docker one is two plain segments. All three
 * travel from {@code SoftwareRelease} verbatim, so all three have to resolve literally.
 */
@QuarkusTest
class SbomRegistryTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  /** The three real name shapes, as the release event spells them. */
  private static final String MAVEN_NAME = "eu.wohlben.qits:qits-eventstream";

  private static final String NPM_NAME = "@qits/ui-components";
  private static final String DOCKER_NAME = "qits/qits-artifacts";

  @Inject SbomDocumentRepository documents;

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType("application/json")
        .body("{\"type\":\"sboms\"}")
        .when()
        .put("/artifacts/api/repositories/sboms")
        .then()
        .statusCode(200);
  }

  // --- the round trip ---------------------------------------------------------------------------

  @Test
  void aMavenCoordinateWithAColonPublishesAndReadsBackByteForByte() {
    // The name shape no other wire in this service carries: groupId AND artifactId in ONE path
    // segment, because that is how SoftwareRelease travels it. Re-spelling it as two segments here
    // would make this the one place the release identity is written differently.
    String version = version();
    byte[] document = TinySbom.document(MAVEN_NAME, version);

    try (SbomClient sboms = client()) {
      HttpResponse<String> published = sboms.put("maven", MAVEN_NAME, version, document);
      assertEquals(201, published.statusCode(), published.body());

      JsonObject body = new JsonObject(published.body());
      assertEquals("maven", body.getString("packageType"));
      assertEquals(MAVEN_NAME, body.getString("packageName"), "the colon survives the route");
      assertEquals(version, body.getString("version"));
      assertEquals("sha256:" + TinySbom.sha256(document), body.getString("digest"));
      assertEquals(document.length, body.getLong("sizeBytes"));
      assertEquals("1.6", body.getString("specVersion"));
      assertTrue(body.getString("createdAt") != null, "created_at is server-stamped");

      HttpResponse<byte[]> served = sboms.get("maven", MAVEN_NAME, version);
      assertEquals(200, served.statusCode());
      assertArrayEquals(document, served.body(), "the store must return the exact bytes");
      assertEquals(
          "application/vnd.cyclonedx+json; version=1.6",
          served.headers().firstValue("content-type").orElseThrow(),
          "the document's own specVersion parameterises the media type");
      assertEquals(
          "sha256:" + TinySbom.sha256(document),
          served.headers().firstValue("docker-content-digest").orElseThrow(),
          "a consumer verifies the document it just read without a second request");
      assertEquals(
          "\"" + TinySbom.sha256(document) + "\"",
          served.headers().firstValue("etag").orElseThrow());
      assertEquals(
          "public, max-age=31536000, immutable",
          served.headers().firstValue("cache-control").orElseThrow(),
          "a stored document can never change, so its bytes are cacheable forever");
    }
  }

  @Test
  void aScopedNpmNameAndATwoSegmentDockerNameResolveLiterally() {
    // Multi-segment names are what the /-/ separator exists for: without it @qits/ui-components/1.0
    // could be split three ways, and the grammar would have to guess.
    String version = version();

    try (SbomClient sboms = client()) {
      byte[] npm = TinySbom.document(NPM_NAME, version);
      assertEquals(201, sboms.put("npm", NPM_NAME, version, npm).statusCode());
      HttpResponse<byte[]> servedNpm = sboms.get("npm", NPM_NAME, version);
      assertEquals(200, servedNpm.statusCode());
      assertArrayEquals(npm, servedNpm.body());

      byte[] docker = TinySbom.document(DOCKER_NAME, version);
      assertEquals(201, sboms.put("docker", DOCKER_NAME, version, docker).statusCode());
      HttpResponse<byte[]> servedDocker = sboms.get("docker", DOCKER_NAME, version);
      assertEquals(200, servedDocker.statusCode());
      assertArrayEquals(docker, servedDocker.body());

      // Same version, three types, three documents — the package type is part of the identity, not
      // a hint, so npm and docker never collide however alike their names look.
      assertEquals(NPM_NAME, new JsonObject(sboms.get("npm", NPM_NAME).body()).getString("packageName"));
      assertEquals(
          DOCKER_NAME, new JsonObject(sboms.get("docker", DOCKER_NAME).body()).getString("packageName"));
    }
  }

  @Test
  void aHeadCarriesTheSameContentLengthAsTheGet() {
    // HEAD is the GET's twin, not a derivation: Vert.x does not derive one, so a missing twin is a
    // 404 for every client that probes before downloading.
    String version = version();
    byte[] document = TinySbom.document(DOCKER_NAME, version);

    try (SbomClient sboms = client()) {
      assertEquals(201, sboms.put("docker", DOCKER_NAME, version, document).statusCode());

      HttpResponse<Void> head = sboms.head("docker", DOCKER_NAME, version);
      assertEquals(200, head.statusCode());
      assertEquals(
          Long.toString(document.length),
          head.headers().firstValue("content-length").orElseThrow());
      assertEquals(
          "application/vnd.cyclonedx+json; version=1.6",
          head.headers().firstValue("content-type").orElseThrow());
    }
  }

  @Test
  void aChunkedPublishIsTheSamePublish() {
    // No Content-Length is what a piped `curl --upload-file` sends, and the encoding the global wire
    // ceiling does not gate at all — so it is the shape the route's own cap has to hold for. A
    // publish test that only ever declared a length would prove nothing about it.
    String version = version();
    byte[] document = TinySbom.document(NPM_NAME, version);

    try (SbomClient sboms = client()) {
      HttpResponse<String> published = sboms.putStreaming("npm", NPM_NAME, version, document);
      assertEquals(201, published.statusCode(), published.body());
      assertEquals(
          "sha256:" + TinySbom.sha256(document),
          new JsonObject(published.body()).getString("digest"));
      assertArrayEquals(document, sboms.get("npm", NPM_NAME, version).body());
    }
  }

  // --- the rules --------------------------------------------------------------------------------

  @Test
  void republishingAnIdentityConvergesRatherThanConflicting() {
    // The one deliberate difference from the daemon 409. This PUT sits INSIDE a release run, one
    // step after the artifact publish, and release steps are replayed as a matter of course — a 409
    // would turn every replay of a green release into a red one over a document already exactly
    // where it belongs. What still holds, and is the property that matters, is that the stored
    // document can never CHANGE.
    String version = version();
    byte[] first = TinySbom.document(MAVEN_NAME, version);
    byte[] second = TinySbom.padded(MAVEN_NAME, version, 3);

    try (SbomClient sboms = client()) {
      assertEquals(201, sboms.put("maven", MAVEN_NAME, version, first).statusCode());

      HttpResponse<String> again = sboms.put("maven", MAVEN_NAME, version, second);
      assertEquals(200, again.statusCode(), again.body());
      JsonObject body = new JsonObject(again.body());
      assertTrue(body.getBoolean("alreadyPublished"), again.body());
      assertEquals(
          "sha256:" + TinySbom.sha256(first),
          body.getString("digest"),
          "the answer describes the row that STANDS, never the bytes just discarded");
      assertEquals(first.length, body.getLong("sizeBytes"));

      // And the first publish's bytes are still what the identity serves.
      assertArrayEquals(first, sboms.get("maven", MAVEN_NAME, version).body());
    }
  }

  @Test
  void aBodyThatIsNotACycloneDxDocumentIsRefusedBeforeAnythingIsStored() {
    String version = version();

    try (SbomClient sboms = client()) {
      HttpResponse<String> notJson =
          sboms.put("npm", NPM_NAME, version, "this is not json".getBytes(StandardCharsets.UTF_8));
      assertEquals(400, notJson.statusCode(), notJson.body());
      assertTrue(notJson.body().contains("not a JSON document"), notJson.body());

      // A JSON document that is not a bill of materials. SPDX would land here, which is the case
      // this check exists for: one store, one format, so a reader never has to sniff.
      byte[] wrongFormat =
          TinySbom.bytes(TinySbom.json(NPM_NAME, version).put("bomFormat", "SPDX"));
      HttpResponse<String> refused = sboms.put("npm", NPM_NAME, version, wrongFormat);
      assertEquals(400, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("CycloneDX"), refused.body());

      byte[] oldSpec = TinySbom.bytes(TinySbom.json(NPM_NAME, version).put("specVersion", "1.3"));
      HttpResponse<String> tooOld = sboms.put("npm", NPM_NAME, version, oldSpec);
      assertEquals(400, tooOld.statusCode(), tooOld.body());
      assertTrue(tooOld.body().contains("1.4, 1.5, 1.6"), tooOld.body());

      // None of the three wrote a row: the document is parsed before a byte is staged, so a
      // malformed publish leaves neither an identity nor a row-less blob behind.
      assertEquals(404, sboms.get("npm", NPM_NAME, version).statusCode());
    }
  }

  @Test
  void anUnknownPackageTypeIsAFourHundredNamingTheAllowedSet() {
    // 400 and not 404, and the grammar is loose on purpose so it can be: a 404 would read as "no
    // such route" and send a publisher looking for a mount point rather than at its own spelling.
    String version = version();

    try (SbomClient sboms = client()) {
      HttpResponse<String> refused =
          sboms.put("gem", "sinatra", version, TinySbom.document("sinatra", version));
      assertEquals(400, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("daemon, docker, maven, npm"), refused.body());

      // The reads answer the same way — a misspelled type must not mean "unknown type" from one
      // verb and "not found" from another.
      assertEquals(400, sboms.get("gem", "sinatra", version).statusCode());
      assertEquals(400, sboms.get("gem", "sinatra").statusCode());
    }
  }

  @Test
  void anUnpublishedVersionIsAPlainTextFourOhFourAndNeverTheSpasHtml() {
    try (SbomClient sboms = client()) {
      HttpResponse<byte[]> missing = sboms.get("maven", MAVEN_NAME, version());
      assertEquals(404, missing.statusCode());
      assertTrue(
          missing.headers().firstValue("content-type").orElseThrow().startsWith("text/plain"),
          "a pipeline step that reads a 200 text/html as its answer reports a publish that never"
              + " happened");
    }
  }

  @Test
  void deleteIsRefusedRatherThanUnimplementedQuietly() {
    // 405 rather than 404: the store is append-only and retiring a document is the GC strategy's
    // job. A 404 would read as "unknown package" and send a publisher looking for the wrong bug.
    try (SbomClient sboms = client()) {
      assertEquals(405, sboms.delete("maven", MAVEN_NAME, version()).statusCode());
    }
  }

  @Test
  void aPathUnderTheBaseThatMatchesNoRouteIsAPlainTextFourOhFour() {
    try (SbomClient sboms = client()) {
      HttpResponse<String> typeAlone = sboms.getAbsolute("/artifacts/sboms/maven");
      assertEquals(404, typeAlone.statusCode());
      assertTrue(typeAlone.body().contains("not a route"), typeAlone.body());
      assertTrue(
          typeAlone.headers().firstValue("content-type").orElseThrow().startsWith("text/plain"),
          typeAlone.body());

      HttpResponse<String> base = sboms.getAbsolute("/artifacts/sboms");
      assertEquals(404, base.statusCode());
      assertTrue(base.body().contains("not a route"), base.body());

      // Five name segments is past the cap, so it matches no route rather than matching a route
      // with a truncated name.
      HttpResponse<String> tooDeep = sboms.getAbsolute("/artifacts/sboms/npm/a/b/c/d/e");
      assertEquals(404, tooDeep.statusCode());
      assertTrue(tooDeep.body().contains("not a route"), tooDeep.body());
    }
  }

  // --- the listing ------------------------------------------------------------------------------

  @Test
  void theListingIsNewestFirstAndAnUnknownPackageIsAFourOhFour() {
    String name = "qits/listing-" + UNIQUE.incrementAndGet();
    String older = version();
    String newer = version();

    try (SbomClient sboms = client()) {
      assertEquals(404, sboms.get("docker", name).statusCode(), "no row means no such package");

      assertEquals(201, sboms.put("docker", name, older, TinySbom.document(name, older)).statusCode());
      assertEquals(201, sboms.put("docker", name, newer, TinySbom.document(name, newer)).statusCode());

      HttpResponse<String> listed = sboms.get("docker", name);
      assertEquals(200, listed.statusCode(), listed.body());
      JsonObject body = new JsonObject(listed.body());
      assertEquals("docker", body.getString("packageType"));
      assertEquals(name, body.getString("packageName"));

      JsonArray versions = body.getJsonArray("versions");
      assertEquals(2, versions.size());
      assertEquals(
          newer,
          versions.getJsonObject(0).getString("version"),
          "the service orders newest first and nothing here re-sorts");
      assertEquals(older, versions.getJsonObject(1).getString("version"));

      JsonObject first = versions.getJsonObject(0);
      assertEquals("1.6", first.getString("specVersion"));
      assertEquals(
          "sha256:" + TinySbom.sha256(TinySbom.document(name, newer)), first.getString("digest"));
      assertEquals(TinySbom.document(name, newer).length, first.getLong("sizeBytes"));
      assertTrue(first.getString("createdAt") != null);
    }
  }

  // --- the access basis -------------------------------------------------------------------------

  @Test
  void theReadMovesAccessedAtAndAPublishAloneDoesNot() {
    // The SBOM half of the GC's access basis. qits-platform-maintenance re-reads a live artifact's
    // document on a schedule, and that read is what keeps it out of the window — so a publish that
    // counted as an access would make a document nobody ever reads look exactly as wanted as one
    // that is read hourly.
    String version = version();
    byte[] document = TinySbom.document(DOCKER_NAME, version);

    try (SbomClient sboms = client()) {
      assertEquals(201, sboms.put("docker", DOCKER_NAME, version, document).statusCode());
      documents.getEntityManager().clear();
      assertNull(
          documents.findOne("sboms", "docker", DOCKER_NAME, version).orElseThrow().accessedAt,
          "a publish is not an access");

      assertEquals(200, sboms.get("docker", DOCKER_NAME, version).statusCode());
      documents.getEntityManager().clear();
      Instant first =
          documents.findOne("sboms", "docker", DOCKER_NAME, version).orElseThrow().accessedAt;
      assertTrue(first != null, "the GET must record the access");

      assertEquals(200, sboms.get("docker", DOCKER_NAME, version).statusCode());
      assertEquals(200, sboms.head("docker", DOCKER_NAME, version).statusCode());
      documents.getEntityManager().clear();
      assertEquals(
          first,
          documents.findOne("sboms", "docker", DOCKER_NAME, version).orElseThrow().accessedAt,
          "writes are coalesced to one per row per hour — a scan touching a thousand rows must not"
              + " be a write per request per scan");
    }
  }

  private static String version() {
    return "2026.901." + UNIQUE.incrementAndGet() + "0000";
  }

  private SbomClient client() {
    return new SbomClient(URI.create(root.toString()));
  }
}
