package eu.wohlben.qits.artifacts.contracts.consumer;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the idp contract</b> (qits-1149): {@code GET /artifacts/token} on this
 * running application, with the relay pointed at a pact mock server that answers what {@link
 * ConsumerContract}'s idp row promises — and the docker token this service made of it compared
 * with what the mock answered.
 *
 * <p>A {@code @QuarkusTest}, unlike {@link ConsumerPactTest}: the relay is a Vert.x route handler,
 * so the honest way to run it is through the route. The mock server runs on a port this profile
 * picks before the application starts, because the relay reads its target from config.
 */
@QuarkusTest
@TestProfile(IdpConsumerPactTest.MockIdp.class)
@Disabled("needs provider state 'a registered machine client' for issueToken in qits-idp-service")
class IdpConsumerPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /** The port the pact mock server listens on, chosen free once per JVM. */
  static final int PORT = freePort();

  /** The relay pointed at the mock server; nothing else changes. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.artifacts.registry.idp-token-url", "http://127.0.0.1:" + PORT + "/idp/token");
    }
  }

  @Test
  void theRelayAsksTheIdpAndHandsBackItsToken() {
    ConsumerContract.Provider provider = ConsumerContract.IDP;
    ProviderGoldenMasters masters =
        ProviderGoldenMasters.of(provider.application(), provider.repository());
    for (ConsumerContract.Row row : ConsumerContract.rows(provider)) {
      Map<String, String> params = masters.params(row.state());
      V4Pact pact = ConsumerContract.pact(provider, List.of(row));
      JsonNode recorded = ConsumerPactTest.answered(pact, row);
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              pact,
              MockProviderConfig.Companion.httpConfig("127.0.0.1", PORT, PactSpecVersion.V4),
              (mockServer, context) -> {
                Response answer =
                    given()
                        .header("Authorization", "Basic " + params.get("basicCredential"))
                        .when()
                        .get("/artifacts/token");
                assertEquals(200, answer.statusCode(), answer.asString());
                assertEquals(recorded.path("access_token").asText(), answer.path("token"));
                assertEquals(
                    recorded.path("expires_in").asLong(),
                    ((Number) answer.path("expires_in")).longValue());
                return null;
              });
      assertInstanceOf(PactVerificationResult.Ok.class, result, row.description() + ": " + result);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
