package eu.wohlben.qits.sbom;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * An anonymous SBOM publish still lands with the machine-token gate on — the known gap of the
 * CI-only publish rule (USER RULING 2026-09-13), the twin of {@code registry/RegistryOpenPushTest}.
 * CI release steps submit SBOMs with no credential today, so {@code PublishGuard} judges only an
 * identity that is presented ({@code artifacts/api/PublishGuardTest}).
 *
 * <p><b>What stands in for write auth meanwhile.</b> A stored document is immutable: a re-PUT of an
 * identity stores nothing and answers {@code 200 alreadyPublished}, so an open publish can add a
 * document and can never change one. Consumers verify against the digest this route echoes.
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
        .header("Authorization", "Bearer " + MachineTokens.forThisService())
        .contentType("application/json")
        .body("{\"type\":\"sboms\"}")
        .when()
        .put("/artifacts/api/repositories/sboms")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishAndReadRoundTripDespiteTheGate() {
    byte[] document = TinySbom.document(NAME, "open-publish");
    try (SbomClient sboms = client()) {
      HttpResponse<String> published = sboms.put("maven", NAME, "open-publish", document);
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("sha256:" + TinySbom.sha256(document)), published.body());

      HttpResponse<byte[]> served = sboms.get("maven", NAME, "open-publish");
      assertEquals(200, served.statusCode());
      assertEquals(document.length, served.body().length);
      assertEquals(200, sboms.head("maven", NAME, "open-publish").statusCode());
    }
  }

  @Test
  void aStrayBearerDoesNotTriggerOidcOnTheTokenlessRoute() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, so
    // nothing resolves an identity unless something asks. A publisher that happens to send an
    // Authorization header — a release step reusing one curl invocation for several surfaces — must
    // still be served, not refused by an OIDC mechanism nobody invoked.
    byte[] document = TinySbom.document(NAME, "stray-bearer");
    try (SbomClient sboms = client()) {
      HttpResponse<String> published =
          sboms.putAuthorized("maven", NAME, "stray-bearer", document, "qits-ci");
      assertEquals(201, published.statusCode(), published.body());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the sbom publish staying open must not have
    // widened anything else.
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
