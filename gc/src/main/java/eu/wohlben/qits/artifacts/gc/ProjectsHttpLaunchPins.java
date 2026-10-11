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
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sixth and last outbound pin call: {@code GET /projects/api/pins}, at plan time, every time.
 *
 * <p>{@link WorkspacesHttpLaunchPins}' twin, on the same terms and for the same reason — the agent
 * and refinement images qits-projects would start with the configuration it is running with now.
 * The two are separate classes rather than one parameterised bean because they are separate
 * sources: a run's report names each url, each outcome and each failure on its own line, and a
 * shared bean would have to invent which of the two an outage belonged to.
 *
 * <p>The {@code HttpClient} is an <b>instance</b> field for the native-image reason every outbound
 * client in this repository carries; the rule travels with the client.
 */
@ApplicationScoped
public class ProjectsHttpLaunchPins implements ProjectsLaunchPins {

  /** The service named in every refusal this reader makes. */
  public static final String SERVICE = "qits-projects";

  /**
   * Wrapped through {@link QitsHttpClients} so this call to qits-projects opens a CLIENT span and
   * carries {@code X-Qits-Caller}. Built in the injecting constructor, not at field declaration —
   * a container-managed field's initialiser runs before field injection does.
   */
  private final HttpClient client;

  @ConfigProperty(name = "qits.artifacts.gc.pins.projects-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.projects-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  /** For the client proxy ArC puts in front of this bean, and for nothing else. */
  ProjectsHttpLaunchPins() {
    this.client = null;
  }

  @Inject
  ProjectsHttpLaunchPins(QitsHttpClients clients) {
    this.client = clients.wrap(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  @Override
  public String url() {
    return baseUrl + "/pins";
  }

  @Override
  public List<LaunchPin> pins() {
    String url = url();
    return LaunchImagePins.parse(get(url), SERVICE, url);
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
            SERVICE + " answered " + response.statusCode() + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading " + url, interrupted);
    } catch (IllegalStateException refused) {
      throw refused;
    } catch (Exception unreachable) {
      throw new IllegalStateException(
          SERVICE + " unreachable at " + url + ": " + unreachable, unreachable);
    }
  }
}
