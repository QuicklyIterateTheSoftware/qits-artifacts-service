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
import java.util.Optional;
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
 * <p><b>The anonymous caller is refused on four of the six surfaces, and passes on the other
 * two.</b> Closing the gap is a rollout rather than a switch: a surface may only be flipped once
 * every publisher that still writes to it presents the run's credential, and refusing one before
 * that stops every release on the estate. {@link Anonymous} is that state, carried per {@link
 * Surface}, so the {@link #SURFACES} list below IS the rollout and flipping the next surface is one
 * word. Refused today: {@code /v2/} (with a challenge — see below), {@code /artifacts/maven/},
 * {@code /artifacts/daemons/} and {@code /artifacts/sboms/}. Still open: {@code /artifacts/npm/}
 * and {@code /artifacts/docs/}, whose remaining publishers are the {@code npm-library} and {@code
 * java-service} archetypes in the qits-qits wrapper — their credentialing change reaches CI only
 * once that wrapper is released. Four things follow from it:
 *
 * <ul>
 *   <li>A bearer that is not a JWT (npm's mandatory {@code _authToken} ceremony, {@code
 *       qits-ci}, {@code qits-bootstrap}) is no identity and is judged as anonymous — passed on an
 *       open surface, refused on a flipped one. It is never handed to quarkus-oidc, so it cannot be
 *       refused as an invalid token.
 *   <li>A JWT is validated and judged by its roles. One that fails validation (wrong audience,
 *       expired, foreign signature) is refused with 401.
 *   <li>A Basic credential is refused with 401 on every surface. The edge passes a client's {@code
 *       id:secret} through unchanged, and this service cannot check a secret, so it cannot tell a
 *       CI run's pair from an agent's or a service's. A CI run presents its bearer (the edge's
 *       docker token exchange turns the run's pair into one).
 *   <li>With the machine-token gate off nothing is refused, on any surface. {@code
 *       quarkus.oidc.tenant-enabled} follows the same key, so with the gate off there is no tenant
 *       to validate the credential a refusal would be demanding — a refusal there makes the store
 *       unusable rather than stricter. The anonymous branch therefore sits <b>under</b> {@code
 *       machineAuth.enforced()}, never beside it.
 * </ul>
 *
 * <p><b>{@code /v2/} is refused WITH a challenge, and that is not decoration.</b> docker and
 * buildkit send a credential only after a {@code 401 WWW-Authenticate: Bearer realm="…"} names an
 * endpoint they can reach; a bare 401 there is a push that never retries with anything. {@link
 * RegistryTokenEndpoint} serves that endpoint at {@code /artifacts/token} and {@link
 * RegistryChallenge#challenge} writes the header. The other three flipped surfaces take a plain
 * 401: {@code qits-publish daemon submit}, {@code qits-publish sbom submit} and maven itself hold
 * the run's bearer already and speak no token dance, so a challenge there would name a door nobody
 * knocks on.
 *
 * <p><b>Why {@code /artifacts/maven/} could be flipped, and why it takes a plain 401.</b>
 * qits-ci-service {@code 2026.921.80307} injects the credential into every step: {@code BOOTSTRAP}
 * writes {@code /tmp/qits-deploy-settings.xml} carrying a {@code <server id="qits">} whose
 * configuration is an {@code Authorization: Bearer} HTTP header, and appends {@code -gs} to {@code
 * MAVEN_ARGS}. {@code qits} is the repository id every {@code
 * -DaltDeploymentRepository="qits::default::…"} names, so that one file covers <b>all</b> maven
 * publishers at once — the seven repositories that also pass a {@code -gs} of their own, and the
 * five {@code maven-library} archetype repositories whose recipe could not be edited ahead of a
 * wrapper release. Measured against the live store before the flip: deploying with that exact
 * settings file to a nonexistent repository answered <b>403</b> ({@code "only a CI run publishes;
 * dyn-workspace-… holds [qits:agent, …]"}), which is the guard reading the header as an identity —
 * an uncredentialed deploy would instead have reached the route and answered 404. So maven sends
 * the header <b>preemptively</b>, out of the {@code <server>} entry, and never does a
 * 401-then-retry: a {@code WWW-Authenticate: Bearer} here would be noise it cannot act on, which is
 * why this surface is {@link Anonymous#REFUSE} and not {@link Anonymous#REFUSE_WITH_CHALLENGE}.
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

  /** What the message says on a surface where anonymous is refused. */
  private static final String NO_CREDENTIAL =
      "only a CI run publishes; this publish carried no credential";

  /**
   * What a surface does with a caller this service can put no name to — the rollout state of the
   * CI-only publish rule, one value per surface. It is data rather than a chain of {@code if}s so
   * that flipping the next surface is one word in {@link #SURFACES}, and so that the list itself
   * says how far the rollout has come.
   *
   * <p>Read only while the machine-token gate is on; with it off every surface behaves as {@link
   * #ALLOW_ANONYMOUS}.
   */
  enum Anonymous {
    /**
     * Passed through: a publisher on this surface still writes with no credential, and refusing it
     * would stop every release. The state every surface was in before 2026-09-20.
     */
    ALLOW_ANONYMOUS,

    /** Refused with a plain 401. Every publisher here holds the run's bearer already. */
    REFUSE,

    /**
     * Refused with a 401 carrying {@link RegistryChallenge#bearerChallenge} — the only answer a
     * docker or buildkit client can recover from, because it names the token endpoint to go and
     * buy a credential at.
     */
    REFUSE_WITH_CHALLENGE
  }

  /**
   * A publish surface: a path prefix, the methods that write under it, and what it does with a
   * caller that presents no identity this service can judge.
   */
  record Surface(String prefix, Set<HttpMethod> methods, Anonymous anonymous) {}

  /**
   * Every surface that creates content. Extended by hand when a wire is added — a publish route
   * outside this list is unguarded — and the third component is the rollout: four surfaces refuse
   * the anonymous publisher, two still carry it. The two open ones are published to by the {@code
   * npm-library} and {@code java-service} archetypes in the qits-qits wrapper, and flip when a
   * wrapper release carries their credentialing change into CI.
   */
  static final List<Surface> SURFACES =
      List.of(
          // buildctl and `docker push`: they send a credential only after being challenged.
          new Surface(
              "/v2/",
              Set.of(HttpMethod.POST, HttpMethod.PATCH, HttpMethod.PUT),
              Anonymous.REFUSE_WITH_CHALLENGE),
          new Surface("/artifacts/npm/", Set.of(HttpMethod.PUT), Anonymous.ALLOW_ANONYMOUS),
          // Every maven publisher presents the run's bearer: qits-ci's BOOTSTRAP writes the
          // settings file that carries it, and appends `-gs` to MAVEN_ARGS.
          new Surface("/artifacts/maven/", Set.of(HttpMethod.PUT), Anonymous.REFUSE),
          // `qits-publish daemon submit`, and the bootstrap's own credential.
          new Surface("/artifacts/daemons/", Set.of(HttpMethod.PUT), Anonymous.REFUSE),
          new Surface("/artifacts/docs/", Set.of(HttpMethod.PUT), Anonymous.ALLOW_ANONYMOUS),
          // `qits-publish sbom submit`, from the composed postlude.
          new Surface("/artifacts/sboms/", Set.of(HttpMethod.PUT), Anonymous.REFUSE));

  @ConfigProperty(name = "qits.auth.forward.user-header")
  String userHeader;

  @ConfigProperty(name = "qits.auth.forward.roles-header")
  String rolesHeader;

  /**
   * The advertised realm, empty unless a deployment overrides it — {@link RegistryChallenge} says
   * why deriving it from the inbound request is the default, and {@link RegistryTokenEndpoint}
   * carries the same injection point. {@code Optional<String>} rather than {@code String}, because
   * SmallRye reads this configured-empty key as absent.
   */
  @ConfigProperty(name = "qits.artifacts.registry.token-realm")
  Optional<String> tokenRealm;

  @Inject MachineAuth machineAuth;

  void register(@Observes Filters filters) {
    filters.register(this::filter, PRIORITY);
  }

  /** True for a method and normalized path that publish. */
  static boolean isPublish(HttpMethod method, String path) {
    return surfaceOf(method, path) != null;
  }

  /**
   * The surface a request publishes on, or null when it publishes on none. The first prefix that
   * matches decides — a path under a wire's prefix is that wire's, and a method it does not write
   * with is not publishing at all rather than falling through to a later entry.
   */
  static Surface surfaceOf(HttpMethod method, String path) {
    if (path == null) {
      return null;
    }
    for (Surface surface : SURFACES) {
      if (path.startsWith(surface.prefix())) {
        return surface.methods().contains(method) ? surface : null;
      }
    }
    return null;
  }

  void filter(RoutingContext rc) {
    String path = rc.normalizedPath();
    Surface surface = surfaceOf(rc.request().method(), path);
    if (surface == null) {
      rc.next();
      return;
    }

    // The forwarded pair first: the edge asserts it for a signed-in person, and anything on
    // qits-net can send it. Either way it names an identity, and that identity must be a CI run.
    // It is TERMINAL either way: a pair naming a CI run has been judged, and falling through to be
    // judged a second time would turn a request the edge already authenticated into an anonymous
    // one the moment a surface flips.
    String user = rc.request().getHeader(userHeader);
    if (user != null && !user.isBlank()) {
      Set<String> roles = roles(rc.request().getHeader(rolesHeader));
      if (!roles.contains(CI_RUN)) {
        refuse(rc, 403, "only a CI run publishes; " + user + " holds " + roles);
        return;
      }
      rc.next();
      return;
    }

    // A Basic pair is a client's id and secret — through the edge it reaches here verbatim. This
    // service cannot check it against the idp, so it cannot tell a CI run from an agent or a
    // service. No publisher sends one; a CI run presents its bearer.
    String authorization = rc.request().getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
      refuse(rc, 401, "a publish takes a CI run's bearer token, not a Basic credential");
      return;
    }

    if (!machineAuth.enforced()) {
      // The gate is off, so there is no OIDC tenant at all and nothing could present a credential
      // this service would accept. Behaviour is exactly what it was before the idp existed, on
      // every surface — the refusal below lives UNDER this check and never beside it.
      rc.next();
      return;
    }

    String jwt = jwtBearer(authorization);
    if (jwt == null) {
      // No identity this service can put a name to. That is the anonymous publisher — and also
      // npm's ceremonial `Bearer qits-ci`, which is not three base64url segments and so never
      // reaches quarkus-oidc. The surface decides.
      switch (surface.anonymous()) {
        case ALLOW_ANONYMOUS -> rc.next();
        case REFUSE -> refuse(rc, 401, NO_CREDENTIAL);
        case REFUSE_WITH_CHALLENGE -> challenge(rc);
      }
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
   * Refuses an anonymous {@code /v2} publish the one way a docker or buildkit client can recover
   * from: 401 with {@code WWW-Authenticate: Bearer realm="…/artifacts/token"}, which is what sends
   * it to {@link RegistryTokenEndpoint} and back with a credential.
   *
   * <p>{@code Connection: close} is put on ahead of {@link RegistryChallenge#challenge} for the
   * same reason {@link #refuse} sets it — the client may still be pushing a layer at us — and
   * before rather than inside, so the shipped challenge stays the string {@code
   * RegistryChallengeTest} pins.
   */
  private void challenge(RoutingContext rc) {
    LOG.warnf(
        "Refused %s %s with 401 and a Bearer challenge: %s",
        rc.request().method(), rc.normalizedPath(), NO_CREDENTIAL);
    if (rc.response().ended()) {
      return;
    }
    // Resumed for the same reason {@link #refuse} resumes: the caller may still be sending a
    // payload. A challenge is usually answered on the small request that OPENS an upload —
    // docker's `POST /v2/<name>/blobs/uploads/` carries no body — but a token that expires
    // mid-push is challenged on a `PATCH` that is streaming a layer, and that one must not be
    // left half-read. Reaching here the request was never paused (only the bearer path pauses),
    // so this is a no-op on the common path and insurance on the rare one.
    rc.request().resume();
    rc.response().putHeader(HttpHeaders.CONNECTION, "close");
    RegistryChallenge.challenge(rc.request(), tokenRealm, NO_CREDENTIAL);
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
