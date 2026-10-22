package eu.wohlben.qits.daemon;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One slow daemon download must not stall the reads behind it (qits-1510).
 *
 * <p>The bug was an ordering, not a slowness: the daemon routes were <b>ordered</b> {@code
 * blockingHandler}s, and ordered tasks of one event loop run one at a time from a queue every
 * request on that loop shares. {@code BlobSender} holds its worker for the whole transfer, parked on
 * the client's drain, so in production a 43 MB daemon download to a slow curl stalled the manifest
 * HEADs on its loop past qits-ci's 3 s timeout.
 *
 * <p>The collision is made deterministic rather than hoped for: the profile pins the server to ONE
 * event loop, so the stalled GET and the timed HEAD are guaranteed to share it, and the puller is a
 * raw socket with a tiny receive window that sends its request and then never reads — the slowest
 * client there is. With the daemon reads ordered, the HEAD sits behind the parked GET and times out;
 * unordered, it is answered at once. The timed request is a daemon HEAD rather than a /v2 manifest
 * on purpose: this suite is about this repository's own routes, not the registries library's.
 *
 * <p>Its own class because it needs a {@code @TestProfile}, which forces a Quarkus restart.
 */
@QuarkusTest
@TestProfile(DaemonReadIsolationTest.OneEventLoop.class)
class DaemonReadIsolationTest {

  /** One event loop, so every connection lands on the same one and shares its ordered queue. */
  public static class OneEventLoop implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.vertx.event-loops-pool-size", "1",
          "quarkus.http.io-threads", "1");
    }
  }

  private static final String DAEMON = "qits-ci-daemon";

  /**
   * Far more than a never-reading client's receive window, the kernel's send buffer and Netty's
   * write-queue watermark together can absorb, so the sender is certain to park on drain — and far
   * under the 256M binary cap.
   */
  private static final int LARGE_BINARY = 16 * 1024 * 1024;

  @TestHTTPResource("/")
  URL root;

  @Test
  void aDaemonHeadIsNotQueuedBehindAStalledDaemonDownload() throws Exception {
    given()
        .contentType("application/json")
        .body("{\"type\":\"daemon-binaries\"}")
        .when()
        .put("/artifacts/api/repositories/daemons")
        .then()
        .statusCode(200);
    URI base = URI.create(root.toString());

    // Unique per RUN, not merely per test: versions are immutable and the blob store outlives runs.
    String run = Long.toString(System.currentTimeMillis());
    String large = "2026.1510." + run + "-large";
    String small = "2026.1510." + run + "-small";
    try (DaemonClient daemons = new DaemonClient(base)) {
      HttpResponse<String> published =
          daemons.put(DAEMON, large, TinyDaemon.binary(large, LARGE_BINARY));
      assertEquals(201, published.statusCode(), published.body());
      published = daemons.put(DAEMON, small, TinyDaemon.binary(small, 4096));
      assertEquals(201, published.statusCode(), published.body());
    }

    try (Socket puller = new Socket()) {
      // Set before connecting, so the advertised window is small from the first segment.
      puller.setReceiveBufferSize(4096);
      puller.connect(new InetSocketAddress(base.getHost(), base.getPort()), 5_000);
      OutputStream out = puller.getOutputStream();
      out.write(
          ("GET /artifacts/daemons/" + DAEMON + "/" + large + " HTTP/1.1\r\n"
                  + "Host: " + base.getHost() + ":" + base.getPort() + "\r\n"
                  + "\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      // Never read. Give the sender time to fill every buffer between it and this socket and park.
      Thread.sleep(1_500);

      try (HttpClient http =
          HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
        HttpRequest head =
            HttpRequest.newBuilder(URI.create(base + "artifacts/daemons/" + DAEMON + "/" + small))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build();
        long started = System.nanoTime();
        HttpResponse<Void> response;
        try {
          response = http.send(head, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
          fail("the daemon HEAD was queued behind the stalled daemon download and never answered");
          return;
        }
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertEquals(200, response.statusCode());
        assertTrue(
            tookMillis < 1_000,
            "the daemon HEAD took " + tookMillis + " ms behind a stalled daemon download");
      }
    }
  }
}
