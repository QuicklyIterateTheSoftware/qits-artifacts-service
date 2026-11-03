package eu.wohlben.qits.artifacts.gc;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-process stand-in for any of the six pin peers, one path and one canned answer.
 *
 * <p>There is no network in this repository's suite and none of the six peers to dial, so the six
 * HTTP adapters would otherwise only ever be tested on their failure path. What they have to get
 * right is the <b>shape</b> — which key holds the array, which field is blank, what a non-200 means
 * — and that can only be proved against something that answers.
 *
 * <p>Five of the six answer at {@code /pins} and the daemon at {@code /daemon}, so a case that
 * needs two peers at once takes two instances rather than one server with two contexts: each peer
 * gets its own base url, which is what the adapters are configured with anyway. That is doing real
 * work for the two launch readers: they share one parse, so only a distinct base url can show that
 * each reader called its own service.
 *
 * <p>Driven over real HTTP rather than by calling the parser directly, for the reason the npm proxy
 * suite records: an adapter tested past its transport passes just as well when the transport is
 * wrong.
 */
final class StubPinService implements AutoCloseable {

  private final HttpServer server;

  /** The Authorization header of every request served, "" for a request that sent none. */
  private final List<String> authorizations;

  private StubPinService(HttpServer server, List<String> authorizations) {
    this.server = server;
    this.authorizations = authorizations;
  }

  /** Answers 200 with this body on the given path, and 404 everywhere else. */
  static StubPinService serving(String path, String body) throws IOException {
    return answering(path, 200, body);
  }

  /** Answers the given status and body on the given path. */
  static StubPinService answering(String path, int status, String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> authorizations = new CopyOnWriteArrayList<>();
    server.createContext(
        path,
        exchange -> {
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          authorizations.add(authorization == null ? "" : authorization);
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return new StubPinService(server, authorizations);
  }

  /** The Authorization header of every request served so far, in order; "" where none was sent. */
  List<String> authorizations() {
    return List.copyOf(authorizations);
  }

  /** The base url an adapter is configured with — no trailing slash, as the real ones have none. */
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
