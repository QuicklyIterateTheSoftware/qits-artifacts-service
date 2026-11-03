package eu.wohlben.qits.artifacts.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.artifacts.gc.PinSourceBearer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the token mint</b> (qits-1410): the named client {@code qits} that
 * application.properties ships, switched on and pointed at a pact mock server that answers what
 * {@link ConsumerContract}'s {@code getGcPlan: issueToken} row promises — and the bearer the {@link
 * PinSourceBearer} bean hands the five GC pin readers compared with what the mock answered. Every
 * other property of the client (grant type, token path, discovery off, the {@code qits-platform}
 * audience) is the shipped one, so the request the mock checks is the one the deployed process
 * sends. qits-docs-service's test of the same name, copied.
 *
 * <p>The port is a system property rather than a static field, because Quarkus instantiates the
 * profile in its own class loader, where a static field is a second copy with a second free port.
 */
@QuarkusTest
@TestProfile(IdpTokenMintPactTest.MockIdp.class)
class IdpTokenMintPactTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  static final String PORT_PROPERTY = "qits.artifacts.test.mint-idp-port";

  /** The mint row: the one idp row the GC plan triggers. */
  static ConsumerContract.Row mint() {
    return ConsumerContract.rows(ConsumerContract.IDP).stream()
        .filter(r -> r.trigger().equals(ConsumerContract.GC_PLAN))
        .findFirst()
        .orElseThrow();
  }

  static ProviderGoldenMasters idp() {
    return ProviderGoldenMasters.of(
        ConsumerContract.IDP.application(), ConsumerContract.IDP.repository());
  }

  /** The {@code qits} client switched on and pointed at the mock, as the state's client. */
  public static class MockIdp implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // The state hands its client as the Basic credential it sends: id and secret, decoded.
      String basic = idp().params(mint().state()).get("authorization").substring("Basic ".length());
      String[] pair =
          new String(Base64.getDecoder().decode(basic), StandardCharsets.UTF_8).split(":", 2);
      return Map.of(
          "quarkus.oidc-client.qits.client-enabled",
          "true",
          "quarkus.oidc-client.qits.auth-server-url",
          "http://127.0.0.1:" + port() + "/idp",
          "quarkus.oidc-client.qits.client-id",
          pair[0],
          "quarkus.oidc-client.qits.credentials.secret",
          pair[1]);
    }
  }

  @Inject PinSourceBearer bearer;

  @Test
  void theQitsClientMintsTheBearerEveryPinReadCarries() {
    ConsumerContract.Row row = mint();
    String minted = idp().json(row.state(), row.operationId()).path("access_token").asText();
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            ConsumerContract.pact(ConsumerContract.IDP, List.of(row)),
            MockProviderConfig.Companion.httpConfig("127.0.0.1", port(), PactSpecVersion.V4),
            (mockServer, context) -> {
              assertEquals(minted, bearer.token().orElseThrow());
              return null;
            });
    assertInstanceOf(PactVerificationResult.Ok.class, result, String.valueOf(result));
  }

  /** The port the pact mock server listens on: chosen free once per JVM, whichever loader asks. */
  static synchronized int port() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return Integer.parseInt(parked);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
