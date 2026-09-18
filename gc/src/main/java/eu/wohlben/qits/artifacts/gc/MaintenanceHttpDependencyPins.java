package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The third outbound pin call: {@code GET /maintenance/api/pins}, at plan time, every time.
 *
 * <p>Everything {@link CdHttpDeploymentPins}' javadoc says about the direction, the caching and the
 * failure posture holds here verbatim. What it adds is a <b>fourth</b> keep-class of its own kind:
 * the other three sources answer "what is running or would be launched", and this one answers "what
 * is still referenced by source on main". Those are different facts, and the second is the only one
 * that speaks for a library nothing has resolved since the last cache warmed.
 *
 * <p><b>The {@code repositories} array is parsed and ignored.</b> It is the scan's freshness
 * provenance — which repositories were read, at which sha, how they went — and reading it into the
 * keep-set would be this service deciding when maintenance's inventory is fresh enough to act on. It
 * is shape-checked and dropped: an inventory too stale to trust is qits-platform-maintenance's own
 * refusal to make, and it makes it as a 503 that lands here as a failure like any other.
 *
 * <p><b>An ecosystem this cannot file is a refusal, not a skip.</b> A pin whose coordinate could not
 * be filed into one of the keep-sets is a keep silently dropped, which is the one failure mode a pin
 * source exists to prevent. So the whole answer is refused, the run reclaims nothing, and the fix is
 * one line here rather than an image nobody can pull.
 *
 * <p><b>That refusal is whole-source and fail-closed, which decides the rollout order.</b> The
 * throw lands in {@link GcPinSources}' fold as a failed source, and a failed source aborts every
 * sweep on the platform — not the daemon type, the run. So a maintenance that has begun serving an
 * ecosystem this set does not hold stops GC entirely, and this half ships <b>first</b>: teaching the
 * reader a name costs nothing while it is unused, and meeting it untaught costs every run.
 *
 * <p>{@link #parse} is also what {@code GcSuppliedPins.dependencyPins()} calls, so the supplied
 * document path needs no change of its own — one reader, two ways in, and an ecosystem taught here
 * is taught to both at once. That is the property the shared parser exists for.
 *
 * <p>The {@code HttpClient} is an <b>instance</b> field for the native-image reason every outbound
 * client in this repository carries; the rule travels with the client.
 */
@ApplicationScoped
public class MaintenanceHttpDependencyPins implements MaintenanceDependencyPins {

  /**
   * The ecosystems this store hosts, and therefore the ones a pin may be spelled for.
   *
   * <p>{@code daemon} joined the three on 2026-09-18, and it is the same kind of coordinate as the
   * rest: the daemons store is hosted here beside the maven repository, the npm registry and the OCI
   * registry, so a {@code daemon} row names bytes this service can file and can delete. What is
   * different is where it comes from — no manifest spells a daemon binary, so maintenance DERIVES
   * these from the maven or npm coordinate that carries one, and {@code via} on the row names that
   * carrier. That is provenance and this reader drops it, exactly as it drops {@code repositories}.
   */
  static final Set<String> ECOSYSTEMS = Set.of("maven", "npm", "docker", "daemon");

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.artifacts.gc.pins.maintenance-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.maintenance-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  @Override
  public String url() {
    return baseUrl + "/pins";
  }

  @Override
  public List<DependencyPin> pins() {
    String url = url();
    return parse(get(url), url);
  }

  /**
   * qits-platform-maintenance's answer, read into pins — the parsing half, with no transport in it.
   *
   * <p>Separate from {@link #pins()} for the reason {@link CdHttpDeploymentPins#parse} gives: the
   * same document arrives two ways, fetched here or handed in by qits-platform-orchestrator, and one
   * reader for both is what stops one document being read two ways.
   *
   * @param body the response body, verbatim
   * @param origin where it came from, for the message a refusal carries
   * @throws IllegalStateException the shape is one this cannot read. Never an empty list on doubt:
   *     an empty answer condemns every version nothing has resolved lately.
   */
  public static List<DependencyPin> parse(JsonNode body, String origin) {
    if (body == null || !body.isObject()) {
      throw new IllegalStateException(
          "qits-platform-maintenance answered a non-object body for " + origin);
    }
    JsonNode repositories = body.get("repositories");
    if (repositories != null && !repositories.isNull() && !repositories.isArray()) {
      // Read and dropped — but a body whose provenance member is not the shape it claims is a body
      // this does not recognise, and recognising half of one is how a keep-set is read wrong.
      throw new IllegalStateException(
          "qits-platform-maintenance answered a non-array 'repositories' for " + origin);
    }
    JsonNode pins = body.get("pins");
    if (pins == null || !pins.isArray()) {
      throw new IllegalStateException(
          "qits-platform-maintenance answered without a 'pins' array for " + origin);
    }
    List<DependencyPin> rows = new ArrayList<>();
    for (JsonNode pin : pins) {
      String ecosystem = text(pin, "ecosystem");
      String name = text(pin, "name");
      String version = text(pin, "version");
      if (ecosystem == null || name == null || version == null) {
        throw new IllegalStateException(
            "qits-platform-maintenance returned a pin with no ecosystem, name or version");
      }
      if (!ECOSYSTEMS.contains(ecosystem)) {
        // Filing it nowhere would drop a keep in silence, which is exactly the deletion this source
        // exists to prevent. Refusing costs a run that reclaims nothing and names the ecosystem.
        throw new IllegalStateException(
            "qits-platform-maintenance returned a pin for an ecosystem this store cannot file: "
                + ecosystem);
      }
      rows.add(
          new DependencyPin(
              ecosystem, name, version, text(pin, "repository"), text(pin, "manifestPath")));
    }
    return List.copyOf(rows);
  }

  /** Reads the JSON body of one GET, or throws with the url in the message. */
  private JsonNode get(String url) {
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "qits-platform-maintenance answered " + response.statusCode() + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          "qits-platform-maintenance unreachable at " + url + ": " + unreachable, unreachable);
    }
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row == null ? null : row.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String read = value.asText().trim();
    return read.isEmpty() ? null : read;
  }
}
