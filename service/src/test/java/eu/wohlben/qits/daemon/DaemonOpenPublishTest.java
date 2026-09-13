package eu.wohlben.qits.daemon;

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
 * An anonymous daemon publish still lands with the machine-token gate on — the known gap of the
 * CI-only publish rule (USER RULING 2026-09-13), the twin of {@code registry/RegistryOpenPushTest}.
 * CI steps publish the daemons with a bare {@code curl -X PUT} today, so {@code PublishGuard} judges
 * only an identity that is presented ({@code artifacts/api/PublishGuardTest}).
 *
 * <p><b>What stands in for write auth meanwhile.</b> A version is immutable: republishing {@code
 * (name, version)} is {@code 409} even for identical bytes, so an open publish can add a version and
 * can never change one. Consumers pin the digest this route echoes.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class DaemonOpenPublishTest {

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", "Bearer " + MachineTokens.forThisService())
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/daemons")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishAndDownloadRoundTripDespiteTheGate() {
    byte[] binary = TinyDaemon.binary("open-publish", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published = daemons.put("qits-ci-daemon", "open-publish", binary);
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("sha256:" + TinyDaemon.sha256(binary)), published.body());

      HttpResponse<byte[]> served = daemons.get("qits-ci-daemon", "open-publish");
      assertEquals(200, served.statusCode());
      assertEquals(binary.length, served.body().length);
      assertEquals(200, daemons.head("qits-ci-daemon", "open-publish").statusCode());
    }
  }

  @Test
  void aStrayBearerDoesNotTriggerOidcOnTheTokenlessRoute() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, so
    // nothing resolves an identity unless something asks. A publisher that happens to send an
    // Authorization header — a pipeline reusing one curl invocation for several surfaces — must
    // still be served, not refused by an OIDC mechanism nobody invoked.
    byte[] binary = TinyDaemon.binary("stray-bearer", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published =
          daemons.putAuthorized("qits-ci-daemon", "stray-bearer", binary, "qits-ci");
      assertEquals(201, published.statusCode(), published.body());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the daemon publish staying open must not have
    // widened anything else.
    given()
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/unauthenticated")
        .then()
        .statusCode(401);
  }

  private DaemonClient client() {
    return new DaemonClient(URI.create(root.toString()));
  }
}
