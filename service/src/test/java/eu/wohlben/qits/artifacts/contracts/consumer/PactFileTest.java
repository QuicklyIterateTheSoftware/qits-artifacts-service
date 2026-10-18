package eu.wohlben.qits.artifacts.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.artifacts.contracts.GoldenFiles;
import eu.wohlben.qits.artifacts.contracts.GoldenJson;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pacts, {@code pacts/qits-artifacts-service_<provider>.json}</b>
 * (qits-1149), one per provider, and the references every interaction must carry. Adapted from
 * qits-maintenance-service's {@code ProjectsPactFileTest}.
 *
 * <p><b>Compare by default.</b> pact-jvm writes the pact {@link ConsumerContract} describes (raw, to
 * {@code service/target/pacts/}); the test normalises it — interactions sorted by description then
 * state, pact-jvm's own version dropped from {@code metadata}, 2-space indentation, one trailing
 * newline — and compares it to the committed file. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites it.
 *
 * <p>Every provider here has its file and is listed under {@code contracts.pacts} in {@code
 * .config/qits/release.yml}, so the platform publishes it as {@code
 * eu.wohlben.qits:qits-artifacts-service-pacts-<provider>}. A pending row is in no file.
 */
class PactFileTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void deployments() {
    check(ConsumerContract.DEPLOYMENTS);
  }

  @Test
  void ci() {
    check(ConsumerContract.CI);
  }

  @Test
  void maintenance() {
    check(ConsumerContract.MAINTENANCE);
  }

  @Test
  void workspaces() {
    check(ConsumerContract.WORKSPACES);
  }

  @Test
  void projects() {
    check(ConsumerContract.PROJECTS);
  }

  @Test
  void idp() {
    check(ConsumerContract.IDP);
  }

  /** Writes the provider's pact, compares it with the committed file, and checks its references. */
  static void check(ConsumerContract.Provider provider) {
    try {
      String raw = written(ConsumerContract.pact(provider));
      Path scratch = Path.of("target", "pacts", provider.pactFile());
      Files.createDirectories(scratch.getParent());
      Files.writeString(scratch, raw);
      String normalised = normalise(raw);
      GoldenFiles.compareOrWrite(
          GoldenFiles.repositoryRoot().resolve("pacts").resolve(provider.pactFile()), normalised);
      references(provider, MAPPER.readTree(normalised));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Both references on every interaction, every value a non-blank string. */
  static void references(ConsumerContract.Provider provider, JsonNode pact) {
    assertEquals(ProviderGoldenMasters.CONSUMER, pact.path("consumer").path("name").asText());
    assertEquals(provider.repository(), pact.path("provider").path("name").asText());
    assertEquals("4.0", pact.path("metadata").path("pactSpecification").path("version").asText());
    JsonNode interactions = pact.path("interactions");
    assertEquals(ConsumerContract.rows(provider).size(), interactions.size(), "one per row");
    Set<String> unique = new HashSet<>();
    for (JsonNode interaction : interactions) {
      String description = interaction.path("description").asText();
      JsonNode references = interaction.path("comments").path("references");
      JsonNode call = references.path("qits-call");
      strings(description, call, "app", "operationId");
      assertEquals(provider.repository(), call.path("app").asText(), description);
      JsonNode trigger = references.path("qits-trigger");
      String kind = trigger.path("kind").asText();
      assertTrue(List.of("operation", "event").contains(kind), description + ": kind " + kind);
      String key = "operation".equals(kind) ? "operationId" : "event";
      strings(description, trigger, "kind", "app", key);
      assertEquals(ProviderGoldenMasters.CONSUMER, trigger.path("app").asText(), description);
      assertTrue(
          description.startsWith(trigger.path(key).asText() + ": "),
          description + ": the description leads with the trigger");
      String state = interaction.path("providerStates").path(0).path("name").asText();
      assertTrue(unique.add(description + "\u0000" + state), "repeats: " + description);
    }
  }

  private static void strings(String description, JsonNode group, String... keys) {
    assertTrue(group.isObject(), description + ": reference group missing");
    assertEquals(keys.length, group.size(), description + ": " + group + " holds other keys");
    for (String key : keys) {
      JsonNode value = group.path(key);
      assertTrue(
          value.isTextual() && !value.asText().isBlank(),
          description + ": " + key + " must be a non-blank string, got " + value);
    }
  }

  /** The pact as pact-jvm's own writer serialises it. */
  static String written(V4Pact pact) {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(pact, writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted, version-stripped, 2-space indented, one trailing newline. */
  static String normalise(String raw) throws IOException {
    ObjectNode pact = (ObjectNode) MAPPER.readTree(raw);
    if (pact.path("metadata") instanceof ObjectNode meta) {
      meta.remove("pact-jvm");
    }
    if (pact.path("interactions") instanceof ArrayNode interactions) {
      List<JsonNode> sorted = new ArrayList<>();
      interactions.forEach(sorted::add);
      sorted.sort(
          Comparator.comparing((JsonNode i) -> i.path("description").asText())
              .thenComparing(i -> i.path("providerStates").path(0).path("name").asText()));
      interactions.removeAll();
      sorted.forEach(interactions::add);
    }
    return GoldenJson.render(pact);
  }
}
