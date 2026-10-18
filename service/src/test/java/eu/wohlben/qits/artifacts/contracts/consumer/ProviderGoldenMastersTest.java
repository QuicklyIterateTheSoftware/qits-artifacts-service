package eu.wohlben.qits.artifacts.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.gc.ContractPinReaders;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer-pact machinery, run end to end against a fixture</b> (qits-1149): what proves
 * the reader, the projection and the mock-server run work on cases no provider's recording happens
 * to hold (an empty array, a path the recording lacks). The fixture under {@code
 * contracts-fixture/golden-masters/} is test data for this machinery only — it is never written
 * into a pact file. It also holds {@link ConsumerContract}'s table to the pinned recordings: every
 * live row's (state, operation) is recorded, and every pending row's is not — yet.
 */
class ProviderGoldenMastersTest {

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final ConsumerContract.Provider FIXTURE =
      new ConsumerContract.Provider("qits-fixture", "qits-fixture-service");

  private static ProviderGoldenMasters masters() {
    return ProviderGoldenMasters.of(
        "contracts-fixture/golden-masters/", FIXTURE.application(), FIXTURE.repository());
  }

  private static ConsumerContract.Row row(String operationId, List<String> consumes) {
    return new ConsumerContract.Row(
        FIXTURE,
        ConsumerContract.GC_PLAN,
        "fixture pins",
        operationId,
        consumes,
        ConsumerContract.Request.NONE,
        base -> ContractPinReaders.deployments(base, MAPPER).pins(),
        "$.pins");
  }

  private static final ConsumerContract.Row PINS =
      row("listFixturePins", List.of("$.pins[*].applicationName", "$.pins[*].shas[*]"));

  private static V4Pact pact(ConsumerContract.Row row) {
    PactBuilder builder =
        new PactBuilder(ProviderGoldenMasters.CONSUMER, FIXTURE.repository(), PactSpecVersion.V4);
    masters().interaction(builder, row);
    return builder.toPact();
  }

  @Test
  void theRealReaderReadsTheProjectedRecording() {
    V4Pact pact = pact(PINS);
    JsonNode expected = ConsumerPactTest.answered(pact, PINS);
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            pact,
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              Object read = PINS.read().apply(mockServer.getUrl());
              assertEquals(expected, MAPPER.valueToTree(read));
              return null;
            });
    assertInstanceOf(PactVerificationResult.Ok.class, result, String.valueOf(result));
    // Two pins recorded, so two examples, each a copy of the template.
    assertEquals(2, expected.size());
    assertEquals("fixture-app", expected.path(0).path("applicationName").asText());
    assertTrue(expected.path(0).path("shas").size() > 0);
  }

  @Test
  void thePactBindsOnlyWhatTheReaderConsumes() throws Exception {
    JsonNode pact = MAPPER.readTree(PactFileTest.normalise(PactFileTest.written(pact(PINS))));
    JsonNode interaction = pact.path("interactions").path(0);
    JsonNode body = interaction.path("response").path("body").path("content");
    assertFalse(body.has("generatedAt"), body.toString());
    assertFalse(body.path("pins").path(0).has("environment"), body.toString());
    assertTrue(body.path("pins").path(0).has("applicationName"), body.toString());
    // Any length, each element like the template: no reader here counts pins.
    JsonNode rules = interaction.path("response").path("matchingRules").path("body");
    assertEquals("type", rules.path("$.pins").path("matchers").path(0).path("match").asText());
    assertEquals(
        "operation", interaction.path("comments").path("references").path("qits-trigger")
            .path("kind").asText());
    assertEquals(
        "listFixturePins",
        interaction.path("comments").path("references").path("qits-call").path("operationId")
            .asText());
  }

  @Test
  void aConsumedPathTheRecordingLacksIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> pact(row("listFixturePins", List.of("$.pins[*].digest"))));
    assertTrue(refused.getMessage().contains("$.pins[*].digest"), refused.getMessage());
  }

  @Test
  void anEmptyRecordedArrayIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> pact(row("listNoFixturePins", List.of("$.pins[*].applicationName"))));
    assertTrue(refused.getMessage().contains("at least one recorded element"), refused.getMessage());
  }

  @Test
  void everyRowNamesAStateAnOperationAndWhatItReads() {
    for (ConsumerContract.Row row : ConsumerContract.ROWS) {
      assertFalse(row.state().isBlank(), row.description());
      assertFalse(row.operationId().isBlank(), row.description());
      assertTrue(row.waitingFor().contains(row.provider().repository()), row.waitingFor());
      if (row.isPending()) {
        continue;
      }
      ProviderGoldenMasters masters =
          ProviderGoldenMasters.of(row.provider().application(), row.provider().repository());
      // A success is read; a refusal is a status, and binding its body would bind nothing read.
      if (masters.operation(row.state(), row.operationId()).status() < 300) {
        assertFalse(row.consumes().isEmpty(), row.description());
      }
    }
    assertEquals(8, ConsumerContract.ROWS.size());
  }

  /**
   * A pending row waits for its provider to publish the state it names. The day the pinned golden
   * masters record that (state, operation), this fails: switch the row on.
   */
  @Test
  void aPendingRowWaitsOnAStateThePinnedRecordingLacks() {
    for (ConsumerContract.Row row : ConsumerContract.pending()) {
      ProviderGoldenMasters masters =
          ProviderGoldenMasters.of(row.provider().application(), row.provider().repository());
      boolean recorded;
      try {
        masters.operation(row.state(), row.operationId());
        recorded = true;
      } catch (IllegalArgumentException absent) {
        recorded = false;
      }
      assertFalse(
          recorded,
          row.description() + " [" + row.state() + "] is pending on '" + row.pending()
              + "', but the pinned " + row.provider().application()
              + "-golden-masters record it now: switch the row on");
    }
  }
}
