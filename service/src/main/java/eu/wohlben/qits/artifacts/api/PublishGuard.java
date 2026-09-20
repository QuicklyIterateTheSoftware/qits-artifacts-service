package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Only CI publishes (USER RULING 2026-09-13). On the six wire publish routes, a caller that
 * presents an identity must be a CI run: its roles must hold {@code qits:ci-run}. Every other
 * presented identity is refused — a person ({@code qits:admin}) by bearer or by the forwarded
 * {@code X-Qits-*} pair, an agent ({@code qits:agent}), and a platform service ({@code
 * qits:system}), which is an explicit exception to the open calling model.
 *
 * <p>The publish routes are {@code POST}/{@code PATCH}/{@code PUT} on {@code /v2/…} and {@code
 * PUT} on {@code /artifacts/npm/…}, {@code /artifacts/maven/…}, {@code /artifacts/daemons/…},
 * {@code /artifacts/docs/…} and {@code /artifacts/sboms/…}. Reads, and the {@code DELETE}s the
 * wires answer with 405, are not publishing and pass untouched.
 *
 * <p><b>A caller that presents no identity still passes, and that is a known gap, not a
 * decision.</b> Every publisher on qits-net writes anonymously today: the bootstrap's seeds, and
 * the CI steps that reach this service by its qits-net alias (maven, npm, daemons, docs, SBOMs).
 * Closing that needs each of them to present the run's credential first; until then, refusing the
 * anonymous caller would stop every release. Two things follow from it:
 *
 * <ul>
 *   <li>A bearer that is not a JWT (npm's mandatory {@code _authToken} ceremony, {@code
 *       qits-ci}, {@code qits-bootstrap}) is no identity and passes as anonymous. It is never
 *       handed to quarkus-oidc, so it cannot be refused as an invalid token.
 *   <li>A JWT is validated and judged by its roles. One that fails validation (wrong audience,
 *       expired, foreign signature) is refused with 401.
 *   <li>A Basic credential is refused with 401. The edge passes a client's {@code id:secret}
 *       through unchanged, and this service cannot check a secret, so it cannot tell a CI run's
 *       pair from an agent's or a service's. A CI run presents its bearer (the edge's docker token
 *       exchange turns the run's pair into one).
 * </ul>
 *
 * <p><b>The other half of closing that gap already ships.</b> docker and buildkit send a credential
 * only after a {@code 401 WWW-Authenticate: Bearer realm="…"} names an endpoint they can reach, so
 * the flip needs a realm before it needs a refusal. {@link RegistryTokenEndpoint} serves one at
 * {@code /artifacts/token} and {@link RegistryChallenge#challenge} builds the header — both live,
 * both tested, and deliberately called by nothing here yet. The change that closes the gap replaces
 * the {@code rc.next()} in the anonymous branch of {@link #filter} with that one call.
 *
 * <p>A bearer is judged only while the machine-token gate {@code qits.auth.machine.required} is
 * on, because with it off there is no OIDC tenant to validate one. The forwarded pair is judged
 * either way: it needs no validation, it is believed the way {@code ForwardAuthMechanism} believes
 * it.
 *
 * <p><b>Why a filter and not a handler on each route.</b> The npm, maven and {@code /v2} routes
 * are registered by the qits-registries jars; this service cannot put a handler into their chains.
 * A Quarkus {@link Filters} entry runs before every route, and at {@link #PRIORITY} it runs after
 * Quarkus' own authentication handler (priority 200), which is what puts the deferred identity on
 * the routing context. With {@code quarkus.http.auth.proactive=false} nothing resolves that
 * identity unless asked, and this filter asks only for a JWT on a publish route.
 */
@ApplicationScoped
public class PublishGuard {

  private static final Logger LOG = Logger.getLogger(PublishGuard.class);

  /** The role of a CI run's commissioned credential — the only publisher. */
  public static final String CI_RUN = "qits:ci-run";

  /** Below Quarkus' authentication (200) and authorization (100) filters, above every route. */
  static final int PRIORITY = 50;

  /** Three base64url segments: the shape of a JWS. Anything else is not a token this idp mints. */
  private static final Pattern JWT = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  /** A publish surface: a path prefix and the methods that write under it. */
  record Surface(String prefix, Set<HttpMethod> methods) {}

  /**
   * Every surface that creates content. Extended by hand when a wire is added — a publish route
   * outside this list is unguarded.
   */
  static final List<Surface> SURFACES =
      List.of(
          new Surface("/v2/", Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT)),
          new Surface("/artifacts/npm/", Set.of(HttpMethod.PUT)),
          new Surface("/artifacts/maven/", Set.of(HttpMethod.PUT)),
          new Surface("/artifacts/daemons/", Set.of(HttpMethod.PUT)),
          new Surface("/artifacts/docs/", Set.of(HttpMethod.PUT)),
          new Surface("/artifacts/sboms/", Set.of(HttpMethod.PUT)));

  @ConfigProperty(name = "qits.auth.forward.user-header")
  String userHeader;

  @ConfigProperty(name = "qits.auth.forward.roles-header")
  String rolesHeader;

  @Inject MachineAuth machineAuth;

  void register(@Observes Filters filters) {
    filters.register(this::filter, PRIORITY);
  }

  /** True for a method and normalized path that publish. */
  static boolean isPublish(HttpMethod method, String path) {
    if (path == null) {
      return false;
    }
    for (Surface surface : SURFACES) {
      if (path.startsWith(surface.prefix())) {
        return surface.methods().contains(method);
      }
    }
    return false;
  }

  void filter(RoutingContext rc) {
    String path = rc.normalizedPath();
    if (!isPublish(rc.request().method(), path)) {
      rc.next();
      return;
    }

    // The forwarded pair first: the edge asserts it for a signed-in person, and anything on
    // qits-net can send it. Either way it names an identity, and that identity must be a CI run.
    String user = rc.request().getHeader(userHeader);
    if (user != null && !user.isBlank()) {
      Set<String> roles = roles(rc.request().getHeader(rolesHeader));
      if (!roles.contains(CI_RUN)) {
        refuse(rc, 403, "only a CI run publishes; " + user + " holds " + roles);
        return;
      }
    }

    // A Basic pair is a client's id and secret — through the edge it reaches here verbatim. This
    // service cannot check it against the idp, so it cannot tell a CI run from an agent or a
    // service. No publisher sends one; a CI run presents its bearer.
    String authorization = rc.request().getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      refuse(rc, 401, "a publish takes a CI run's bearer token, not a Basic credential");
      return;
    }

    String jwt = jwtBearer(authorization);
    if (jwt == null || !machineAuth.enforced()) {
      // No identity presented (or none this service can validate): qits-net trust, the known gap.
      rc.next();
      return;
    }

    Uni<SecurityIdentity> deferred = rc.get(QuarkusHttpUser.DEFERRED_IDENTITY_KEY);
    if (deferred == null) {
      // Quarkus' authentication handler did not run. A token nobody can judge is not a pass.
      refuse(rc, 401, "the bearer token cannot be validated here");
      return;
    }
    // Validation is asynchronous. Pause the body so no chunk is lost before the route's own
    // handler takes it: pauseForWorker keeps it paused, a BodyHandler resumes it.
    rc.request().pause();
    Context context = Vertx.currentContext();
    deferred
        .subscribe()
        .with(
            identity -> onContext(context, () -> judge(rc, identity)),
            failure ->
                onContext(context, () -> refuse(rc, 401, "the bearer token is not valid here")));
  }

  private void judge(RoutingContext rc, SecurityIdentity identity) {
    if (!MachineIdentity.isMachine(identity)) {
      refuse(rc, 401, "the bearer token is not valid here");
      return;
    }
    if (!identity.hasRole(CI_RUN)) {
      refuse(
          rc,
          403,
          "only a CI run publishes; "
              + identity.getPrincipal().getName()
              + " holds "
              + identity.getRoles());
      return;
    }
    rc.next();
  }

  private static void onContext(Context context, Runnable action) {
    if (context == null) {
      action.run();
    } else {
      context.runOnContext(ignored -> action.run());
    }
  }

  /**
   * Ends the request with the wire's own error shape. The body is drained and the connection
   * closed, because the client may still be sending what it meant to publish.
   */
  private static void refuse(RoutingContext rc, int status, String message) {
    LOG.warnf(
        "Refused %s %s with %d: %s", rc.request().method(), rc.normalizedPath(), status, message);
    if (rc.response().ended()) {
      return;
    }
    String path = rc.normalizedPath();
    String contentType;
    String body;
    if (path.startsWith("/v2/")) {
      contentType = "application/json";
      String code = status == 401 ? "UNAUTHORIZED" : "DENIED";
      body =
          new JsonObject()
              .put(
                  "errors",
                  new JsonArray().add(new JsonObject().put("code", code).put("message", message)))
              .encode();
    } else if (path.startsWith("/artifacts/npm/")) {
      contentType = "application/json";
      body = new JsonObject().put("error", message).encode();
    } else {
      contentType = "text/plain; charset=utf-8";
      body = message + "\n";
    }
    rc.request().resume();
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, contentType)
        .putHeader(HttpHeaders.CONNECTION, "close")
        .end(body);
  }

  /** The token of a {@code Bearer} header when it has the shape of a JWT, else null. */
  static String jwtBearer(String authorization) {
    if (authorization == null || authorization.length() < 7) {
      return null;
    }
    if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String token = authorization.substring(7).strip();
    return JWT.matcher(token).matches() ? token : null;
  }

  static Set<String> roles(String header) {
    Set<String> roles = new LinkedHashSet<>();
    if (header == null) {
      return roles;
    }
    for (String role : header.split(",")) {
      String read = role.strip();
      if (!read.isEmpty()) {
        roles.add(read);
      }
    }
    return roles;
  }
}
