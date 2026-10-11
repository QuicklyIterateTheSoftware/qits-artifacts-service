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
 * The fifth outbound pin call: {@code GET /workspaces/api/pins}, at plan time, every time.
 *
 * <p>Everything {@link CdHttpDeploymentPins}' javadoc says about the direction, the caching and the
 * failure posture holds here verbatim, and {@link LaunchImagePins} holds the fact this one adds: it
 * is the only source that can name the version qits-workspaces is <b>actually</b> launching, as
 * opposed to the one it will launch after its next deploy.
 *
 * <p><b>An empty {@code pins} array is an answer</b> — a qits-workspaces with no image version
 * configured launches nothing pinnable — and it pins nothing, which is what it says. A missing array
 * is not: that is a shape this cannot read, and reading it as "nothing is launched" would condemn
 * the image the next workspace start pulls.
 *
 * <p>The {@code HttpClient} is an <b>instance</b> field for the native-image reason every outbound
 * client in this repository carries; the rule travels with the client.
 */
@ApplicationScoped
public class WorkspacesHttpLaunchPins implements WorkspacesLaunchPins {

  /** The service named in every refusal this reader makes. */
  public static final String SERVICE = "qits-workspaces";

  /**
   * Wrapped through {@link QitsHttpClients} so this call to qits-workspaces opens a CLIENT span
   * and carries {@code X-Qits-Caller}. Built in the injecting constructor, not at field
   * declaration — a container-managed field's initialiser runs before field injection does.
   */
  private final HttpClient client;

  @ConfigProperty(name = "qits.artifacts.gc.pins.workspaces-base-url")
  String baseUrl;

  @ConfigProperty(name = "qits.artifacts.gc.pins.workspaces-timeout")
  Duration timeout;

  @Inject ObjectMapper objectMapper;

  /** For the client proxy ArC puts in front of this bean, and for nothing else. */
  WorkspacesHttpLaunchPins() {
    this.client = null;
  }

  @Inject
  WorkspacesHttpLaunchPins(QitsHttpClients clients) {
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
