package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import eu.wohlben.qits.SuiteBearers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BlobControllerTest {

  @BeforeEach
  void ensureRepo() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/ci-screenshots")
        .then()
        .statusCode(200);
  }

  private io.restassured.specification.RequestSpecification upload(
      String branch, String flow, byte[] png) {
    return given()
        .header("Authorization", SuiteBearers.ciRun())
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders(branch, flow, 100, 50))
        .body(png);
  }

  @Test
  void acceptsUploadsLargerThanThe10mDefaultWireLimit() {
    // The service raises quarkus.http.limits.max-body-size (10M default) to accommodate media
    // uploads; an 11 MB blob must therefore be accepted (a plain default would 413 it at the HTTP
    // layer). Regression guard for the sizing (docs/issues on the max-body-size tradeoff).
    byte[] big = Arrays.copyOf(ArtifactsTestMedia.png(100, 50, 99), 11 * 1024 * 1024);
    upload("main", "big", big)
        .when()
        .post("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(201);
  }

  @Test
  void thePerTypeCapStillFiresWellBelowTheRaisedGlobalCeiling() {
    // The other half of the test above, and the half the OCI registry's ceiling raise threatened.
    // 26 MB is over ci-screenshots' 25 MB type cap but far under the 1088M wire ceiling, so the
    // only thing that can reject it is BlobStore's own cap.
    //
    // The assertion that matters is the BODY, not the status: both rejections are a 413, but a
    // wire-level one (Quarkus' order -2 route, or VertxInputStream) answers with an empty body,
    // while BlobStore's cap comes back through ArtifactsExceptionMapper with a message. Only the
    // second proves the two limits are still independent — which is exactly what M0 had to
    // establish before the ceiling could move.
    byte[] overCap = Arrays.copyOf(ArtifactsTestMedia.png(100, 50, 7), 26 * 1024 * 1024);
    upload("main", "over-cap", overCap)
        .when()
        .post("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(413)
        .body("message", containsString("size cap"));
  }

  @Test
  void uploadServeAndQueryRoundTrip() {
    byte[] png = ArtifactsTestMedia.png(100, 50, 1);
    String id =
        upload("main", "checkout", png)
            .when()
            .post("/artifacts/api/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // Serve: exact bytes, stored mediatype, immutable cache header.
    byte[] served =
        given()
            .header("Authorization", SuiteBearers.operator())
            .when()
            .get("/artifacts/api/repositories/ci-screenshots/blobs/" + id)
            .then()
            .statusCode(200)
            .contentType("image/png")
            .header("Cache-Control", containsString("immutable"))
            .extract()
            .asByteArray();
    assertArrayEquals(png, served);

    // Query by branch, latest collapse.
    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("meta.git.branch.name", "main")
        .queryParam("latest", "true")
        .when()
        .get("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(200)
        .body("records.id", hasItem(id))
        .body("records.mediatype", hasItem("image/png"))
        .body("records.accessedAt", hasItem(notNullValue()));
  }

  @Test
  void invalidTypedFilterIs400() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .queryParam("accessed-before", "not-an-instant")
        .when()
        .get("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(400)
        .body("message", containsString("ISO-8601"));
  }

  @Test
  void sniffOverridesALyingContentTypeHeader() {
    byte[] png = ArtifactsTestMedia.png(100, 50, 2);
    String id =
        given()
            .header("Authorization", SuiteBearers.ciRun())
            .contentType("image/jpeg") // lie — bytes are PNG
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(png)
            .when()
            .post("/artifacts/api/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/ci-screenshots/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void missingRequiredMetadataKeyIs400() {
    var headers = ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50);
    headers.remove("X-Artifacts-Meta-qits.diff.hash");
    given()
        .header("Authorization", SuiteBearers.ciRun())
        .contentType("image/png")
        .headers(headers)
        .body(ArtifactsTestMedia.png(100, 50, 3))
        .when()
        .post("/artifacts/api/repositories/ci-screenshots/blobs")
        .then()
        .statusCode(400);
  }

  @Test
  void uploadToUnknownRepositoryIs404() {
    upload("main", "checkout", ArtifactsTestMedia.png(100, 50, 4))
        .when()
        .post("/artifacts/api/repositories/nope/blobs")
        .then()
        .statusCode(404);
  }

  @Test
  void serveUnknownBlobIs404() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get(
            "/artifacts/api/repositories/ci-screenshots/blobs/"
                + "0000000000000000000000000000000000000000000000000000000000000000")
        .then()
        .statusCode(404);
  }

  @Test
  void serveMalformedIdIs404NotATraversal() {
    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories/ci-screenshots/blobs/not-a-sha")
        .then()
        .statusCode(404);
  }
}
