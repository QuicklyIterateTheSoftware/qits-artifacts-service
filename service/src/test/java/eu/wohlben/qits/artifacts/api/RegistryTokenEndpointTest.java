package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.MachineTokens;
import eu.wohlben.qits.TinyIdp;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code /artifacts/token} on the wire: the door a later {@link PublishGuard} challenge will send
 * docker and buildkit to.
 *
 * <p><b>The machine-token gate is ON in this profile</b>, and that is the point of composing
 * {@link MachineTokens.Enforced} rather than running open. The claim under test is not merely that
 * the endpoint answers — it is that it answers <b>with no credential of its own</b> while every
 * guard this service has is armed. It is the endpoint that buys a credential, so a guard in front
 * of it would be a loop.
 *
 * <p>The far side is {@link TinyIdp}, which decides on the credential rather than on a stub, for
 * the classloader reason its javadoc gives.
 */
@QuarkusTest
@TestProfile(RegistryTokenEndpointTest.EnforcedWithTinyIdp.class)
class RegistryTokenEndpointTest {

  /**
   * The enforced posture plus an idp that answers — one profile, so one more Quarkus application
   * in the suite and not three. The unreachable-idp case rides the same profile because {@link
   * TinyIdp#SILENT} makes silence a property of the credential rather than of an address, which is
   * what would otherwise have needed a second application pointed at a closed port.
   */
  public static class EnforcedWithTinyIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides =
          new LinkedHashMap<>(new MachineTokens.Enforced().getConfigOverrides());
      overrides.putAll(TinyIdp.configOverrides());
      return overrides;
    }
  }

  // --- no credential, or one that is not a credential -------------------------------------------

  @Test
  void withNoAuthorizationTheEndpointAsksForTheStoredLogin() {
    given()
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(401)
        // Basic ALONE: naming Bearer here would point a client back at the door it is knocking on.
        .header("WWW-Authenticate", startsWith("Basic realm=\""))
        .body("errors[0].code", equalTo("UNAUTHORIZED"))
        .body("errors[0].message", equalTo("client credentials required"));
  }

  @Test
  void aBearerIsNotACredentialHere() {
    // This endpoint SELLS bearer tokens. One presented to it is not an authentication.
    given()
        .header("Authorization", "Bearer " + MachineTokens.forCiRun())
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(401)
        .body("errors[0].code", equalTo("UNAUTHORIZED"));
  }

  @Test
  void aBasicHeaderWithNoPairInItIsRefusedWithoutCallingTheIdp() {
    // An empty credential store, or a truncated helper answer. There is nothing to ask idp, and
    // asking would hold the client for the whole timeout while an unreachable idp is waited out.
    given()
        .header("Authorization", "Basic bm90LWEtcGFpcg==")
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(401)
        .body("errors[0].code", equalTo("UNAUTHORIZED"));
  }

  // --- the grant ---------------------------------------------------------------------------------

  @Test
  void aCredentialTheIdpAcceptsBuysABearer() {
    given()
        .header("Authorization", TinyIdp.basic(TinyIdp.GOOD))
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(200)
        // `token` is what the docker CLI reads, `access_token` the OAuth2 name; clients differ
        // about which they look for, so both carry the same string, always.
        .body("token", equalTo(TinyIdp.ACCESS_TOKEN))
        .body("access_token", equalTo(TinyIdp.ACCESS_TOKEN))
        .body("expires_in", equalTo(300))
        // RFC 6749 §5.1: a token response is never cached, anywhere.
        .header("Cache-Control", "no-store");
  }

  @Test
  void theSameGrantIsAvailableOverPost() {
    // docker GETs the realm with ?service=&scope=; anything written against RFC 6749 POSTs. Be
    // liberal — and read neither the query nor a form body, because the permission is the audience
    // the minted token already carries.
    given()
        .header("Authorization", TinyIdp.basic(TinyIdp.GOOD))
        .queryParam("service", "dev-qits-artifacts:8080")
        .queryParam("scope", "repository:qits/anything:push,pull")
        .when()
        .post("/artifacts/token")
        .then()
        .statusCode(200)
        .body("token", equalTo(TinyIdp.ACCESS_TOKEN));
  }

  @Test
  void aCredentialTheIdpRefusesIsRefusedHere() {
    given()
        .header("Authorization", TinyIdp.basic(TinyIdp.WRONG))
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(401)
        .body("errors[0].code", equalTo("UNAUTHORIZED"))
        .body("errors[0].message", equalTo("the identity provider refused these credentials"));
  }

  @Test
  void anIdpThatNeverAnswersIsAGatewayProblemAndNotTheCallers() {
    // 502, the edge's answer for the same case: the credential may be perfectly good, so a 401
    // would send docker off to fix something that is not the problem.
    given()
        .header("Authorization", TinyIdp.basic(TinyIdp.SILENT))
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(502)
        .body("errors[0].code", equalTo("UNAVAILABLE"));
  }

  // --- and no guard stands in front of it --------------------------------------------------------

  @Test
  void thePublishGuardNeverClaimsThisDoor() {
    // Asserted three ways, because all three are what keep the endpoint reachable: the guard's own
    // route arithmetic; the JAX-RS AdminWriteGuard, which sees only /artifacts/api; and the wire,
    // where every answer above arrived with no identity presented and the gate on.
    io.vertx.core.http.HttpMethod[] verbs = {
      io.vertx.core.http.HttpMethod.GET,
      io.vertx.core.http.HttpMethod.POST,
      io.vertx.core.http.HttpMethod.PUT
    };
    for (io.vertx.core.http.HttpMethod verb : verbs) {
      org.junit.jupiter.api.Assertions.assertFalse(
          PublishGuard.isPublish(verb, "/artifacts/token"),
          verb + " /artifacts/token must not be a publish route");
    }
    // A publish route with no identity passes today (the known gap) and will be challenged later;
    // this door must answer on its own terms either way. 401 here is the endpoint's own Basic
    // challenge, not a guard's refusal — the body and the header say which.
    given()
        .when()
        .get("/artifacts/token")
        .then()
        .statusCode(401)
        .header("WWW-Authenticate", startsWith("Basic realm=\""))
        .body("errors[0].message", equalTo("client credentials required"));
  }
}
