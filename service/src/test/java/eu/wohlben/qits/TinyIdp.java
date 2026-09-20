package eu.wohlben.qits;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A qits-platform-idp token endpoint, small enough to be decided by the credential it is handed.
 *
 * <p>{@code TokenValidationBootstrapIT}'s {@code MockIdp} is the recording mock this repository
 * already uses, and it is the right tool there: that IT asserts what the service <em>asked</em> the
 * idp for. It cannot answer this suite's question. Its stubs may only be registered by the owning
 * instance, and a {@code @QuarkusTest} runs its test methods in the runtime classloader while the
 * profile that starts the mock runs in the system one — so a test method holds an {@code attach()}
 * handle that throws on {@code stub}, and the accept/refuse halves of this flow could not be told
 * apart from the test at all.
 *
 * <p>So the behaviour lives HERE, on the server side, keyed on the credential — which is also what
 * a real idp does. Three credentials, three answers:
 *
 * <ul>
 *   <li>{@link #GOOD} — 200 with an RFC 6749 token response carrying {@link #ACCESS_TOKEN};
 *   <li>anything else with the Basic shape — 401 {@code invalid_client}, what idp says to a secret
 *       it does not know;
 *   <li>{@link #SILENT} — accepted and never answered, the shape a container that is redeploying
 *       takes. The profile pairs it with a one-second {@code idp-timeout}, so it is the "idp
 *       unreachable" case with a deterministic clock rather than a closed port that would need a
 *       second Quarkus application to point at.
 * </ul>
 *
 * <p>Started once per JVM with its port parked in a system property, the classloader-safe pattern
 * {@code MockService} and {@code EmbeddedPg} both use: a {@code QuarkusTestProfile} is instantiated
 * in more than one classloader, and the property table is the one thing every copy shares.
 */
public final class TinyIdp {

  /** The credential this idp knows. */
  public static final String GOOD = "dyn-ci-run-1:the-right-secret";

  /** A credential of the right shape that this idp has never heard of. */
  public static final String WRONG = "dyn-ci-run-1:the-wrong-secret";

  /** A credential this idp accepts the connection for and then never answers. */
  public static final String SILENT = "dyn-ci-run-1:never-answered";

  /** What a successful grant hands back, verbatim, under {@code access_token}. */
  public static final String ACCESS_TOKEN = "tiny-idp-access-token";

  /** How long {@link #SILENT} holds the connection — comfortably past the configured timeout. */
  private static final long SILENCE_MS = 5_000;

  private static final String PORT_PROPERTY = "qits.tinyidp.port";

  private static HttpServer server;

  private TinyIdp() {}

  /** Start once per JVM and park the port; later calls, in any classloader, are a no-op. */
  public static synchronized void ensureStarted() {
    if (System.getProperty(PORT_PROPERTY) != null) {
      return;
    }
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("could not start the tiny idp", e);
    }
    // A pool, not the default same-thread executor: SILENT holds its exchange open, and on the
    // caller's thread that would stall every other request this idp has to answer.
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/idp/token", TinyIdp::answer);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
  }

  /** The token endpoint's absolute URL — what {@code qits.artifacts.registry.idp-token-url} wants. */
  public static String tokenUrl() {
    ensureStarted();
    return "http://127.0.0.1:" + System.getProperty(PORT_PROPERTY) + "/idp/token";
  }

  /** {@code Basic <base64>} for a {@code clientId:secret} pair. */
  public static String basic(String credential) {
    return "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8));
  }

  /** The overrides a profile needs to put this idp behind the store's token endpoint. */
  public static Map<String, String> configOverrides() {
    return Map.of(
        "qits.artifacts.registry.idp-token-url", tokenUrl(),
        // Short, because SILENT is asserted through it. The shipped 10S is a patience budget for a
        // redeploying idp; a suite that waited it out would spend ten seconds proving one 502.
        "qits.artifacts.registry.idp-timeout", "PT1S");
  }

  private static void answer(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
    String credential = decode(authorization);
    if (SILENT.equals(credential)) {
      try {
        Thread.sleep(SILENCE_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
      return;
    }
    if (GOOD.equals(credential)) {
      send(
          exchange,
          200,
          "{\"access_token\":\""
              + ACCESS_TOKEN
              + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
      return;
    }
    send(exchange, 401, "{\"error\":\"invalid_client\"}");
  }

  private static String decode(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      return null;
    }
    try {
      return new String(
          Base64.getDecoder().decode(authorization.substring(6).strip()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
  }

  private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
