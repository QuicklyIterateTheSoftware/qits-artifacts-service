package eu.wohlben.qits.daemon;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.DaemonRegistryService;
import eu.wohlben.qits.artifacts.error.DaemonException;
import eu.wohlben.qits.registry.BlobSender;
import eu.wohlben.qits.registry.OciRequestBody;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The daemon-binaries wire, at {@code /artifacts/daemons/<name>/<version>}.
 *
 * <p>Two verbs and one idea: a streaming {@code PUT} that publishes, and a streaming {@code GET}
 * that serves. The maven wire's shape with the derivations taken out, because a daemon binary has
 * none — it is one file with one coordinate.
 *
 * <p><b>The row write IS the publish.</b> One request stages the bytes, promotes them into the blob
 * store and inserts the {@code daemon_binary} row, so there is no way to store a daemon's bytes
 * without an identity. That is the hole this type was created to close: the bootstrap's monolithic
 * {@code POST} to the OCI blob-upload session promoted bytes and wrote no row by construction, which
 * is why every row-less byte in this store is a ci-daemon build. Re-publishing an existing version
 * is {@code 409} — versions are immutable, latest-wins never happens, and the response carries the
 * computed digest, which is what a release pipeline pastes into a deployment.
 *
 * <p><b>Two spellings of download, deliberately</b> (daemon-artifact-identity-plan.md §2.2). The
 * digest-addressed blob route on {@code /v2} is <em>untouched</em>: the launcher, the URL template
 * and every existing pin keep working exactly as they are, and the digest stays what {@code
 * qits.ci.daemon-version} holds (⚖2). The version-addressed {@code GET} here is the readable second
 * spelling, safe to add only because a version pointer never moves.
 *
 * <p><b>There is no authentication here, at all</b> — the same stance {@code /v2}, {@code
 * /artifacts/npm} and {@code /artifacts/maven} take, and for the same reason: on qits-net producers
 * are trusted, and a publish surface that needed a credential store would need one before the
 * platform can build anything. What a publish cannot do is <em>change</em> anything — a version is
 * immutable ({@code 409} on republish) and a consumer pins the digest this route echoes, so
 * integrity comes from addressing rather than from write auth. Machine auth arrives wholesale with
 * qits-platform-idp, for every surface at once; gating this one alone would report a decision
 * nobody took.
 *
 * <p>Reads are anonymous for the extra reason that the cold-start path is a bootstrap script with no
 * token: a fresh platform has to be able to fetch a daemon before it has any CI to mint a credential
 * with.
 *
 * <p><b>No {@code BodyHandler}, anywhere in this class.</b> {@code BodyHandler.create()} defaults to
 * 10 MiB and the binary is 43 MB, so one would have silently 413'd every real publish — the git
 * host's {@code max-pack-size} lesson. The {@code PUT} streams through {@code OciRequestBody}
 * instead, bounded by {@code qits.artifacts.daemon.max-binary-size}, which is also the only bound a
 * chunked upload has: the global {@code quarkus.http.limits.max-body-size} gates a declared
 * {@code Content-Length} only.
 */
@ApplicationScoped
public class DaemonRoutes {

  /** Waits for the NEXT chunk, not the whole upload — the registry's idle-timeout shape. */
  private static final Duration UPLOAD_IDLE_TIMEOUT = Duration.ofMinutes(1);

  @Inject DaemonRegistryService registry;
  @Inject BlobStore blobStore;
  @Inject BlobSender blobSender;

  /**
   * The one size answer for both directions a daemon binary can travel.
   *
   * <p>The measured ci-daemon is 43 MB (a musl-static native image), so the default is roughly a
   * 6× headroom rather than a snug fit: a daemon grows with the platform, and a cap that has to be
   * raised in a deployment the day a release lands is a cap that will be raised in a hurry. It is a
   * config knob and not a constant for the reason the other three are — it has to be able to move
   * with {@code quarkus.http.limits.max-body-size}, a deployment's disk budget rather than a
   * property of the format.
   */
  @ConfigProperty(name = "qits.artifacts.daemon.max-binary-size", defaultValue = "256M")
  MemorySize maxBinarySize;

  void init(@Observes Router router) {
    // HEAD is NOT derived from GET by Vert.x — every GET route needs its HEAD twin, or every client
    // that probes before downloading sees a 404.
    //
    // Both reads are registered UNORDERED — the trailing `false` — and the publish keeps the
    // ordered default. An ordered blockingHandler does not get a worker of its own: its tasks join
    // one queue per event loop, shared by every request on that loop (Quarkus' duplicated contexts
    // share it too), and run one at a time. BlobSender holds its worker for the whole transfer,
    // parked on the client's drain, so behind an ordered 43 MB download to a slow curl every later
    // read on that loop — /v2 manifest HEADs included — waited out the transfer (qits-1510).
    router
        .headWithRegex(DaemonPaths.BINARY)
        .blockingHandler(guarded("head binary", rc -> serve(rc, false)), false);
    router
        .getWithRegex(DaemonPaths.BINARY)
        .blockingHandler(guarded("get binary", rc -> serve(rc, true)), false);

    // The publish PUT streams rather than buffers: a BodyHandler would hold 43 MB in memory AND cap
    // it at 10 MiB, and a raw HttpServerRequest read is bounded by nothing — the two rules
    // OciRequestBody exists for. Hashing to sha256 happens inside the stage, for free.
    router
        .putWithRegex(DaemonPaths.BINARY)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("publish", this::publish));

    // DELETE is deliberately unimplemented, exactly as on /v2, /artifacts/npm and /artifacts/maven:
    // the store is append-only and retiring a version is the GC strategy's job, not a verb a
    // publisher holds. 405 rather than 404, which would read as "unknown daemon".
    router
        .route(HttpMethod.DELETE, DaemonPaths.BASE + "/*")
        .handler(
            rc -> DaemonErrors.send(rc, 405, "this daemon repository does not implement delete"));

    // Everything else under the base is a short plain-text 404, never the SPA's HTML — a bootstrap
    // script that pipes 200 text/html into a file gets an executable that is a web page.
    router.route(DaemonPaths.BASE).handler(this::notFound);
    router.route(DaemonPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    DaemonErrors.send(
        rc, 404, "not a route this daemon repository serves: " + rc.normalizedPath());
  }

  // --- GET / HEAD -------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD /artifacts/daemons/<name>/<version>} — the version-addressed download, streamed
   * from the store.
   *
   * <p>Anonymous, like every read in this service. Immutable on top and content-addressed
   * underneath, so the bytes behind this URL can never mean something else and the response says so
   * with the same cache header a maven release carries.
   */
  private void serve(RoutingContext rc, boolean withBody) {
    String name = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireDaemonRepository(ArtifactsRepositorySeeder.DAEMONS);

    DaemonRegistryService.StoredBinary stored =
        registry
            .find(ArtifactsRepositorySeeder.DAEMONS, name, version)
            .orElseThrow(
                () ->
                    new DaemonException(
                        404, "no such daemon binary: " + name + " version " + version));
    long size;
    try {
      size = blobStore.size(stored.blobId());
    } catch (Exception missing) {
      throw new DaemonException(
          404, "the bytes of " + name + " " + version + " are not stored");
    }

    // Size first, then touch — a row whose bytes are gone is a 404, not an access. HEAD counts,
    // the stance the OCI manifest route already takes. Only this spelling records anything: the
    // digest-addressed /v2 blob route carries no daemon identity, so it stays unattributed.
    registry.touchBinary(ArtifactsRepositorySeeder.DAEMONS, name, version);

    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
            .putHeader(HttpHeaders.ETAG, "\"" + stored.blobId() + "\"")
            // The digest, on the response of the readable spelling. It is what makes the two
            // download routes verifiably the same bytes, and what a consumer checks against its pin
            // without having to ask a second endpoint.
            .putHeader("Docker-Content-Digest", "sha256:" + stored.blobId())
            .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
    if (!withBody) {
      // HEAD must carry the same Content-Length as GET, and BlobSender writes a body
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }
    // The binary's chunks, written under the client's backpressure on this worker thread. A daemon
    // binary is 43 MB and every bootstrap curls one, so this is the route where the trade-off costs
    // most — see quarkus.vertx.worker-pool-size in application.properties, which is what bounds how
    // many of these can be in flight.
    blobSender.send(response, stored.blobId(), "daemon " + name + " " + version);
  }

  // --- PUT --------------------------------------------------------------------------------------

  /**
   * {@code PUT /artifacts/daemons/<name>/<version>} — the publish. Stage, promote, insert; the row
   * write is what makes it a publish.
   *
   * <p>The 409 is thrown inside {@link DaemonRegistryService#publish}'s transaction, so a re-publish
   * never half-lands — the shape npm's immutability refusal has. The promoted blob survives it,
   * which costs nothing: it is content-addressed, so a re-publish of identical bytes promotes over
   * itself and a re-publish of different bytes leaves a row-less blob the census reports honestly.
   */
  private void publish(RoutingContext rc) {
    String name = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireDaemonRepository(ArtifactsRepositorySeeder.DAEMONS);

    BlobStore.StagedBlob staged;
    try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis())) {
      staged = blobStore.stage(body, maxBinarySize.asLongValue());
    } catch (IOException e) {
      throw new DaemonException(400, "the upload stream failed: " + e.getMessage());
    }
    if (staged.size() == 0) {
      throw new DaemonException(400, "an empty body is not a daemon binary");
    }
    blobStore.promote(staged);
    DaemonRegistryService.StoredBinary published =
        registry.publish(
            ArtifactsRepositorySeeder.DAEMONS, name, version, staged.sha256(), staged.size());

    // A JsonObject, not a DTO. A type serialised only inside a Vert.x handler is invisible to the
    // native-image build, so this stack adds zero reflection configuration — the rule registry, npm
    // and maven all follow.
    respond(
        rc,
        201,
        new JsonObject()
            .put("name", published.name())
            .put("version", published.version())
            .put("digest", "sha256:" + published.blobId())
            .put("sizeBytes", published.sizeBytes())
            .put("publishedAt", published.publishedAt().toString()));
  }

  private void respond(RoutingContext rc, int status, JsonObject body) {
    byte[] bytes = body.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
        .end(io.vertx.core.buffer.Buffer.buffer(bytes));
  }

  /**
   * Wraps a handler so every throwable becomes the plain-text envelope rather than {@code
   * QuarkusErrorHandler}'s HTML.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (Throwable thrown) {
        DaemonErrors.fail(rc, what, thrown);
      }
    };
  }
}
