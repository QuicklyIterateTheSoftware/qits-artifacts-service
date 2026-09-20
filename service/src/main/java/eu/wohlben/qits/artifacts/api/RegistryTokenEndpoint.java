package eu.wohlben.qits.artifacts.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * This store's own docker-registry token endpoint, at {@link RegistryChallenge#TOKEN_PATH}
 * ({@code /artifacts/token}): HTTP Basic in, a short-lived bearer out.
 *
 * <h2>Why the store needs one</h2>
 *
 * <p>{@code docker login} stores a password and resends it forever, while an idp token lives about
 * five minutes and cannot be refreshed. The Distribution spec's answer is the Bearer token endpoint
 * flow, and docker and buildkit send a credential <b>only</b> after a {@code 401
 * WWW-Authenticate: Bearer realm="…"} names one. The edge implements exactly this
 * (qits-edge-platform-service, {@code EdgeAuth.token}) — but only on the configured application
 * vhost {@code registry.<env>.<domain>}, and a CI step reaches this store by its qits-net alias
 * {@code dev-qits-artifacts:8080}, which the edge never sees. So the realm a challenge from this
 * process names has to be served by this process, and this is it.
 *
 * <h2>What it does, and what it deliberately does not</h2>
 *
 * <p>The client id and secret that arrive are relayed to qits-platform-idp's token endpoint
 * <b>verbatim</b> — the {@code Authorization} header exactly as it came in, {@code
 * grant_type=client_credentials} and nothing else. This process decides nothing about the
 * credential beyond its SHAPE, stores none of it and logs none of it; idp authenticates the client
 * and decides its audiences, as it does for every other machine caller.
 *
 * <p><b>No {@code audience} parameter</b>, which asks idp for the client's whole allowed list. The
 * alternative — naming one here — makes a client that lacks it fail at idp with an {@code
 * invalid_target} the caller cannot read. Judging the token is {@code quarkus-oidc}'s job when the
 * caller comes back with it, where the reason is known.
 *
 * <p><b>This endpoint is itself uncredentialed, necessarily: it is the door that BUYS a
 * credential.</b> Three things keep it that way, and all three are asserted by
 * {@code RegistryTokenEndpointTest}. {@link PublishGuard#isPublish} claims only {@code /v2/} and
 * the five named {@code /artifacts/<wire>/} prefixes, so {@code /artifacts/token} falls through it
 * untouched. {@link AdminWriteGuard} is JAX-RS and sees only what RESTEasy dispatches under
 * {@code /artifacts/api}, which this is not. And no {@code quarkus.http.auth.permission} rule
 * exists in this repository at all.
 *
 * <p><b>Nothing in the main tree challenges anyone yet.</b> {@link PublishGuard} still lets an
 * anonymous publisher through — the known gap — so today this endpoint is reachable, correct and
 * unused. It ships first on purpose: the flip that makes the anonymous branch call {@link
 * RegistryChallenge#challenge} is only safe once the realm it advertises already answers.
 *
 * <p><b>Every arm ends a response.</b> That is the whole requirement docker places on a token
 * endpoint: the client reaches it from a challenge it was handed and has no timeout of its own, so
 * an arm that answers nothing is a client that waits forever rather than one that fails. A missing
 * or malformed credential is answered here with no call at all; a call is bounded on both sides by
 * {@code qits.artifacts.registry.idp-timeout}, and both of its outcomes end a response.
 */
@ApplicationScoped
public class RegistryTokenEndpoint {

  private static final Logger LOG = Logger.getLogger(RegistryTokenEndpoint.class);

  private static final String BASIC = "Basic ";

  /** RFC 6749 §4.4, and the only grant this endpoint ever asks for. */
  private static final String CLIENT_CREDENTIALS = "grant_type=client_credentials";

  /**
   * The eleventh outbound client in this repository, and it carries the same native-image rule as
   * the other ten: an <b>instance</b> field, never a static one, or native-image freezes an
   * {@code HttpClientFacade} into the image heap and the build fails. The rule travels with the
   * client.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /**
   * qits-platform-idp's token endpoint. Derived in {@code application.properties} from {@code
   * quarkus.oidc.auth-server-url} rather than configured as a second address, so a deployment that
   * moves the idp moves it once and the validator and this relay cannot drift apart.
   */
  @ConfigProperty(name = "qits.artifacts.registry.idp-token-url")
  String idpTokenUrl;

  /** Both halves of the wait: a connect that never completes, and a call that is never answered. */
  @ConfigProperty(name = "qits.artifacts.registry.idp-timeout", defaultValue = "10S")
  Duration idpTimeout;

  /**
   * The advertised realm, empty unless a deployment overrides it — see {@link RegistryChallenge}
   * for why deriving it from the inbound request is the default. {@code Optional<String>}, not
   * {@code String}: SmallRye reads a configured-EMPTY value as absent, and a {@code String}
   * injection point would die at boot on "Failed to load config value" the moment this key ships
   * blank (the {@code mirror.endpoint-override} lesson, in AGENTS.md's native table).
   */
  @ConfigProperty(name = "qits.artifacts.registry.token-realm")
  Optional<String> tokenRealm;

  @Inject ObjectMapper objectMapper;

  /**
   * Both verbs, because clients differ: docker GETs the realm with {@code ?service=&scope=}, while
   * anything written against RFC 6749 POSTs. Neither the query nor a form body is read — the
   * permission is the audience the minted token already carries, and per-repository scopes would be
   * a change to the platform's claim model rather than to this class.
   *
   * <p>A blocking handler, like every other route in this service that does I/O: the relay is one
   * small JSON POST on a worker thread, and a token request is a rare event on a pool of 40.
   */
  void init(@Observes Router router) {
    router.get(RegistryChallenge.TOKEN_PATH).blockingHandler(this::token);
    router.post(RegistryChallenge.TOKEN_PATH).blockingHandler(this::token);
  }

  void token(RoutingContext rc) {
    HttpServerRequest request = rc.request();
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization == null || !authorization.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
      // Basic, not Bearer: asking for a bearer at the endpoint that sells them would be a loop.
      // docker sends the credential it stored with `docker login` when it sees this.
      tokenChallenge(request, "client credentials required");
      return;
    }
    if (!isClientCredentials(authorization.substring(BASIC.length()).strip())) {
      // A header that says Basic and carries no id and secret — an empty credential store, a
      // truncated helper answer. There is nothing to ask idp, and asking would hold the client for
      // the whole timeout while an unreachable idp is waited out.
      tokenChallenge(request, "client credentials required");
      return;
    }

    HttpResponse<String> answer;
    try {
      answer =
          client.send(
              HttpRequest.newBuilder(URI.create(idpTokenUrl))
                  .timeout(idpTimeout)
                  .header(HttpHeaders.AUTHORIZATION.toString(), authorization)
                  .header(
                      HttpHeaders.CONTENT_TYPE.toString(), "application/x-www-form-urlencoded")
                  .header(HttpHeaders.ACCEPT.toString(), "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(CLIENT_CREDENTIALS))
                  .build(),
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      unavailable(request, interrupted);
      return;
    } catch (Exception unreachable) {
      unavailable(request, unreachable);
      return;
    }

    if (answer.statusCode() != 200) {
      // An ANSWER is idp deciding, and it is never retried: repeating the question would turn one
      // refusal into a burst of them. The status is logged; the credential never is.
      LOG.warnf("the identity provider refused a token request with %d", answer.statusCode());
      request
          .response()
          .setStatusCode(401)
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
          .end(
              RegistryChallenge.errors(
                      "UNAUTHORIZED", "the identity provider refused these credentials")
                  .encode());
      return;
    }

    String accessToken;
    JsonNode issued;
    try {
      issued = objectMapper.readTree(answer.body());
      accessToken = issued.path("access_token").asText(null);
    } catch (Exception unreadable) {
      unavailable(request, unreadable);
      return;
    }
    if (accessToken == null || accessToken.isBlank()) {
      // 200 with no token in it is not a refusal to pass on — the caller did nothing wrong, the
      // far side answered something this cannot use.
      unavailable(request, new IllegalStateException("no access_token in the grant response"));
      return;
    }

    JsonObject dockerToken = new JsonObject();
    // `token` is what the docker CLI reads; `access_token` is the same string under the name the
    // OAuth2 half of the spec uses, and clients differ about which they look for. Both, always.
    dockerToken.put("token", accessToken);
    dockerToken.put("access_token", accessToken);
    JsonNode expiresIn = issued.get("expires_in");
    if (expiresIn != null && expiresIn.isNumber()) {
      dockerToken.put("expires_in", expiresIn.asLong());
    }
    dockerToken.put("issued_at", Instant.now().toString());
    request
        .response()
        .setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        // A token response is never cached, anywhere (RFC 6749 §5.1).
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(dockerToken.encode());
  }

  /** The 401 that asks for the stored {@code docker login} credential. */
  private void tokenChallenge(HttpServerRequest request, String message) {
    request
        .response()
        .setStatusCode(401)
        .putHeader(
            RegistryChallenge.WWW_AUTHENTICATE,
            RegistryChallenge.basicChallenge(RegistryChallenge.authority(request)))
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(RegistryChallenge.errors("UNAUTHORIZED", message).encode());
  }

  /**
   * 502, the edge's answer for the same case: the caller's credential may be perfectly good, so a
   * 401 would send docker off to fix a credential that is not the problem.
   */
  private void unavailable(HttpServerRequest request, Throwable cause) {
    LOG.errorf(cause, "could not reach the identity provider at %s", idpTokenUrl);
    request
        .response()
        .setStatusCode(502)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(
            RegistryChallenge.errors(
                    "UNAVAILABLE", "the identity provider could not be reached")
                .encode());
  }

  /**
   * Whether this is base64 of {@code <client id>:<secret>} — RFC 7617's shape, and nothing about
   * whether idp knows it. The credential is decoded HERE and nowhere else, and neither half of it
   * is held, logged or carried past this call.
   */
  static boolean isClientCredentials(String credential) {
    if (credential == null || credential.isBlank()) {
      return false;
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(credential), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
    int colon = decoded.indexOf(':');
    return colon > 0 && colon < decoded.length() - 1;
  }
}
