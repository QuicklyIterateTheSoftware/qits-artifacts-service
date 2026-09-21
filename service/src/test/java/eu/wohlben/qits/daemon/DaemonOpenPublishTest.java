package eu.wohlben.qits.daemon;

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
 * An anonymous daemon publish is <b>refused</b> with the machine-token gate on — the twin of
 * {@code registry/RegistryOpenPushTest}, flipped on the same day (2026-09-20). It used to assert
 * the opposite, the known gap of the CI-only publish rule (USER RULING 2026-09-13); {@code
 * qits-publish daemon submit} and the bootstrap's own publisher carry the run's credential now, so
 * nothing left on this surface writes anonymously.
 *
 * <p><b>A plain 401, with no {@code WWW-Authenticate: Bearer}</b>, and that is the difference from
 * the registry's refusal: nothing that publishes a daemon binary speaks the docker token dance, so
 * a challenge here would name a door nobody knocks on. The publisher holds its bearer already or it
 * does not publish.
 *
 * <p>Reads are untouched, and deliberately so: a bootstrap script {@code curl}s a daemon binary
 * with no credential at all, which is the one flow that cannot be given one.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class DaemonOpenPublishTest {

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/daemons")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishIsRefusedWithoutAChallenge() {
    byte[] binary = TinyDaemon.binary("open-publish", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> refused = daemons.put("qits-ci-daemon", "open-publish", binary);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(
          refused.headers().allValues("WWW-Authenticate").stream()
              .noneMatch(value -> value.regionMatches(true, 0, "Bearer", 0, 6)),
          "a plain refusal, not a token-dance challenge: " + refused.headers());

      // Refused means nothing was stored, not merely that the answer was unhappy.
      assertEquals(404, daemons.head("qits-ci-daemon", "open-publish").statusCode());
    }
  }

  @Test
  void aCiRunPublishesAndAnybodyDownloads() {
    byte[] binary = TinyDaemon.binary("ci-publish", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> published =
          daemons.putAuthorized("qits-ci-daemon", "ci-publish", binary, MachineTokens.forCiRun());
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(published.body().contains("sha256:" + TinyDaemon.sha256(binary)), published.body());

      // The download stays anonymous: a bootstrap curling a daemon binary has no credential.
      HttpResponse<byte[]> served = daemons.get("qits-ci-daemon", "ci-publish");
      assertEquals(200, served.statusCode());
      assertEquals(binary.length, served.body().length);
      assertEquals(200, daemons.head("qits-ci-daemon", "ci-publish").statusCode());
    }
  }

  @Test
  void aStrayBearerIsStillNoIdentityAndNeverReachesOidc() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, so
    // nothing resolves an identity unless something asks, and PublishGuard never hands a bearer
    // that is not three base64url segments to OIDC. Such a caller is now refused — but as the
    // anonymous publisher, by this guard, and not by an OIDC mechanism nobody invoked. The
    // difference is visible in the body: the guard's own sentence, not a token-validation failure.
    byte[] binary = TinyDaemon.binary("stray-bearer", 512);
    try (DaemonClient daemons = client()) {
      HttpResponse<String> refused =
          daemons.putAuthorized("qits-ci-daemon", "stray-bearer", binary, "qits-ci");
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("only a CI run publishes"), refused.body());
      assertNull(refused.headers().firstValue("WWW-Authenticate").orElse(null));
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the daemon publish closing must not have moved
    // anything else.
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
