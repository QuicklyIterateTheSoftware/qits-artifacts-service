package eu.wohlben.qits.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * An anonymous push is <b>refused</b> with the machine-token gate on — {@code /v2} was the first of
 * the six surfaces to close (2026-09-20), and this suite is where the direction of that answer is
 * pinned. It used to assert the opposite, the known gap of the CI-only publish rule (USER RULING
 * 2026-09-13); buildctl now carries the run's credential, so the gap is closed here.
 *
 * <p><b>The status is only half of it, and the smaller half.</b> docker and buildkit send a
 * credential only after a {@code 401 WWW-Authenticate: Bearer realm="…"} names an endpoint they can
 * reach — a bare 401 is a push that never retries with anything, so it would break every image push
 * on the estate rather than making it authenticate. The realm is derived from the inbound request
 * and points at this store's own {@code /artifacts/token}. {@code artifacts/api/PublishGuardTest}
 * proves the role table behind it; {@code artifacts/api/RegistryChallengeTest} pins the exact bytes
 * of the header; {@code artifacts/api/PublishGuardGateOffTest} pins that with the gate off this
 * push still lands.
 *
 * <p>Reads are untouched: the version probe, the manifest and the blob are all anonymous here, and
 * a bootstrap with no credential still pulls.
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
  void anAnonymousPushIsRefusedWithARealmItCanReach() {
    // The first write of a push: the POST that opens an upload session. Unguarded it answers 202.
    io.restassured.response.Response refused =
        given().when().post("/v2/" + IMAGE + "/blobs/uploads/").andReturn();
    assertEquals(401, refused.statusCode(), refused.asString());

    String challenge = refused.header("WWW-Authenticate");
    String authority = URI.create(root.toString()).getAuthority();
    assertEquals(
        "Bearer realm=\"http://" + authority + "/artifacts/token\",service=\"" + authority + "\"",
        challenge);
    // And the realm is a door that really stands there: the token endpoint answers on it, which is
    // the whole reason a challenge is better than a refusal.
    assertTrue(
        given().when().get("/artifacts/token").andReturn().statusCode() < 500,
        "the advertised realm answers");
  }

  @Test
  void aCiRunPushesAndAnybodyPullsAnonymously() {
    TinyImage subject = TinyImage.of("open-push");
    try (OciClient ci = new OciClient(URI.create(root.toString())).bearer(MachineTokens.forCiRun())) {
      ci.push(IMAGE, "latest", subject);
    }
    try (OciClient anonymous = new OciClient(URI.create(root.toString()))) {
      assertEquals(200, anonymous.versionProbe(), "the probe is unconditionally 200");
      assertArrayEquals(subject.manifest(), anonymous.pull(IMAGE, "latest").manifest());
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the registry's own refusal must not have moved
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
