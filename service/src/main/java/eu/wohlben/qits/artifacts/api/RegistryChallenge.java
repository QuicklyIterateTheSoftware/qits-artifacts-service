package eu.wohlben.qits.artifacts.api;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Optional;

/**
 * The two strings the docker Bearer-token dance is made of: the {@code WWW-Authenticate} challenge
 * that names this store's own token endpoint, and the Distribution spec's error envelope.
 *
 * <p><b>Why this exists at all.</b> docker and buildkit send a credential only after they have been
 * challenged — an anonymous {@code /v2} push that is simply refused with 403 is a push that never
 * retries with anything. So closing {@link PublishGuard}'s anonymous gap needs a 401 carrying a
 * {@code realm} the caller can reach, and {@link RegistryTokenEndpoint} is what stands at the other
 * end of it. The edge (qits-edge-platform-service, {@code EdgeAuth}) already implements the same
 * dance, but only on the configured application vhost {@code registry.<env>.<domain>}; CI steps
 * reach this store by its qits-net alias {@code dev-qits-artifacts:8080}, which never passes
 * through the edge, so the store needs a token endpoint and a challenge of its own.
 *
 * <p><b>{@link #challenge} is what {@link PublishGuard} answers an anonymous {@code /v2} publish
 * with</b>, since the flip of 2026-09-20 — the surface's {@link PublishGuard.Anonymous} state is
 * {@code REFUSE_WITH_CHALLENGE}, and it is the only one of the six that carries a challenge rather
 * than a plain 401, because it is the only one whose clients do the token dance. The three surfaces
 * still open ({@code npm}, {@code maven}, {@code docs}) reach neither door.
 *
 * <h2>Where the realm points</h2>
 *
 * <p>The realm is derived from the <b>inbound request</b> — its scheme and its authority, plus
 * {@link #TOKEN_PATH} — rather than configured. One deployment serves two front doors: a CI step
 * arriving at {@code http://dev-qits-artifacts:8080/v2/…} must be sent back to {@code
 * dev-qits-artifacts:8080}, and a docker client arriving through the edge at {@code
 * https://registry.<env>.<domain>/v2/…} must be sent to the edge. A configured realm could only
 * ever name one of the two, and would send half the callers to a host they cannot resolve.
 * {@code qits.artifacts.registry.token-realm} is therefore an <b>override</b> and ships empty, for
 * the deployment that one day fronts this store with something that rewrites neither {@code Host}
 * nor {@code X-Forwarded-Proto}.
 */
final class RegistryChallenge {

  /**
   * This store's token endpoint, inside the {@code /artifacts} prefix {@code deployments.yml}
   * declares (its {@code routes: /artifacts,/v2}), so the one path is reachable both on qits-net
   * and through the edge's projection. It collides with nothing: the five hosted wires live under
   * {@code /artifacts/{npm,maven,daemons,docs,sboms}/}, the JSON API under {@code /artifacts/api}
   * and the framework's own surface under {@code /artifacts/q}.
   */
  static final String TOKEN_PATH = "/artifacts/token";

  /** Not in Vert.x' HttpHeaders constants, so it is spelled once here. */
  static final String WWW_AUTHENTICATE = "WWW-Authenticate";

  /** What a reverse proxy in front of this process says it terminated. */
  private static final String FORWARDED_PROTO = "X-Forwarded-Proto";

  private RegistryChallenge() {}

  /**
   * The {@code WWW-Authenticate} value for a request that arrived here with no usable credential.
   *
   * <p>Bearer alone, unlike the edge's two-challenge answer: the clients that cannot do the token
   * dance (maven, npm) do not reach {@code /v2}, and a Basic challenge ahead of the Bearer one
   * sends docker to store-and-resend instead of to the token endpoint.
   *
   * @param request the request being refused; its scheme and authority are the default realm
   * @param realmOverride {@code qits.artifacts.registry.token-realm}, empty unless a deployment
   *     sets it
   */
  static String bearerChallenge(HttpServerRequest request, Optional<String> realmOverride) {
    return bearerChallenge(scheme(request), authority(request), realmOverride);
  }

  /**
   * The challenge string itself, with no request behind it so the exact bytes can be pinned by a
   * plain unit test — a challenge docker does not parse fails with no message anywhere.
   *
   * <p>{@code service} is the authority the caller actually used, whatever the realm says: docker
   * echoes it back as the {@code service} query parameter, and it is how a token endpoint shared by
   * two front doors can tell which one a request came from.
   */
  static String bearerChallenge(
      String scheme, String authority, Optional<String> realmOverride) {
    String realm =
        realmOverride
            .filter(configured -> !configured.isBlank())
            .map(String::strip)
            .orElseGet(() -> scheme + "://" + authority + TOKEN_PATH);
    return "Bearer realm=\"" + realm + "\",service=\"" + authority + "\"";
  }

  /**
   * The challenge {@link RegistryTokenEndpoint} itself sends. Basic <b>alone</b>, and that is the
   * difference from {@link #bearerChallenge}: this is the endpoint that sells bearer tokens, so
   * naming Bearer here would point a client back at the door it is already knocking on.
   */
  static String basicChallenge(String authority) {
    return "Basic realm=\"" + authority + "\"";
  }

  /**
   * Ends a request with 401, the challenge above and the Distribution spec's error body — the whole
   * of what {@link PublishGuard}'s {@code REFUSE_WITH_CHALLENGE} surface answers with. The caller
   * puts {@code Connection: close} on the response first, because the client may still be pushing.
   */
  static void challenge(
      HttpServerRequest request, Optional<String> realmOverride, String message) {
    request
        .response()
        .setStatusCode(401)
        .putHeader(WWW_AUTHENTICATE, bearerChallenge(request, realmOverride))
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(errors("UNAUTHORIZED", message).encode());
  }

  /**
   * The OCI error envelope, the shape {@link PublishGuard}'s own refusal already sends on
   * {@code /v2/}: {@code {"errors":[{"code":…,"message":…}]}}.
   */
  static JsonObject errors(String code, String message) {
    return new JsonObject()
        .put(
            "errors",
            new JsonArray().add(new JsonObject().put("code", code).put("message", message)));
  }

  /** {@code https} when something in front terminated TLS and said so, else the socket's own. */
  static String scheme(HttpServerRequest request) {
    String forwarded = request.getHeader(FORWARDED_PROTO);
    if (forwarded != null && forwarded.equalsIgnoreCase("https")) {
      return "https";
    }
    return request.scheme() == null ? "http" : request.scheme();
  }

  /**
   * The authority the caller addressed, from {@code Host} — which is what makes the derived realm
   * point back at the door the request came in through.
   */
  static String authority(HttpServerRequest request) {
    String host = request.getHeader(HttpHeaders.HOST);
    if (host == null && request.authority() != null) {
      host = request.authority().toString();
    }
    return safeAuthority(host);
  }

  /**
   * The host-name charset and nothing else. A {@code Host} header is attacker-controlled and this
   * value is reflected into a response header; anything outside this set would let a caller write
   * a quote, a comma or a newline into the challenge and so forge a second directive.
   */
  static String safeAuthority(String host) {
    if (host == null) {
      return "";
    }
    StringBuilder safe = new StringBuilder(host.length());
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '-'
              || c == ':';
      if (ok) {
        safe.append(c);
      }
    }
    return safe.toString();
  }
}
