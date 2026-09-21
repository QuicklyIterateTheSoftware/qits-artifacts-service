package eu.wohlben.qits.sbom;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An anonymous SBOM publish is <b>refused</b> with the machine-token gate on — the twin of
 * {@code registry/RegistryOpenPushTest} and {@code daemon/DaemonOpenPublishTest}, flipped on the
 * same day (2026-09-20). It used to assert the opposite, the known gap of the CI-only publish rule
 * (USER RULING 2026-09-13); the composed postlude submits SBOMs through {@code qits-publish sbom
 * submit} with the run's credential now, so nothing left on this surface writes anonymously.
 *
 * <p><b>A plain 401, with no {@code WWW-Authenticate: Bearer}</b>: nothing here speaks the docker
 * token dance, so a challenge would name a door nobody knocks on. Reads stay open — a consumer
 * verifying a release's SBOM is not publishing.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class SbomOpenPublishTest {

  private static final String NAME = "eu.wohlben.qits:qits-eventstream";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .contentType("application/json")
        .body("{\"type\":\"sboms\"}")
        .when()
        .put("/artifacts/api/repositories/sboms")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishIsRefusedWithoutAChallenge() {
    byte[] document = TinySbom.document(NAME, "open-publish");
    try (SbomClient sboms = client()) {
      HttpResponse<String> refused = sboms.put("maven", NAME, "open-publish", document);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(
          refused.headers().allValues("WWW-Authenticate").stream()
              .noneMatch(value -> value.regionMatches(true, 0, "Bearer", 0, 6)),
          "a plain refusal, not a token-dance challenge: " + refused.headers());

      // Refused means nothing was stored.
      assertEquals(404, sboms.head("maven", NAME, "open-publish").statusCode());
    }
  }

  @Test
  void aCiRunPublishesAndAnybodyReads() {
    byte[] document = TinySbom.document(NAME, "ci-publish");
    try (SbomClient sboms = client()) {
      HttpResponse<String> published =
          sboms.putAuthorized("maven", NAME, "ci-publish", document, MachineTokens.forCiRun());
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("sha256:" + TinySbom.sha256(document)), published.body());

      HttpResponse<byte[]> served = sboms.get("maven", NAME, "ci-publish");
      assertEquals(200, served.statusCode());
      assertEquals(document.length, served.body().length);
      assertEquals(200, sboms.head("maven", NAME, "ci-publish").statusCode());
    }
  }

  @Test
  void aStrayBearerIsStillNoIdentityAndNeverReachesOidc() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, and
    // PublishGuard never hands a bearer that is not three base64url segments to OIDC. Such a caller
    // is now refused — but as the anonymous publisher, by this guard, which is visible in the body:
    // the guard's own sentence, not a token-validation failure.
    byte[] document = TinySbom.document(NAME, "stray-bearer");
    try (SbomClient sboms = client()) {
      HttpResponse<String> refused =
          sboms.putAuthorized("maven", NAME, "stray-bearer", document, "qits-ci");
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("only a CI run publishes"), refused.body());
      assertNull(refused.headers().firstValue("WWW-Authenticate").orElse(null));
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the sbom publish closing must not have moved
    // anything else.
    given()
        .contentType("application/json")
        .body("{\"type\":\"sboms\"}")
        .when()
        .put("/artifacts/api/repositories/unauthenticated-sboms")
        .then()
        .statusCode(401);
  }

  private SbomClient client() {
    return new SbomClient(URI.create(root.toString()));
  }
}
