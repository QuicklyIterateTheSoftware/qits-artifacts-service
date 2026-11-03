package eu.wohlben.qits.daemon;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.SuiteBearers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The route's own size cap, which is the whole reason this wire uses no {@code BodyHandler}.
 *
 * <p>{@code BodyHandler.create()} is not unlimited — vertx-web defaults it to <b>10 MiB</b>, and the
 * ci-daemon binary is <b>43 MB</b>. A publish route built the obvious way would therefore have
 * 413'd every real publish while passing every test small enough to be quick, which is the git
 * host's {@code max-pack-size} failure verbatim. {@code DaemonRoutes} streams through {@code
 * OciRequestBody} instead, and {@code qits.artifacts.daemon.max-binary-size} is the only bound a
 * chunked upload has: the global {@code quarkus.http.limits.max-body-size} gates a declared {@code
 * Content-Length} only.
 *
 * <p>The cap is asserted by <b>lowering</b> it rather than by uploading 256 MB, so the suite stays
 * fast and the assertion stays about the knob. A separate class because a {@code @TestProfile} is
 * per class, and because the shipped default is what every other case here must run under.
 */
@QuarkusTest
@TestProfile(DaemonBinaryCapTest.TinyCap.class)
class DaemonBinaryCapTest {

  /** Small enough that a few kilobytes is oversized, large enough that a normal case still fits. */
  public static class TinyCap implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.daemon.max-binary-size", "1K");
    }
  }

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

  @Test
  void aBinaryOverTheCapIsRefusedAndPublishesNothing() {
    try (DaemonClient daemons = new DaemonClient(URI.create(root.toString()))) {
      HttpResponse<String> refused =
          daemons.put("qits-ci-daemon", "oversized", TinyDaemon.binary("oversized", 8192));
      assertEquals(413, refused.statusCode(), refused.body());

      // The refusal happens while STAGING, before promote and before the row — so an oversized
      // publish leaves no identity behind and the version stays free.
      assertEquals(404, daemons.get("qits-ci-daemon", "oversized").statusCode());
    }
  }

  @Test
  void aChunkedBinaryOverTheCapIsRefusedToo() {
    // The encoding that matters. With no Content-Length the global ceiling does not gate at all, so
    // if this route ever stopped reading through OciRequestBody the cap would silently vanish for
    // exactly the upload shape a real publish uses.
    try (DaemonClient daemons = new DaemonClient(URI.create(root.toString()))) {
      HttpResponse<String> refused =
          daemons.putStreaming("qits-ci-daemon", "oversized-chunked", TinyDaemon.binary("c", 8192));
      assertEquals(413, refused.statusCode(), refused.body());
    }
  }

  @Test
  void aBinaryUnderTheCapStillPublishes() {
    // The other half: the cap is a cap, not a broken route.
    try (DaemonClient daemons = new DaemonClient(URI.create(root.toString()))) {
      HttpResponse<String> published =
          daemons.put("qits-ci-daemon", "small", TinyDaemon.binary("small", 512));
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("\"digest\":\"sha256:"), published.body());
    }
  }
}
