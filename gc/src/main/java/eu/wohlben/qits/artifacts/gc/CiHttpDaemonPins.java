package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The second and last outbound call this service makes: {@code GET /ci/api/daemon}, at plan time,
 * every time.
 *
 * <p>Everything {@link CdHttpDeploymentPins}' javadoc says about the direction, the caching and the
 * failure posture holds here verbatim, with one difference that is the whole reason this class needs
 * its own paragraph: <b>a blank version is a successful answer</b>. qits-ci reporting
 * {@code {"daemonVersion":"","source":"none"}} means this deployment has pinned no daemon, which is
 * the shipped default. Turning that into a failure would abort every run on a platform that has
 * published no daemon yet — a collector that never runs is not a safe collector, it is a broken one.
 *
 * <p>What does abort a run is qits-ci being unreachable, answering non-200, or answering a shape
 * this cannot read. Then {@link GcPinSources} records the failure and nothing is deleted.
 *
 * <p>The {@code HttpClient} is an <b>instance</b> field for the native-image reason the other three
 * outbound clients in this repository carry; the rule travels with the client.
 */
@ApplicationScoped
public class CiHttpDaemonPins implements CiDaemonPins {

  /**
   * Wrapped through {@link QitsHttpClients} so this call to qits-ci opens a CLIENT span and
   * carries {@code X-Qits-Caller}. Built in the injecting constructor, not at field declaration —
   * a container-managed field's initialiser runs before field injection does.
   */
  private final HttpClient client;

  @ConfigProperty(name = "qits.artifacts.gc.pins.ci-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.ci-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  /** For the client proxy ArC puts in front of this bean, and for nothing else. */
  CiHttpDaemonPins() {
    this.client = null;
  }

  @Inject
  CiHttpDaemonPins(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  @Override
  public String url() {
    return baseUrl + "/daemon";
  }

  @Override
  public DaemonPin daemonPin() {
    String url = url();
    return parse(get(url), url);
  }

  /**
   * qits-ci's answer, read into a pin — the parsing half, with no transport in it.
   *
   * <p>Split out for the reason {@link CdHttpDeploymentPins#parse} gives: the same document may be
   * fetched here or handed in by qits-platform-orchestrator, and one reader for both is what keeps
   * the two paths from reading one body two ways.
   *
   * <p>Missing fields read as blank rather than as a refusal: blank is this endpoint's own way of
   * saying "no pin", so a shape with one absent says the same thing. A body that is not an object
   * at all is the refusal.
   *
   * @param body the response body, verbatim
   * @param origin where it came from, for the message a refusal carries
   * @throws IllegalStateException the body is not a JSON object
   */
  public static DaemonPin parse(JsonNode body, String origin) {
    if (body == null || !body.isObject()) {
      throw new IllegalStateException("qits-ci answered a non-object body for " + origin);
    }
    return new DaemonPin(
        text(body, "daemonName"),
        text(body, "daemonVersion"),
        text(body, "previousDaemonVersion"),
        text(body, "source"));
  }

  private JsonNode get(String url) {
    try {
      HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "qits-ci answered " + response.statusCode() + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          "qits-ci unreachable at " + url + ": " + unreachable, unreachable);
    }
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    return value == null || value.isNull() ? "" : value.asText();
  }
}
