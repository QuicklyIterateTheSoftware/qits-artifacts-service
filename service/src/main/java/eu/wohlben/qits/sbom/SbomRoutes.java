package eu.wohlben.qits.sbom;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.control.SbomRegistryService;
import eu.wohlben.qits.artifacts.error.SbomException;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.registry.BlobSender;
import eu.wohlben.qits.registry.OciRequestBody;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The SBOM wire, at {@code /artifacts/sboms/<packageType>/<packageName>/-/<version>}.
 *
 * <p>Two verbs and one idea, the daemon wire's shape with a document where it has a binary: a
 * streaming {@code PUT} that publishes one CycloneDX document, and a {@code GET} that serves it back
 * byte for byte out of the blob store.
 *
 * <p><b>The path IS the {@code SoftwareRelease} identity.</b> {@code (packageType, packageName,
 * version)} is spelled exactly as qits-ci announces it, so a consumer of that event fetches the
 * document with no translation step — which is the whole reason one shared attachment surface exists
 * instead of a metadata slot on each of four wires that could not carry one.
 *
 * <p><b>First-write-wins, and that differs from the daemon 409 on purpose.</b> A daemon re-publish
 * means a release ran twice and is worth failing loudly; this {@code PUT} sits <em>inside</em> that
 * same release run, one step after the artifact publish, and release steps are retried and replayed
 * as a matter of course. A 409 here would turn every replay of a green release into a red one over a
 * document already exactly where it belongs, so a re-PUT answers {@code 200} describing the row that
 * stands, with {@code alreadyPublished: true}. The property that matters is not "published once" but
 * <b>immutability</b> — the stored document can never <em>change</em> — and that holds either way.
 * It is also what keeps this surface safe to leave tokenless.
 *
 * <p><b>There is no authentication here, at all</b> — the stance {@code /v2}, {@code
 * /artifacts/npm}, {@code /artifacts/maven}, {@code /artifacts/daemons} and {@code /artifacts/docs}
 * all take, and for the same reason: on qits-net producers are trusted, and a publish surface that
 * needed a credential store would need one before the platform can build anything. What a publish
 * cannot do is change anything, and a consumer verifies the document against the digest this route
 * echoes, so integrity comes from addressing rather than from write auth. Machine auth arrives
 * wholesale with qits-platform-idp, for every surface at once; gating this one alone would report a
 * decision nobody took.
 *
 * <p><b>No {@code BodyHandler}, anywhere in this class.</b> {@code BodyHandler.create()} defaults to
 * 10 MiB with nothing in the log to say so — the trap the daemon publish would have fallen into. The
 * {@code PUT} streams through {@code OciRequestBody} into a bounded in-memory buffer instead, capped
 * by {@code qits.artifacts.sbom.max-size}.
 */
@ApplicationScoped
public class SbomRoutes {

  /** Waits for the NEXT chunk, not the whole upload — the registry's idle-timeout shape. */
  private static final Duration UPLOAD_IDLE_TIMEOUT = Duration.ofMinutes(1);

  /** How much of the upload is moved into the buffer per read. */
  private static final int COPY_BUFFER = 32 * 1024;

  /** The one wire media type a CycloneDX document has, echoed with the document's own version. */
  private static final String CYCLONEDX_JSON = "application/vnd.cyclonedx+json";

  /**
   * The {@code specVersion} values this store accepts.
   *
   * <p>Not "whatever parses": the version decides what the document <em>means</em>, and a consumer
   * that has to handle every revision CycloneDX ever shipped is a consumer nobody writes. 1.4 is the
   * floor because it is the first with the {@code vulnerabilities} shape, 1.6 the ceiling because it
   * is the newest anything in this platform emits. Widening it is a one-line decision, taken once.
   */
  private static final Set<String> SPEC_VERSIONS = Set.of("1.4", "1.5", "1.6");

  /** The only {@code bomFormat} a CycloneDX document may declare — the format's own self-check. */
  private static final String BOM_FORMAT = "CycloneDX";

  @Inject SbomRegistryService registry;
  @Inject BlobStore blobStore;
  @Inject BlobSender blobSender;

  /**
   * The cap on one document.
   *
   * <p>A CycloneDX document for a real dependency tree is tens to hundreds of kilobytes, so 16M is
   * three orders of magnitude of headroom rather than a snug fit — deliberately far below the
   * daemon's 256M, because this body is buffered whole in memory to be parsed and a cap that lets a
   * publisher hold a quarter of a gigabyte of heap per request is not a cap. It is a config knob and
   * not a constant for the reason every sibling's is: it has to be able to move with {@code
   * quarkus.http.limits.max-body-size}, a deployment's budget rather than a property of the format.
   */
  @ConfigProperty(name = "qits.artifacts.sbom.max-size", defaultValue = "16M")
  MemorySize maxSize;

  void init(@Observes Router router) {
    // Order is not what keeps these apart — SbomPaths' segment shape is, and SbomPathsTest pins it.
    // They are registered longest-first anyway, so a future loosening of a character class shows up
    // as a wrong answer rather than as an unreachable route.

    // HEAD is NOT derived from GET by Vert.x — every GET route needs its HEAD twin, or every client
    // that probes before downloading sees a 404.
    //
    // Every read is registered UNORDERED — the trailing `false` — and the publish keeps the ordered
    // default: ordered tasks of one event loop run one at a time from a queue every request on that
    // loop shares, so a read parked on a slow client would hold up every read behind it (qits-1510).
    router
        .headWithRegex(SbomPaths.DOCUMENT)
        .blockingHandler(guarded("head document", rc -> serve(rc, false)), false);
    router
        .getWithRegex(SbomPaths.DOCUMENT)
        .blockingHandler(guarded("get document", rc -> serve(rc, true)), false);

    router
        .getWithRegex(SbomPaths.PACKAGE)
        .blockingHandler(guarded("list versions", this::versions), false);

    // The publish PUT streams rather than buffers off the request, for the two reasons
    // OciRequestBody exists: a BodyHandler would cap at 10 MiB, and a raw HttpServerRequest read is
    // bounded by nothing.
    router
        .putWithRegex(SbomPaths.DOCUMENT)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("publish", this::publish));

    // DELETE is deliberately unimplemented, exactly as on /v2, /artifacts/npm, /artifacts/maven,
    // /artifacts/daemons and /artifacts/docs: the store is append-only and retiring a document is
    // the GC strategy's job, not a verb a publisher holds. 405 rather than 404, which would read as
    // "unknown package".
    router
        .route(HttpMethod.DELETE, SbomPaths.BASE + "/*")
        .handler(rc -> SbomErrors.send(rc, 405, "this sbom store does not implement delete"));

    // Everything else under the base is a short plain-text 404, never the SPA's HTML: a pipeline
    // step that reads a 200 text/html as its answer reports a green publish that never happened.
    router.route(SbomPaths.BASE).handler(this::notFound);
    router.route(SbomPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    SbomErrors.send(rc, 404, "not a route this sbom store serves: " + rc.normalizedPath());
  }

  // --- GET / HEAD -------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD …/<packageType>/<packageName>/-/<version>} — the document, streamed from the
   * store.
   *
   * <p>Anonymous, like every read in this service. Immutable on top and content-addressed
   * underneath, so the bytes behind this URL can never mean something else and the response says so
   * with the same cache header a maven release carries.
   */
  private void serve(RoutingContext rc, boolean withBody) {
    String packageType = requirePackageType(rc);
    String packageName = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireSbomRepository(ArtifactsRepositorySeeder.SBOMS);

    SbomRegistryService.StoredSbom stored =
        registry
            .find(ArtifactsRepositorySeeder.SBOMS, packageType, packageName, version)
            .orElseThrow(
                () ->
                    new SbomException(
                        404,
                        "no such sbom document: "
                            + packageType
                            + " "
                            + packageName
                            + " version "
                            + version));
    long size;
    try {
      size = blobStore.size(stored.blobId());
    } catch (Exception missing) {
      throw new SbomException(
          404,
          "the bytes of the sbom for " + packageName + " " + version + " are not stored");
    }

    // Size first, then touch — a row whose bytes are gone is a 404, not an access. HEAD counts, the
    // stance the OCI manifest route already takes. This is the access basis the GC window reads, and
    // qits-platform-maintenance's re-reads are what move it.
    registry.touchDocument(ArtifactsRepositorySeeder.SBOMS, packageType, packageName, version);

    HttpServerResponse response =
        rc.response()
            // The document's own specVersion travels on the media type, which is how CycloneDX
            // itself parameterises it — a reader that only handles 1.6 can refuse on the header.
            .putHeader(
                HttpHeaders.CONTENT_TYPE, CYCLONEDX_JSON + "; version=" + stored.specVersion())
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
            .putHeader(HttpHeaders.ETAG, "\"" + stored.blobId() + "\"")
            // The digest, on the response as well as on the publish. It is what lets a consumer
            // verify the document it just read without asking a second endpoint.
            .putHeader("Docker-Content-Digest", "sha256:" + stored.blobId())
            .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
    if (!withBody) {
      // HEAD must carry the same Content-Length as GET, and BlobSender writes a body
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }
    blobSender.send(
        response, stored.blobId(), "sbom " + packageType + " " + packageName + "@" + version);
  }

  /**
   * {@code GET …/<packageType>/<packageName>} — every stored version of one package, newest first.
   *
   * <p>An unknown package is a {@code 404} rather than an empty list: "no sbom has ever been
   * published for this artifact" is an answer a maintenance scan has to be able to tell apart from
   * "this package exists and has none", and only the first of those is reachable here — a row is the
   * only thing that makes a package known.
   */
  private void versions(RoutingContext rc) {
    String packageType = requirePackageType(rc);
    String packageName = rc.pathParam("name");
    registry.requireSbomRepository(ArtifactsRepositorySeeder.SBOMS);

    List<SbomRegistryService.StoredSbom> found =
        registry.listVersions(ArtifactsRepositorySeeder.SBOMS, packageType, packageName);
    if (found.isEmpty()) {
      throw new SbomException(404, "no such sbom package: " + packageType + " " + packageName);
    }
    // Newest first — the service orders it, so nothing here re-sorts and no second ordering can
    // disagree with the one the store applied.
    JsonArray versions = new JsonArray();
    for (SbomRegistryService.StoredSbom stored : found) {
      versions.add(
          new JsonObject()
              .put("version", stored.version())
              .put("createdAt", stored.createdAt().toString())
              .put("sizeBytes", stored.sizeBytes())
              .put("specVersion", stored.specVersion())
              .put("digest", "sha256:" + stored.blobId()));
    }
    respond(
        rc,
        200,
        new JsonObject()
            .put("packageType", packageType)
            .put("packageName", packageName)
            .put("versions", versions));
  }

  // --- PUT --------------------------------------------------------------------------------------

  /**
   * {@code PUT …/<packageType>/<packageName>/-/<version>} — the publish. Read, validate, stage,
   * promote, insert.
   *
   * <p>The body is read into memory rather than staged straight through, because the document has to
   * be <em>parsed</em> before anything is stored: a body that is not a CycloneDX document is a 400,
   * and a store that promoted its bytes first would answer that 400 having already written a
   * row-less blob for every malformed publish.
   *
   * <p>Re-publishing an identity is {@code 200} with {@code alreadyPublished: true} and the stored
   * row described — see the class javadoc for why that is not the daemon's 409.
   */
  private void publish(RoutingContext rc) {
    String packageType = requirePackageType(rc);
    String packageName = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireSbomRepository(ArtifactsRepositorySeeder.SBOMS);

    byte[] bytes = readBounded(rc);
    if (bytes.length == 0) {
      throw new SbomException(400, "an empty body is not an sbom document");
    }

    JsonObject document;
    try {
      document = new JsonObject(Buffer.buffer(bytes));
    } catch (RuntimeException malformed) {
      throw new SbomException(400, "the body is not a JSON document: " + malformed.getMessage());
    }
    if (!BOM_FORMAT.equals(document.getValue("bomFormat"))) {
      throw new SbomException(
          400,
          "not a CycloneDX document: bomFormat must be \""
              + BOM_FORMAT
              + "\", got "
              + describe(document.getValue("bomFormat")));
    }
    Object declared = document.getValue("specVersion");
    if (!(declared instanceof String specVersion) || !SPEC_VERSIONS.contains(specVersion)) {
      throw new SbomException(
          400,
          "unsupported specVersion "
              + describe(declared)
              + "; this store accepts "
              + String.join(", ", new TreeSet<>(SPEC_VERSIONS)));
    }

    BlobStore.StagedBlob staged =
        blobStore.stage(new ByteArrayInputStream(bytes), maxSize.asLongValue());
    blobStore.promote(staged);
    SbomRegistryService.Published published =
        registry.publish(
            ArtifactsRepositorySeeder.SBOMS,
            packageType,
            packageName,
            version,
            staged.sha256(),
            staged.size(),
            specVersion);

    // A JsonObject, not a DTO. A type serialised only inside a Vert.x handler is invisible to the
    // native-image build, so this stack adds zero reflection configuration — the rule registry, npm,
    // maven, daemon and docs all follow.
    JsonObject answer = describe(published.stored());
    if (published.alreadyPublished()) {
      // The EXISTING row, not what was just sent: the re-sent bytes are discarded, and the answer
      // has to describe what the store actually serves or a replayed release would report a digest
      // nothing has.
      respond(rc, 200, answer.put("alreadyPublished", true));
      return;
    }
    respond(rc, 201, answer);
  }

  /**
   * Reads the request body into memory, refusing at {@link #maxSize}.
   *
   * <p><b>This is the only thing bounding a chunked upload.</b> The global {@code
   * quarkus.http.limits.max-body-size} gates a declared {@code Content-Length} and nothing else, so
   * a body sent with no length — what {@code curl --upload-file} of a piped document produces — is
   * gated by this loop alone. Enforced <em>while</em> reading rather than after, so an oversized
   * body is refused at the cap instead of buffered whole first.
   */
  private byte[] readBounded(RoutingContext rc) {
    long limit = maxSize.asLongValue();
    ByteArrayOutputStream buffered = new ByteArrayOutputStream();
    try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis())) {
      byte[] chunk = new byte[COPY_BUFFER];
      int read;
      while ((read = body.read(chunk)) != -1) {
        if (buffered.size() + (long) read > limit) {
          throw new SbomException(
              413,
              "the sbom document exceeds qits.artifacts.sbom.max-size (" + limit + " bytes)");
        }
        buffered.write(chunk, 0, read);
      }
    } catch (IOException e) {
      throw new SbomException(400, "the upload stream failed: " + e.getMessage());
    }
    return buffered.toByteArray();
  }

  // --- shared -----------------------------------------------------------------------------------

  /**
   * The type check the path grammar deliberately does not make.
   *
   * <p>{@code SbomPaths.PACKAGE_TYPE} matches any short lowercase word so an unknown type reaches a
   * handler and gets a {@code 400} naming the allowed set, rather than a {@code 404} that reads as
   * "no such route". Applied on the reads as well as on the publish: a caller that misspells a type
   * should get one answer, not "unknown type" from one verb and "not found" from another.
   */
  private static String requirePackageType(RoutingContext rc) {
    String packageType = rc.pathParam("packageType");
    if (!SbomRegistryService.PACKAGE_TYPES.contains(packageType)) {
      throw new SbomException(
          400,
          "unknown package type '"
              + packageType
              + "'; this store serves "
              + String.join(", ", new TreeSet<>(SbomRegistryService.PACKAGE_TYPES)));
    }
    return packageType;
  }

  private static JsonObject describe(SbomRegistryService.StoredSbom stored) {
    return new JsonObject()
        .put("packageType", stored.packageType())
        .put("packageName", stored.packageName())
        .put("version", stored.version())
        .put("digest", "sha256:" + stored.blobId())
        .put("sizeBytes", stored.sizeBytes())
        .put("specVersion", stored.specVersion())
        .put("createdAt", stored.createdAt().toString());
  }

  /** A refusal has to be able to quote what it refused, including {@code null} and a non-string. */
  private static String describe(Object value) {
    return value == null ? "nothing" : "\"" + value + "\"";
  }

  private void respond(RoutingContext rc, int status, JsonObject body) {
    byte[] bytes = body.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
        .end(Buffer.buffer(bytes));
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
        SbomErrors.fail(rc, what, thrown);
      }
    };
  }
}
