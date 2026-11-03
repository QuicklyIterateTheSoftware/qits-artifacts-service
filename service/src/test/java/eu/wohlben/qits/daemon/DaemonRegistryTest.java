package eu.wohlben.qits.daemon;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.SuiteBearers;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The daemon-binaries wire, end to end.
 *
 * <p>Absolute paths throughout, deliberately: {@code /artifacts/daemons} is a literal in {@code
 * DaemonPaths} and no config key moves it, so — exactly like {@code GitHostTest}, {@code
 * RegistryTest}, {@code NpmRegistryTest} and {@code MavenRegistryTest} — this suite is the only
 * thing that would notice it drifting.
 *
 * <p>Every case names its own version. The service module's suite has no table reset between tests,
 * and versions here are immutable, so a shared coordinate would make these order-dependent in the
 * one way this registry is specifically designed to refuse.
 */
@QuarkusTest
class DaemonRegistryTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  @Inject DaemonBinaryRepository binaries;

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/daemons")
        .then()
        .statusCode(200);
  }

  // --- the round trip ---------------------------------------------------------------------------

  @Test
  void aBinaryPublishesAndDownloadsByItsVersion() {
    String version = version();
    byte[] binary = TinyDaemon.binary(version, 4096);

    try (DaemonClient daemons = client()) {
      HttpResponse<String> published = daemons.put("qits-ci-daemon", version, binary);
      assertEquals(201, published.statusCode(), published.body());

      // The response carries the computed digest — the value a release pipeline pastes into a
      // deployment, and the reason the publish needs no follow-up request to be useful.
      JsonObject body = new JsonObject(published.body());
      assertEquals("qits-ci-daemon", body.getString("name"));
      assertEquals(version, body.getString("version"));
      assertEquals("sha256:" + TinyDaemon.sha256(binary), body.getString("digest"));
      assertEquals(binary.length, body.getLong("sizeBytes"));
      assertTrue(body.getString("publishedAt") != null, "published_at is server-stamped");

      HttpResponse<byte[]> served = daemons.get("qits-ci-daemon", version);
      assertEquals(200, served.statusCode());
      assertArrayEquals(binary, served.body(), "sendFile must return the exact bytes");
      assertEquals(
          "sha256:" + TinyDaemon.sha256(binary),
          served.headers().firstValue("docker-content-digest").orElseThrow(),
          "the readable spelling still answers with the digest the other spelling addresses by");
      assertEquals(
          "public, max-age=31536000, immutable",
          served.headers().firstValue("cache-control").orElseThrow(),
          "a version is immutable, so its bytes are cacheable forever");

      // HEAD is the GET's twin, not a derivation: same status, same length, no body.
      HttpResponse<Void> head = daemons.head("qits-ci-daemon", version);
      assertEquals(200, head.statusCode());
      assertEquals(
          Long.toString(binary.length), head.headers().firstValue("content-length").orElseThrow());
    }
  }

  @Test
  void aChunkedPublishIsTheSamePublish() {
    // No Content-Length is the encoding a real `curl --upload-file` of a 43 MB binary uses, and the
    // one the global wire ceiling does not gate — so it is the shape the route's own cap has to
    // hold for. A publish test that only ever declared a length would prove nothing about it.
    String version = version();
    byte[] binary = TinyDaemon.binary(version, 8192);

    try (DaemonClient daemons = client()) {
      HttpResponse<String> published = daemons.putStreaming("qits-ci-daemon", version, binary);
      assertEquals(201, published.statusCode(), published.body());
      assertEquals(
          "sha256:" + TinyDaemon.sha256(binary),
          new JsonObject(published.body()).getString("digest"));
      assertArrayEquals(binary, daemons.get("qits-ci-daemon", version).body());
    }
  }

  // --- the rules --------------------------------------------------------------------------------

  @Test
  void republishingAVersionIsAConflict() {
    String version = version();
    byte[] binary = TinyDaemon.binary(version, 512);

    try (DaemonClient daemons = client()) {
      assertEquals(201, daemons.put("qits-ci-daemon", version, binary).statusCode());

      HttpResponse<String> again = daemons.put("qits-ci-daemon", version, binary);
      assertEquals(409, again.statusCode(), again.body());
      assertTrue(again.body().contains("immutable"), again.body());

      // Different bytes at the same version is the same answer, and the same one that matters: a
      // version pointer that could be re-aimed is what makes the readable download spelling unsafe.
      HttpResponse<String> different =
          daemons.put("qits-ci-daemon", version, TinyDaemon.binary(version + "x", 512));
      assertEquals(409, different.statusCode(), different.body());

      // And the first publish's bytes are still what the version serves.
      assertArrayEquals(binary, daemons.get("qits-ci-daemon", version).body());
    }
  }

  @Test
  void anUnpublishedVersionIsFourOhFourAndNeverTheSpasHtml() {
    try (DaemonClient daemons = client()) {
      HttpResponse<byte[]> missing = daemons.get("qits-ci-daemon", version());
      assertEquals(404, missing.statusCode());
      assertTrue(
          missing.headers().firstValue("content-type").orElseThrow().startsWith("text/plain"),
          "a bootstrap script that pipes 200 text/html into a file gets an executable that is a"
              + " web page");
    }
  }

  @Test
  void deleteIsRefusedRatherThanUnimplementedQuietly() {
    // 405 rather than 404: the store is append-only and retiring a version is the GC strategy's
    // job. A 404 would read as "unknown daemon" and send a publisher looking for the wrong bug.
    try (DaemonClient daemons = client()) {
      assertEquals(405, daemons.delete("qits-ci-daemon", version()).statusCode());
    }
  }

  @Test
  void aPathUnderTheBaseThatMatchesNoRouteIsAPlainTextFourOhFour() {
    try (DaemonClient daemons = client()) {
      HttpResponse<String> stray = daemons.getAbsolute("/artifacts/daemons/qits-ci-daemon");
      assertEquals(404, stray.statusCode());
      assertTrue(stray.body().contains("not a route"), stray.body());
    }
  }

  @Test
  void theDigestAddressedBlobRouteIsUntouched() {
    // ⚖2, as an assertion. The launcher, the URL template and every existing pin resolve through
    // /v2/<repo>/blobs/sha256:<hex>, and this workstream changed nothing there — a daemon published
    // here is reachable by BOTH spellings because the bytes are one blob in one global store.
    String version = version();
    byte[] binary = TinyDaemon.binary(version, 1024);
    String digest = TinyDaemon.sha256(binary);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType("application/json")
        .body("{\"type\":\"oci-images\"}")
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);

    try (DaemonClient daemons = client()) {
      assertEquals(201, daemons.put("qits-ci-daemon", version, binary).statusCode());
    }

    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/qits/ci-daemon/blobs/sha256:" + digest)
        .then()
        .statusCode(200);
  }

  @Test
  void theVersionAddressedReadTouchesTheRowAndTheDigestAddressedOneDeliberatelyDoesNot() {
    // The daemon half of the GC's access basis, and the one asymmetry in it. The /v2 blob route
    // resolves an OCI repository and a globally deduplicated digest, so the request names no daemon
    // — attributing it to a daemon_binary row would be the cross-repository attribution that layer
    // reads are refused for. A digest-fetched daemon is kept alive by its live pin, not by this
    // column.
    String version = version();
    byte[] binary = TinyDaemon.binary(version, 1024);
    String digest = TinyDaemon.sha256(binary);

    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType("application/json")
        .body("{\"type\":\"oci-images\"}")
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);

    try (DaemonClient daemons = client()) {
      assertEquals(201, daemons.put("qits-ci-daemon", version, binary).statusCode());
      binaries.getEntityManager().clear();
      assertNull(
          binaries.findOne("daemons", "qits-ci-daemon", version).orElseThrow().accessedAt,
          "a publish is not an access");

      given()
          .urlEncodingEnabled(false)
          .when()
          .get("/v2/qits/ci-daemon/blobs/sha256:" + digest)
          .then()
          .statusCode(200);
      binaries.getEntityManager().clear();
      assertNull(
          binaries.findOne("daemons", "qits-ci-daemon", version).orElseThrow().accessedAt,
          "the digest-addressed download carries no daemon identity and must record nothing");

      assertEquals(200, daemons.get("qits-ci-daemon", version).statusCode());
      binaries.getEntityManager().clear();
      Instant first = binaries.findOne("daemons", "qits-ci-daemon", version).orElseThrow().accessedAt;
      assertTrue(first != null, "the version-addressed GET must record the access");

      assertEquals(200, daemons.get("qits-ci-daemon", version).statusCode());
      assertEquals(200, daemons.head("qits-ci-daemon", version).statusCode());
      binaries.getEntityManager().clear();
      assertEquals(
          first,
          binaries.findOne("daemons", "qits-ci-daemon", version).orElseThrow().accessedAt,
          "writes are coalesced to one per row per hour");
    }
  }

  private static String version() {
    return "2026.801." + UNIQUE.incrementAndGet() + "0000";
  }

  private DaemonClient client() {
    return new DaemonClient(URI.create(root.toString()));
  }
}
