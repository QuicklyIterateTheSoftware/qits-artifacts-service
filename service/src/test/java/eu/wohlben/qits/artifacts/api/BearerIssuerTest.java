package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the issuer qits-idp publishes is accepted</b> (qits-708). The shipped tenant has discovery
 * on and no issuer key at all, so the issuer quarkus-oidc checks every bearer's {@code iss} against
 * is the {@code issuer} of the discovery document it reads at startup. Before qits-708 discovery
 * was off and no issuer was configured, and quarkus-oidc then checks no {@code iss} at all: a token
 * signed by a published key and addressed to {@code qits-platform} was accepted whoever claimed to
 * have issued it.
 *
 * <p>The far side is qits-service-mock's {@link MockIdp}: it serves a discovery document whose
 * {@code issuer} is the {@code iss} it mints, and a JWKS at the {@code jwks_uri} that document
 * names. This profile moves only the gate and {@code auth-server-url}, so the tenant under test is
 * the shipped one: discovery, the published issuer, the shipped audience, keys by {@code kid}.
 * Unlike {@code MachineTokens.Enforced}, it configures no public key and no issuer.
 *
 * <p>{@code TokenValidationBootstrapIT} proves the same block on the packaged artifact; this class
 * is the in-JVM proof of the refusal, which no other test here makes.
 */
@QuarkusTest
@TestProfile(BearerIssuerTest.DiscoveringTheMockIdp.class)
class BearerIssuerTest {

  /** AdminWriteGuard's door: an admin write that demands {@code qits:system}. */
  private static final String GUARDED_ROUTE = "/artifacts/api/repositories/bearer-issuer";

  /** The one platform audience, which the shipped tenant enforces. */
  private static final String AUDIENCE = "qits-platform";

  /** Another environment's idp address: an issuer this service trusts under no name. */
  private static final String FOREIGN_ISSUER = "http://dev-qits-idp:8080/idp";

  /** The gate on, the tenant pointed at the mock idp, and nothing else moved. */
  public static class DiscoveringTheMockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      return Map.of(
          "qits.auth.machine.required", "true",
          "quarkus.oidc.auth-server-url", idp.baseUrl());
    }
  }

  @Test
  void theTenantReadsTheDiscoveryDocument() {
    // The tenant resolves at startup (connection-delay): the document is read before any bearer.
    given().when().get("/artifacts/q/health/ready").then().statusCode(200);
    assertTrue(
        MockIdp.attach().recordedRequests().stream()
            .anyMatch(r -> "/idp/.well-known/openid-configuration".equals(r.path())),
        "the tenant never read the idp's discovery document");
  }

  @Test
  void aBearerFromThePublishedIssuerIsAccepted() {
    MockIdp idp = MockIdp.attach();
    String token = idp.token().audience(AUDIENCE).groups("qits:system").mint();
    put(token).then().statusCode(200);
  }

  @Test
  void aBearerFromAnotherIssuerIsRefused() {
    // Correctly signed with the published key, the right audience, not expired: only `iss` is wrong.
    MockIdp idp = MockIdp.attach();
    String token =
        idp.token().audience(AUDIENCE).groups("qits:system").claim("iss", FOREIGN_ISSUER).mint();
    put(token).then().statusCode(401);
  }

  private static Response put(String token) {
    return given()
        .header("Authorization", "Bearer " + token)
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put(GUARDED_ROUTE);
  }
}
