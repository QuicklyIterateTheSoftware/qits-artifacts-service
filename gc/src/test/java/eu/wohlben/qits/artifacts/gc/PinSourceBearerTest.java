package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * <b>Every pin read presents this service's bearer</b> (qits-1410): the five sources are dialled at
 * their edge hosts, and the edge refuses an anonymous request on a service vhost. Each reader is
 * driven over real HTTP against a stub that records the {@code Authorization} it was sent — with a
 * bearer that has a token, and with one that has none, which must send no header at all rather than
 * an empty {@code Bearer }.
 *
 * <p>What the answer parses to is the per-reader suites' business; a refusal here is caught and
 * ignored, because the request was made either way.
 */
class PinSourceBearerTest {

  private static final QitsHttpClients CLIENTS = QitsHttpClients.of(OpenTelemetry.noop(), null);

  @Test
  void everyReaderPresentsTheBearerItIsGiven() throws IOException {
    for (var reader : readers().entrySet()) {
      assertEquals(
          List.of("Bearer minted-token"),
          sent(reader.getValue(), () -> Optional.of("minted-token")),
          reader.getKey());
    }
  }

  @Test
  void aBearerWithNoTokenSendsNoHeaderRatherThanAnEmptyOne() throws IOException {
    for (var reader : readers().entrySet()) {
      assertEquals(List.of(""), sent(reader.getValue(), Optional::empty), reader.getKey());
    }
  }

  /** The Authorization headers one read made, against a stub answering every path. */
  private static List<String> sent(Consumer<Wiring> reader, PinSourceBearer bearer)
      throws IOException {
    try (StubPinService source = StubPinService.serving("/", "{}")) {
      try {
        reader.accept(new Wiring(source.baseUrl(), bearer));
      } catch (IllegalStateException refusedShape) {
        // the request was made; its answer is not this suite's question
      }
      return source.authorizations();
    }
  }

  private record Wiring(String baseUrl, PinSourceBearer bearer) {}

  private static Map<String, Consumer<Wiring>> readers() {
    ObjectMapper mapper = new ObjectMapper();
    Duration timeout = Duration.ofSeconds(5);
    return Map.of(
        "deployments",
        w -> {
          CdHttpDeploymentPins r = new CdHttpDeploymentPins(CLIENTS);
          r.baseUrl = w.baseUrl();
          r.timeout = timeout;
          r.objectMapper = mapper;
          r.bearer = w.bearer();
          r.pins();
        },
        "ci",
        w -> {
          CiHttpDaemonPins r = new CiHttpDaemonPins(CLIENTS);
          r.baseUrl = w.baseUrl();
          r.timeout = timeout;
          r.objectMapper = mapper;
          r.bearer = w.bearer();
          r.daemonPin();
        },
        "maintenance",
        w -> {
          MaintenanceHttpDependencyPins r = new MaintenanceHttpDependencyPins(CLIENTS);
          r.baseUrl = w.baseUrl();
          r.timeout = timeout;
          r.objectMapper = mapper;
          r.bearer = w.bearer();
          r.pins();
        },
        "workspaces",
        w -> {
          WorkspacesHttpLaunchPins r = new WorkspacesHttpLaunchPins(CLIENTS);
          r.baseUrl = w.baseUrl();
          r.timeout = timeout;
          r.objectMapper = mapper;
          r.bearer = w.bearer();
          r.pins();
        },
        "projects",
        w -> {
          ProjectsHttpLaunchPins r = new ProjectsHttpLaunchPins(CLIENTS);
          r.baseUrl = w.baseUrl();
          r.timeout = timeout;
          r.objectMapper = mapper;
          r.bearer = w.bearer();
          r.pins();
        });
  }
}
