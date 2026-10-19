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
class IdpConsumerPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  /**
   * The port the pact mock server listens on, chosen free once per JVM. Kept in a system property
   * because the profile and the test instance load this class in two class loaders, and a plain
   * static would pick two ports.
   */
  static final int PORT = sharedPort();

  private static synchronized int sharedPort() {
    String key = "qits.artifacts.test.mock-idp-port";
    String chosen = System.getProperty(key);
    if (chosen == null) {
      chosen = String.valueOf(freePort());
      System.setProperty(key, chosen);
    }
    return Integer.parseInt(chosen);
  }

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
      if (row.read() != null) {
        continue; // a plain reader's row (the key read): ConsumerPactTest runs it
      }
      Map<String, String> params = masters.params(row.state());
      V4Pact pact = ConsumerContract.pact(provider, List.of(row));
      // A refusal is a status and carries no body the relay reads: it must answer 401, mint nothing.
      boolean refused = masters.operation(row.state(), row.operationId()).status() >= 300;
      JsonNode recorded = refused ? null : ConsumerPactTest.answered(pact, row);
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              pact,
              MockProviderConfig.Companion.httpConfig("127.0.0.1", PORT, PactSpecVersion.V4),
              (mockServer, context) -> {
                Response answer =
                    given()
                        .header("Authorization", params.get("authorization"))
                        .when()
                        .get("/artifacts/token");
                if (refused) {
                  assertEquals(401, answer.statusCode(), answer.asString());
                  assertEquals(
                      "the identity provider refused these credentials",
                      answer.path("errors[0].message"));
                  return null;
                }
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
