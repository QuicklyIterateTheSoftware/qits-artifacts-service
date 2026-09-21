package eu.wohlben.qits.docs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A synthetic docs client: publish, fetch a file, read a version, list versions.
 *
 * <p>Same reasoning as {@code daemon/DaemonClient}, {@code maven/MavenClient} and {@code
 * npm/NpmClient}: a plain JDK {@link HttpClient} rather than RestAssured, HTTP/1.1 pinned, paths
 * built by hand so they reach the server exactly as written. That last part is the whole point here
 * — a site name contains slashes and often a leading {@code @}, and every convenience API in sight
 * would either decode or re-encode it, so the suite would end up testing the client.
 */
final class DocsClient implements AutoCloseable {

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private final Map<String, String> headers = new LinkedHashMap<>();

  DocsClient(URI base) {
    this.base = base;
  }

  /**
   * Adds a header to every subsequent request — {@code npm/NpmClient.header}'s twin, applied at the
   * one {@link #send} choke point so no call site can be the exception.
   */
  DocsClient header(String name, String value) {
    headers.put(name, value);
    return this;
  }

  @Override
  public void close() {
    http.close();
  }

  /** {@code PUT …/<site>/-/<version>} with a declared {@code Content-Length}. */
  HttpResponse<String> publish(String site, String version, byte[] tarGz) {
    return publish(site, version, tarGz, java.util.Map.of());
  }

  /**
   * The same publish carrying metadata, each entry as an {@code X-Artifacts-Meta-<key>} header —
   * the {@code ArtifactsTestMedia} pattern on the docs wire.
   */
  HttpResponse<String> publish(
      String site, String version, byte[] tarGz, java.util.Map<String, String> metadata) {
    HttpRequest.Builder request =
        bundle(site, version).PUT(HttpRequest.BodyPublishers.ofByteArray(tarGz));
    metadata.forEach((key, value) -> request.header("X-Artifacts-Meta-" + key, value));
    return send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The same publish, chunked — no {@code Content-Length}.
   *
   * <p>The shape {@code curl --upload-file} uses, and the one the global wire ceiling does not gate,
   * so a suite that only ever declared a length would prove nothing about this route's own cap.
   */
  HttpResponse<String> publishStreaming(String site, String version, byte[] tarGz) {
    return send(
        bundle(site, version)
            .PUT(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(tarGz))),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<byte[]> file(String site, String version, String path) {
    return send(
        HttpRequest.newBuilder(URI.create(url(site, version) + "/" + path))
            .timeout(Duration.ofMinutes(1))
            .GET(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  HttpResponse<Void> headFile(String site, String version, String path) {
    return send(
        HttpRequest.newBuilder(URI.create(url(site, version) + "/" + path))
            .timeout(Duration.ofMinutes(1))
            .method("HEAD", HttpRequest.BodyPublishers.noBody()),
        HttpResponse.BodyHandlers.discarding());
  }

  HttpResponse<String> version(String site, String version) {
    return send(bundle(site, version).GET(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> versions(String site) {
    return send(
        HttpRequest.newBuilder(URI.create(base + "artifacts/docs/docs/" + site))
            .timeout(Duration.ofMinutes(1))
            .GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The versions list with a raw query string appended, e.g. {@code meta.git.branch.name=main}. */
  HttpResponse<String> versions(String site, String query) {
    return send(
        HttpRequest.newBuilder(URI.create(base + "artifacts/docs/docs/" + site + "?" + query))
            .timeout(Duration.ofMinutes(1))
            .GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> delete(String site, String version) {
    return send(bundle(site, version).DELETE(), HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET} of an absolute path under the test root — the catch-all cases. */
  HttpResponse<String> getAbsolute(String path) {
    return send(
        HttpRequest.newBuilder(URI.create(base + path.substring(1))).GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder bundle(String site, String version) {
    return HttpRequest.newBuilder(URI.create(url(site, version))).timeout(Duration.ofMinutes(1));
  }

  private String url(String site, String version) {
    return base + "artifacts/docs/docs/" + site + "/-/" + version;
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    headers.forEach(builder::header);
    try {
      return http.send(builder.build(), bodyHandler);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
