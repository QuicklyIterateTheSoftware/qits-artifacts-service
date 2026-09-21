package eu.wohlben.qits.artifacts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.daemon.TinyDaemon;
import eu.wohlben.qits.docs.TinyBundle;
import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.sbom.TinySbom;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The six publish wires, each reduced to the one request that creates content on it, so that a
 * suite about {@link PublishGuard} can say "publish on this wire with these headers" and nothing
 * else.
 *
 * <p>Shared rather than copied because two suites need it in two postures — {@code
 * PublishGuardTest} with the machine-token gate on, {@code PublishGuardGateOffTest} with it off —
 * and the second one must cost no {@code @TestProfile} of its own (AGENTS.md's test-profile budget
 * rule: a profile is a whole Quarkus application). The gate-off suite therefore carries no profile
 * at all and runs on the default application every other suite already started.
 *
 * <p>A plain JDK {@link HttpClient} rather than RestAssured, for the reason {@code registry
 * /OciClient} gives: paths reach the server exactly as written, which the npm scoped-name case
 * depends on.
 */
final class PublishWires {

  /** Fixture content must be unique per run: versions are immutable and blobs dedupe. */
  static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  private static final AtomicInteger SEQ = new AtomicInteger();

  private static final HttpClient HTTP =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** The six wires, each by the one request that creates content on it. */
  enum Wire {
    OCI,
    NPM,
    MAVEN,
    DAEMON,
    DOCS,
    SBOM
  }

  /** The hosted repository each wire writes into, and the type that root carries. */
  static java.util.Map<String, String> roots() {
    return java.util.Map.of(
        "qits", "oci-images",
        "npm", "npm-packages",
        "maven", "maven-packages",
        "daemons", "daemon-binaries",
        "docs", "docs",
        "sboms", "sboms");
  }

  private final URL root;

  PublishWires(URL root) {
    this.root = root;
  }

  HttpResponse<String> publish(Wire wire, String... headers) {
    String id = RUN + "-" + SEQ.incrementAndGet();
    return switch (wire) {
      // The first write of a push; its 202 opens an upload session.
      case OCI ->
          send("POST", "v2/qits/publish-guard-" + id + "/blobs/uploads/", new byte[0], headers);
      case NPM ->
          send(
              "PUT",
              "artifacts/npm/npm/@qits%2fpublish-guard-" + id,
              TinyPackage.of("@qits/publish-guard-" + id, "1.0.0").publishDocument("latest"),
              with(headers, "Content-Type", "application/json"));
      case MAVEN ->
          send(
              "PUT",
              "artifacts/maven/maven/eu/wohlben/guard/g-" + id + "/1.0.0/g-" + id + "-1.0.0.jar",
              TinyArtifact.jar(id),
              headers);
      case DAEMON ->
          send(
              "PUT",
              "artifacts/daemons/qits-guard-daemon/" + id,
              TinyDaemon.binary(id, 256),
              headers);
      case DOCS ->
          send(
              "PUT",
              "artifacts/docs/docs/guard-" + id + "/-/1.0.0",
              TinyBundle.storybookLike(id).toTarGz(),
              headers);
      case SBOM ->
          send(
              "PUT",
              "artifacts/sboms/maven/eu.wohlben.guard:g-" + id + "/-/1.0.0",
              TinySbom.document("eu.wohlben.guard:g-" + id, "1.0.0"),
              headers);
    };
  }

  HttpResponse<String> send(String method, String path, byte[] body, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(root + path))
            .timeout(Duration.ofMinutes(1))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static String[] bearer(String token) {
    return new String[] {"Authorization", "Bearer " + token};
  }

  static String[] forwarded(String user, String roles) {
    return new String[] {"X-Qits-User", user, "X-Qits-Roles", roles};
  }

  static String[] with(String[] headers, String name, String value) {
    String[] all = Arrays.copyOf(headers, headers.length + 2);
    all[headers.length] = name;
    all[headers.length + 1] = value;
    return all;
  }

  static void assertAccepted(HttpResponse<String> response) {
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        response.uri() + " answered " + response.statusCode() + ": " + response.body());
  }

  static void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(
        expected,
        response.statusCode(),
        response.uri() + " answered " + response.statusCode() + ": " + response.body());
  }
}
