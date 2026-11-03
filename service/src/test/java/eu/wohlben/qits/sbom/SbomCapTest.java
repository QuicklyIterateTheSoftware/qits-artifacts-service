package eu.wohlben.qits.sbom;

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
 * <p>{@code BodyHandler.create()} is not unlimited — vertx-web defaults it to <b>10 MiB</b> with
 * nothing in the log to say which limit refused a request. {@code SbomRoutes} reads through {@code
 * OciRequestBody} into a bounded buffer instead, and {@code qits.artifacts.sbom.max-size} is the
 * only bound a <b>chunked</b> upload has at all: the global {@code
 * quarkus.http.limits.max-body-size} gates a declared {@code Content-Length} and nothing else.
 *
 * <p>The cap is asserted by <b>lowering</b> it rather than by uploading 16 MB, so the suite stays
 * fast and the assertion stays about the knob. A separate class because a {@code @TestProfile} is
 * per class, and because the shipped default is what every other case here must run under.
 */
@QuarkusTest
@TestProfile(SbomCapTest.TinyCap.class)
class SbomCapTest {

  /** Small enough that a padded document is oversized, large enough that a minimal one still fits. */
  public static class TinyCap implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.artifacts.sbom.max-size", "1K");
    }
  }

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

  @Test
  void aDocumentOverTheCapIsRefusedAndPublishesNothing() {
    try (SbomClient sboms = new SbomClient(URI.create(root.toString()))) {
      HttpResponse<String> refused =
          sboms.put(
              "maven",
              "eu.wohlben.qits:oversized",
              "1.0.0",
              TinySbom.padded("eu.wohlben.qits:oversized", "1.0.0", 20));
      assertEquals(413, refused.statusCode(), refused.body());
      assertTrue(
          refused.body().contains("qits.artifacts.sbom.max-size"),
          "the refusal names the knob that refused it: " + refused.body());

      // The refusal happens while READING, before the parse, before promote and before the row — so
      // an oversized publish leaves no identity behind and the version stays free.
      assertEquals(404, sboms.get("maven", "eu.wohlben.qits:oversized", "1.0.0").statusCode());
    }
  }

  @Test
  void aChunkedDocumentOverTheCapIsRefusedToo() {
    // The encoding that matters. With no Content-Length the global ceiling does not gate at all, so
    // if this route ever stopped reading through OciRequestBody the cap would silently vanish for
    // exactly the upload shape a piped publish uses.
    try (SbomClient sboms = new SbomClient(URI.create(root.toString()))) {
      HttpResponse<String> refused =
          sboms.putStreaming(
              "npm",
              "@qits/oversized-chunked",
              "1.0.0",
              TinySbom.padded("@qits/oversized-chunked", "1.0.0", 20));
      assertEquals(413, refused.statusCode(), refused.body());

      assertEquals(404, sboms.get("npm", "@qits/oversized-chunked", "1.0.0").statusCode());
    }
  }

  @Test
  void aDocumentUnderTheCapStillPublishes() {
    // The other half: the cap is a cap, not a broken route.
    try (SbomClient sboms = new SbomClient(URI.create(root.toString()))) {
      HttpResponse<String> published =
          sboms.put("daemon", "qits-ci-daemon", "1.0.0", TinySbom.document("qits-ci-daemon", "1.0.0"));
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("\"digest\":\"sha256:"), published.body());
    }
  }
}
