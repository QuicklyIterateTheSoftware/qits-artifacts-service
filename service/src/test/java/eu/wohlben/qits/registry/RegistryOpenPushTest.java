package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An anonymous push still lands with the machine-token gate on — the known gap of the CI-only
 * publish rule (USER RULING 2026-09-13). Every CI step on qits-net pushes with no credential today
 * ({@code buildctl} to the store's alias), so {@code PublishGuard} lets a caller with no identity
 * through and judges only an identity that is presented; {@code artifacts/api/PublishGuardTest}
 * proves that half. The regression this suite catches is "enforcement went on and every CI push
 * started failing". When the publishers present the run's credential, it flips to "an anonymous
 * push is refused".
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class RegistryOpenPushTest {

  private static final String IMAGE = "qits/open-push";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPushAndPullRoundTripDespiteTheGate() {
    try (OciClient anonymous = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("open-push");
      anonymous.push(IMAGE, "latest", subject);
      assertEquals(200, anonymous.versionProbe(), "the probe is unconditionally 200");
      assertArrayEquals(subject.manifest(), anonymous.pull(IMAGE, "latest").manifest());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the registry staying open must not have widened
    // anything else.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/unauthenticated")
        .then()
        .statusCode(401);
  }
}
