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
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One GET on qits-net, at plan time, every time: {@code GET /deployments/api/pins}.
 *
 * <p><b>One call, not two, and the deployer's rule rather than ours.</b> This used to list environments and
 * then each environment's deployments, and derive the keep-set here. cd answers the question
 * directly now — {@code {"pins":[{"applicationName":…,"shas":[…]}]}}, a union over every
 * environment — so the derivation is gone along with the bug it carried, and the request count is
 * halved.
 *
 * <p><b>This is a deliberate dependency direction</b> and one of the two this repository has (the
 * other is {@link CiHttpDaemonPins}). The artifacts service is domain-blind by design and learns
 * where cd lives for exactly one reason: the keep-set is "which shas would a restart or a rollback
 * pull", and cd is the only thing that knows. Living in the {@code gc} module keeps that honest —
 * the {@code artifacts} library dials nothing at all, so the exception belongs to the process that
 * needs it rather than to the store. The alternative — a driver assembling the pin list and posting
 * it in — was considered and rejected in the GC plan (⚖4): a safety-critical input computed outside
 * the service that acts on it is a place for the two to drift, and drift here deletes an image a
 * container is about to restart from.
 *
 * <p><b>Never cached.</b> A pin list is only true at the moment it was read; a cached one deletes
 * the image that was deployed while the cache was warm. The cost is one HTTP call per run.
 *
 * <p>Every failure — connect, status, shape — is an {@link IllegalStateException}. What fails closed
 * is the <b>run</b> now rather than one type: {@link GcPinSources} catches it, a plan marks itself
 * non-executable, and a sweep aborts before deleting anything. There is deliberately no empty-list
 * fallback, because an empty answer condemns every tag.
 */
@ApplicationScoped
public class CdHttpDeploymentPins implements CdDeploymentPins {

  /**
   * An <b>instance</b> field, not a static one, for the reason {@code PostReceiveNotifier} spells
   * out at length: a static {@code HttpClient} is built by the class initialiser, which under
   * GraalVM is image-build time, and native-image refuses an {@code HttpClientFacade} in the image
   * heap. The bean is {@code @ApplicationScoped}, so there is still one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.artifacts.gc.pins.cd-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.cd-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  @Override
  public String url() {
    return baseUrl + "/pins";
  }

  @Override
  public List<ApplicationPin> pins() {
    String url = url();
    return parse(get(url), url);
  }

  /**
   * The deployer's answer, read into pins — the parsing half, with no transport in it.
   *
   * <p>Separate from {@link #pins()} because the same document can arrive two ways now: fetched
   * here, or handed in by qits-platform-orchestrator, which reads the platform's pins once and
   * gives every deleter the same set. Both paths must read a body the same way, so there is one
   * reader and the caller decides where the bytes came from.
   *
   * @param body the response body, verbatim
   * @param origin where it came from, for the message a refusal carries — a url, or a phrase
   *     naming the supplied document
   * @throws IllegalStateException the shape is one this cannot read. Never an empty list on doubt:
   *     an empty answer condemns every tag.
   */
  public static List<ApplicationPin> parse(JsonNode body, String origin) {
    if (body == null || !body.isObject()) {
      throw new IllegalStateException(
          "qits-platform-deployments answered a non-object body for " + origin);
    }
    JsonNode pins = body.get("pins");
    if (pins == null || !pins.isArray()) {
      throw new IllegalStateException(
          "qits-platform-deployments answered without a 'pins' array for " + origin);
    }
    List<ApplicationPin> rows = new ArrayList<>();
    for (JsonNode pin : pins) {
      String application = text(pin, "applicationName");
      JsonNode shas = pin.get("shas");
      if (application == null || shas == null || !shas.isArray()) {
        // A pin cd cannot name an image for pins nothing, and guessing is how a pinned tag gets
        // deleted. Refusing the whole answer is the honest response to a shape this does not know.
        throw new IllegalStateException(
            "qits-platform-deployments returned a pin with no applicationName or no shas array");
      }
      List<String> commits = new ArrayList<>();
      for (JsonNode sha : shas) {
        commits.add(sha.asText());
      }
      rows.add(new ApplicationPin(application, List.copyOf(commits)));
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
            "qits-platform-deployments answered " + response.statusCode() + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          "qits-platform-deployments unreachable at " + url + ": " + unreachable, unreachable);
    }
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }
}
