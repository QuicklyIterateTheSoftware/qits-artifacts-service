package eu.wohlben.qits.artifacts.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of every pin-source contract</b> (qits-1149): the real reader from the
 * {@code gc} module, making its real HTTP call, pointed at a pact mock server that answers what
 * {@link ConsumerContract}'s row promises — and what the reader made of the answer compared with
 * what the mock answered. A row whose request the reader does not make, or whose answer it cannot read,
 * fails here.
 *
 * <p>Plain JUnit and pact-jvm's programmatic runner, one mock server per row, as in
 * qits-maintenance-service's {@code ProjectsConsumerPactTest}: the readers use the JDK's {@code
 * HttpClient} and need no CDI.
 *
 * <p><b>Every test is disabled until its provider records the state it names.</b> The reason on
 * each says which state, operation and repository. To enable one: pin the provider's
 * {@code eu.wohlben.qits:<app>-golden-masters} as a test dependency, drop the {@code @Disabled}
 * here and in {@link PactFileTest}, and write the pact file with {@code -Dgolden.update=true}.
 */
class ConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @Disabled("needs provider state 'deployments that pin images' for listDeploymentPins in qits-deployments-service")
  void deployments() {
    verify(ConsumerContract.DEPLOYMENTS);
  }

  @Test
  @Disabled("needs provider state 'a pinned daemon' for getDaemonPin in qits-ci-service")
  void ci() {
    verify(ConsumerContract.CI);
  }

  @Test
  @Disabled("needs provider state 'manifests that pin artifacts' for listDependencyPins in qits-maintenance-service")
  void maintenance() {
    verify(ConsumerContract.MAINTENANCE);
  }

  @Test
  @Disabled("needs provider state 'configured image pins' for listImagePins in qits-configuration-service")
  void configuration() {
    verify(ConsumerContract.CONFIGURATION);
  }

  @Test
  @Disabled("needs provider state 'a configured workspace image' for listLaunchPins in qits-workspaces-service")
  void workspaces() {
    verify(ConsumerContract.WORKSPACES);
  }

  @Test
  @Disabled("needs provider state 'configured agent images' for listLaunchPins in qits-projects-service")
  void projects() {
    verify(ConsumerContract.PROJECTS);
  }

  /** Every row against {@code provider}, each on its own mock server. */
  static void verify(ConsumerContract.Provider provider) {
    List<ConsumerContract.Row> rows = ConsumerContract.rows(provider);
    assertFalse(rows.isEmpty(), "no row against " + provider.repository());
    List<String> failures = new ArrayList<>();
    for (ConsumerContract.Row row : rows) {
      V4Pact pact = ConsumerContract.pact(provider, List.of(row));
      JsonNode expected = answered(pact, row);
      PactVerificationResult result =
          ConsumerPactRunnerKt.runConsumerTest(
              pact,
              MockProviderConfig.createDefault(PactSpecVersion.V4),
              (mockServer, context) -> {
                Object read = row.read().apply(mockServer.getUrl());
                assertEquals(expected, MAPPER.valueToTree(read), row.description());
                return null;
              });
      if (!(result instanceof PactVerificationResult.Ok)) {
        failures.add(row.description() + " [" + row.state() + "]: " + describe(result));
      }
    }
    if (!failures.isEmpty()) {
      fail(failures.size() + " contract row(s) failed:\n  " + String.join("\n  ", failures));
    }
  }

  /**
   * What the mock server answers for a one-row pact — the recording reduced to what the row reads,
   * each array as copies of its template — at the place the reader's result sits.
   */
  static JsonNode answered(V4Pact pact, ConsumerContract.Row row) {
    try {
      String body =
          pact.getInteractions().get(0).asSynchronousRequestResponse().getResponse().getBody()
              .valueAsString();
      JsonNode answer = MAPPER.readTree(body);
      return "$".equals(row.readAt()) ? answer : answer.at(pointer(row.readAt()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code $.a.b} as a JSON pointer. */
  private static String pointer(String path) {
    return "/" + path.substring(2).replace('.', '/');
  }

  private static String describe(PactVerificationResult result) {
    if (result instanceof PactVerificationResult.Error error) {
      return "error: " + error.getError();
    }
    return result.getDescription() + " — " + result;
  }
}
