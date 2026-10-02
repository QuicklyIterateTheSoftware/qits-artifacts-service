package eu.wohlben.qits.contenthash;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.control.JpaContentHashLedger;
import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenRegistryService;
import eu.wohlben.qits.artifacts.control.MavenVersionOrder;
import eu.wohlben.qits.artifacts.control.NpmRegistryService;
import eu.wohlben.qits.artifacts.control.NpmSemver;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The content-hash read, at {@code /artifacts/content-hashes/<maven|npm>/<name>/-/<version|newest>}
 * (epic qits-620).
 *
 * <p>Answers which version of a name exists and what content hash its publisher recorded — the
 * question the qits CLI asks before a {@code publish: if-changed} upload. The 200 body is {@code
 * {ecosystem, name, version, contentHash}}, {@code contentHash} {@code null} when the version was
 * published without one (a hand {@code mvnw deploy}, or anything before the CLI sent it). A {@code
 * 404} means the version does not exist — or, for {@code newest}, that no version of the name does;
 * any other failure is a 5xx, never a 404, because the CLI reads a 404 as "publish".
 *
 * <p><b>Both ecosystems read the hosted repository</b> — {@code maven} and {@code npm}, the ones
 * {@code $QITS_MAVEN_REGISTRY_URL} and {@code $QITS_NPM_REGISTRY_URL} end in — and "exists" is the
 * registry's own fact, not the ledger's: a maven version exists when its pom is stored ({@code
 * HttpArtifactPresence}'s probe), an npm version when its {@code npm_version} row does.
 *
 * <p><b>"Newest" is by version order, never a tag</b>: {@link MavenVersionOrder} for maven, {@link
 * NpmSemver} precedence for npm. {@code dist-tags.latest} is deliberately not consulted — a replay
 * publishes an older version under another tag and leaves {@code latest} elsewhere, and an operator
 * may move {@code latest} back; neither changes which version is highest.
 *
 * <p><b>No authentication, the sbom GET's stance</b>: every read in this service is anonymous, and
 * {@code PublishGuard} claims only the publish verbs of the six wires. A content hash is no more
 * secret than the artifact it describes.
 */
@ApplicationScoped
public class ContentHashRoutes {

  private static final Logger LOG = Logger.getLogger(ContentHashRoutes.class);

  @Inject MavenRegistryService maven;
  @Inject NpmRegistryService npm;
  @Inject JpaContentHashLedger ledger;

  void init(@Observes Router router) {
    // HEAD is NOT derived from GET by Vert.x — the twin is registered explicitly, as on every wire.
    router
        .headWithRegex(ContentHashPaths.VERSIONED)
        .blockingHandler(guarded("head content hash", rc -> serve(rc, false)));
    router
        .getWithRegex(ContentHashPaths.VERSIONED)
        .blockingHandler(guarded("get content hash", rc -> serve(rc, true)));

    // Everything else under the base — an unknown ecosystem included — is a short plain-text 404,
    // never the SPA's HTML.
    router.route(ContentHashPaths.BASE).handler(this::notFound);
    router.route(ContentHashPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    send(rc, 404, "not a route this content-hash surface serves: " + rc.normalizedPath());
  }

  private void serve(RoutingContext rc, boolean withBody) {
    String ecosystem = rc.pathParam("ecosystem");
    String name = rc.pathParam("name");
    String requested = rc.pathParam("version");

    List<String> versions =
        "maven".equals(ecosystem) ? mavenVersions(name) : npmVersions(name);
    Optional<String> version =
        ContentHashPaths.NEWEST.equals(requested)
            ? versions.stream().max(orderOf(ecosystem))
            : versions.stream().filter(requested::equals).findFirst();
    if (version.isEmpty()) {
      throw new NoSuchVersion(
          ContentHashPaths.NEWEST.equals(requested)
              ? "no version of " + ecosystem + " " + name + " is published"
              : "no such version: " + ecosystem + " " + name + "@" + requested);
    }

    String repository = repositoryOf(ecosystem);
    JsonObject body =
        new JsonObject()
            .put("ecosystem", ecosystem)
            .put("name", name)
            .put("version", version.get())
            .put(
                "contentHash",
                ledger.find(ecosystem, repository, name, version.get()).orElse(null));
    byte[] bytes = body.encode().getBytes(StandardCharsets.UTF_8);
    rc.response()
        .setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
        // The answer moves whenever a newer version lands, and a cached "unchanged" is a publish
        // skipped — so nothing here may be served from a cache.
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    if (withBody) {
      rc.response().end(Buffer.buffer(bytes));
    } else {
      rc.response().end();
    }
  }

  /**
   * The release versions of {@code groupId:artifactId} whose pom is stored.
   *
   * <p>One prefix scan under {@code <g path>/<a>}, keeping the files that are exactly {@code
   * <v>/<a>-<v>.pom} one directory down. That shape also rejects a deeper group that happens to
   * share the prefix ({@code eu.wohlben.qits.qits-foo:x} under {@code eu/wohlben/qits/qits-foo/}).
   * Snapshot versions are skipped: the ledger never records one and the CLI never asks for one.
   */
  private List<String> mavenVersions(String name) {
    int colon = name.indexOf(':');
    if (colon <= 0
        || colon == name.length() - 1
        || name.indexOf(':', colon + 1) >= 0
        || name.indexOf('/') >= 0) {
      throw new NoSuchVersion("not a maven groupId:artifactId: " + name);
    }
    String artifactId = name.substring(colon + 1);
    String directory = name.substring(0, colon).replace('.', '/') + "/" + artifactId;
    String prefix = directory + "/";
    // listUnder appends the "/%" itself, as a LIKE — so '_' in a name is a wildcard there, and the
    // startsWith below is what keeps the match literal.
    return maven.listUnder(ArtifactsRepositorySeeder.MAVEN, directory).stream()
        .map(MavenRegistryService.StoredPath::path)
        .filter(path -> path.startsWith(prefix))
        .map(path -> path.substring(prefix.length()))
        .map(rest -> pomVersion(rest, artifactId))
        .flatMap(Optional::stream)
        .filter(version -> !MavenLayout.isSnapshotVersion(version))
        .distinct()
        .toList();
  }

  private static Optional<String> pomVersion(String rest, String artifactId) {
    int slash = rest.indexOf('/');
    if (slash <= 0) {
      return Optional.empty();
    }
    String version = rest.substring(0, slash);
    return rest.substring(slash + 1).equals(artifactId + "-" + version + ".pom")
        ? Optional.of(version)
        : Optional.empty();
  }

  private List<String> npmVersions(String name) {
    return npm.listVersions(ArtifactsRepositorySeeder.NPM, name).stream()
        .map(NpmRegistryService.StoredVersion::version)
        .toList();
  }

  private static String repositoryOf(String ecosystem) {
    return "maven".equals(ecosystem) ? ArtifactsRepositorySeeder.MAVEN : ArtifactsRepositorySeeder.NPM;
  }

  /**
   * Ascending version order. npm's publish refuses a non-semver version, so every stored one parses;
   * one that somehow does not sorts lowest rather than being guessed at.
   */
  private static Comparator<String> orderOf(String ecosystem) {
    if ("maven".equals(ecosystem)) {
      return MavenVersionOrder.INSTANCE;
    }
    return (left, right) -> {
      Optional<NpmSemver> parsedLeft = NpmSemver.parse(left);
      Optional<NpmSemver> parsedRight = NpmSemver.parse(right);
      if (parsedLeft.isEmpty() || parsedRight.isEmpty()) {
        return parsedLeft.isPresent() == parsedRight.isPresent()
            ? left.compareTo(right)
            : (parsedLeft.isPresent() ? 1 : -1);
      }
      return parsedLeft.get().compareTo(parsedRight.get());
    };
  }

  /** The one refusal this surface makes on purpose; everything else escaping a handler is a 500. */
  private static final class NoSuchVersion extends RuntimeException {
    NoSuchVersion(String message) {
      super(message, null, false, false);
    }
  }

  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (NoSuchVersion missing) {
        send(rc, 404, missing.getMessage());
      } catch (Throwable thrown) {
        LOG.errorf(thrown, "content-hashes: %s", what);
        send(rc, 500, "internal content-hash store error");
      }
    };
  }

  private static void send(RoutingContext rc, int status, String message) {
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(message);
  }
}
