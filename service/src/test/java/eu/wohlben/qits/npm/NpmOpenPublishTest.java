package eu.wohlben.qits.npm;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An anonymous npm publish is <b>refused</b> with the machine-token gate on — the last twin of
 * {@code registry/RegistryOpenPushTest}, {@code daemon/DaemonOpenPublishTest} and {@code
 * sbom/SbomOpenPublishTest}, and the one that closed the rollout (2026-09-21, with {@code docs}).
 * It used to assert the opposite, and it did so long after its siblings had flipped, because the
 * publisher left on this surface was not a tool this estate could edit in place: it was the {@code
 * npm-library} archetype's {@code .npmrc} in the qits-qits wrapper, whose credentialing change
 * could not reach CI until that wrapper released. It has since — the {@code _authToken} is fed from
 * {@code $QITS_PUBLISH_TOKEN_COMMAND} and is the run's bearer, on {@code main} since wrapper commit
 * {@code 7f917fb} — and a census of all 56 release recipes on the estate finds no uncredentialed
 * npm publisher left, so the surface is closed and this file pins the closing.
 *
 * <p><b>A plain 401, with no {@code WWW-Authenticate: Bearer}</b>, and here that is a choice
 * against a client that would act on one: npm reads a Bearer challenge as a reason to re-send the
 * very {@code _authToken} ceremony that is not a credential, which turns one refusal into a retry
 * loop that still fails. Nothing on this wire speaks the docker token dance.
 *
 * <p>Reads stay open — an {@code npm install} resolving a packument is not publishing, and it is
 * the flow with the most callers holding no credential at all.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class NpmOpenPublishTest {

  /**
   * A package of its own per case, which on this wire means a distinct NAME and not merely a
   * distinct version. The sibling suites vary the version because a daemon binary and an SBOM are
   * read back at their own coordinate; an npm read-back is the <b>packument</b>, which is a fact
   * about the name, so a refused publish and an accepted one sharing a name would have the accepted
   * one answering the refused one's 404 assertion. The {@code %2f} spelling is the CLI's: a scoped
   * name must reach the router with its escape intact.
   */
  private static final String REFUSED = "@qits/open-publish";

  private static final String REFUSED_PATH = "@qits%2fopen-publish";

  private static final String PUBLISHED = "@qits/ci-publish";

  private static final String PUBLISHED_PATH = "@qits%2fci-publish";

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .body(Map.of("type", "npm-packages"))
        .when()
        .put("/artifacts/api/repositories/npm")
        .then()
        .statusCode(200);
  }

  @Test
  void anAnonymousPublishIsRefusedWithoutAChallenge() {
    byte[] document = TinyPackage.of(REFUSED, "1.0.0").publishDocument("latest");
    try (NpmClient npm = client()) {
      HttpResponse<String> refused = npm.publish("npm", REFUSED_PATH, document);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(
          refused.headers().allValues("WWW-Authenticate").stream()
              .noneMatch(value -> value.regionMatches(true, 0, "Bearer", 0, 6)),
          "a plain refusal, not a token-dance challenge: " + refused.headers());

      // Refused means nothing was stored. An npm version is immutable, so a guard that refused
      // AFTER writing would not merely be untidy — the coordinate would be spent, and reclaiming it
      // takes a tombstone and a GC pass.
      assertEquals(404, npm.packument("npm", REFUSED_PATH).statusCode());
    }
  }

  @Test
  void aCiRunPublishesAndAnybodyDownloads() {
    TinyPackage subject = TinyPackage.of(PUBLISHED, "1.0.0");
    try (NpmClient ci =
        new NpmClient(URI.create(root.toString()))
            .header("Authorization", "Bearer " + MachineTokens.forCiRun())) {
      assertEquals(
          201, ci.publish("npm", PUBLISHED_PATH, subject.publishDocument("latest")).statusCode());
    }

    // The install stays anonymous: resolving and fetching are reads, and the guard took the write
    // verbs alone.
    try (NpmClient npm = client()) {
      HttpResponse<String> packument = npm.packument("npm", PUBLISHED_PATH);
      assertEquals(200, packument.statusCode(), packument.body());
      String tarball = NpmClient.tarballUrl(NpmClient.parse(packument.body()), "1.0.0");
      HttpResponse<byte[]> served = npm.tarball(tarball);
      assertEquals(200, served.statusCode());
      assertEquals(subject.tarball().length, served.body().length);
    }
  }

  @Test
  void aStrayBearerIsStillNoIdentityAndNeverReachesOidc() {
    // The lesson this wire taught the others, now read back on the wire itself: CI's .npmrc used to
    // carry _authToken=qits-ci, which is not three base64url segments, so PublishGuard never hands
    // it to OIDC — this service ships quarkus.http.auth.proactive=false and nothing resolves an
    // identity unless something asks. Such a caller is now refused, but as the anonymous publisher,
    // by this guard. The difference is visible in the body: the guard's own sentence, not a
    // token-validation failure, which is what sends a debugger to the .npmrc rather than to the idp.
    byte[] document = TinyPackage.of("@qits/stray-bearer", "1.0.0").publishDocument("latest");
    try (NpmClient npm =
        new NpmClient(URI.create(root.toString())).header("Authorization", "Bearer qits-ci")) {
      HttpResponse<String> refused = npm.publish("npm", "@qits%2fstray-bearer", document);
      assertEquals(401, refused.statusCode(), refused.body());
      assertTrue(refused.body().contains("only a CI run publishes"), refused.body());
      assertNull(refused.headers().firstValue("WWW-Authenticate").orElse(null));
    }
  }

  @Test
  void theBlobStoreJsonApiIsStillGuarded() {
    // The same gate keeps guarding the JSON API — the npm publish closing must not have moved
    // anything else.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "npm-packages"))
        .when()
        .put("/artifacts/api/repositories/unauthenticated-npm")
        .then()
        .statusCode(401);
  }

  private NpmClient client() {
    return new NpmClient(URI.create(root.toString()));
  }
}
