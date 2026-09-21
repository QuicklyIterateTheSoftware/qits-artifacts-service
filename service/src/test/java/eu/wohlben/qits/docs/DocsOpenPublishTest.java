package eu.wohlben.qits.docs;

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
 * An anonymous docs publish is <b>refused</b> with the machine-token gate on — the fourth twin of
 * {@code registry/RegistryOpenPushTest}, {@code daemon/DaemonOpenPublishTest} and {@code
 * sbom/SbomOpenPublishTest}, and with {@code npm} the surface that closed the rollout on
 * 2026-09-21.
 *
 * <p><b>This suite is new, and its absence was the gap it exists to close.</b> Until today nothing
 * but {@code artifacts/api/PublishGuardTest}'s parameterised rows said anything about this wire's
 * anonymous state — so docs was the one surface whose posture lived entirely inside a table of six,
 * where a careless widening of an {@code @EnumSource} could have moved it without any file saying
 * so. The other three wires each have a suite that states their answer in their own package and in
 * their own protocol's terms; this is the docs one. Its publisher is the {@code java-service}
 * archetype's {@code curl -H "Authorization: Bearer …"}, {@code qits-publish docs submit}, and five
 * repositories' hand-written copies of the same block — all of them holding the run's bearer since
 * wrapper commit {@code 7f917fb}.
 *
 * <p><b>A plain 401, with no {@code WWW-Authenticate: Bearer}</b>: the publisher here is a shell
 * {@code curl}, which would read a Bearer challenge as a reason to prompt for a password nobody is
 * standing there to type. Nothing on this wire speaks the docker token dance.
 *
 * <p>Reads stay open, and here that is the whole product: a docs bundle is a website, and every
 * reader of it is a browser holding nothing. {@code DocsRegistryTest} proves the round trip with
 * the gate off; this proves who is allowed to start it.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class DocsOpenPublishTest {

  /** Distinct from {@code DocsRegistryTest}'s {@code ui-<salt>} sites, which share this store. */
  private static final String SITE = "@qits/open-publish-docs";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType("application/json")
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .body("{\"type\":\"docs\"}")
        .when()
        .put("/artifacts/api/repositories/docs")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishIsRefusedWithoutAChallenge() {
    byte[] bundle = TinyBundle.storybookLike("open-publish").toTarGz();
    try (DocsClient docs = client()) {
      HttpResponse<String> refused = docs.publish(SITE, "1.0.0", bundle);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(
          refused.headers().allValues("WWW-Authenticate").stream()
              .noneMatch(value -> value.regionMatches(true, 0, "Bearer", 0, 6)),
          "a plain refusal, not a token-dance challenge: " + refused.headers());

      // Refused means nothing was stored, not merely that the answer was unhappy — a half-written
      // bundle is a website served with pieces missing, which is the failure nobody reports.
      assertEquals(404, docs.version(SITE, "1.0.0").statusCode());
    }
  }

  @Test
  void aCiRunPublishesAndAnybodyDownloads() {
    byte[] bundle = TinyBundle.storybookLike("ci-publish").toTarGz();
    try (DocsClient ci =
        client().header("Authorization", "Bearer " + MachineTokens.forCiRun())) {
      HttpResponse<String> published = ci.publish(SITE, "2.0.0", bundle);
      assertEquals(201, published.statusCode(), published.body());
    }

    // The read stays anonymous: the consumer of a docs bundle is a browser with no credential at
    // all, which is the one flow on this wire that could never be given one.
    try (DocsClient docs = client()) {
      assertEquals(200, docs.version(SITE, "2.0.0").statusCode());
      assertEquals(200, docs.headFile(SITE, "2.0.0", "index.html").statusCode());
      HttpResponse<byte[]> index = docs.file(SITE, "2.0.0", "index.html");
      assertEquals(200, index.statusCode());
      assertTrue(index.body().length > 0, "the bundle's index is served, not merely announced");
    }
  }

  @Test
  void aStrayBearerIsStillNoIdentityAndNeverReachesOidc() {
    // The npm lesson, on this route: this service ships quarkus.http.auth.proactive=false, so
    // nothing resolves an identity unless something asks, and PublishGuard never hands a bearer
    // that is not three base64url segments to OIDC. Such a caller is refused as the anonymous
    // publisher, by this guard, and not by an OIDC mechanism nobody invoked. It is the shape a
    // recipe lands in when its token mint failed and the step swallowed the exit code, so the
    // difference matters: the body carries the guard's own sentence, not a validation failure.
    byte[] bundle = TinyBundle.storybookLike("stray-bearer").toTarGz();
    try (DocsClient docs = client().header("Authorization", "Bearer qits-ci")) {
      HttpResponse<String> refused = docs.publish(SITE, "3.0.0", bundle);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("only a CI run publishes"), refused.body());
      assertNull(refused.headers().firstValue("WWW-Authenticate").orElse(null));
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the docs publish closing must not have moved
    // anything else.
    given()
        .contentType("application/json")
        .body("{\"type\":\"docs\"}")
        .when()
        .put("/artifacts/api/repositories/unauthenticated-docs")
        .then()
        .statusCode(401);
  }

  private DocsClient client() {
    return new DocsClient(URI.create(root.toString()));
  }
}
