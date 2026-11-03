package eu.wohlben.qits.artifacts.gc;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;

/**
 * The bearer every pin read carries: this service's own {@code qits:system} token (qits-1410,
 * feature qits-1406).
 *
 * <p><b>Why a read needs one at all.</b> The five pin sources are dialled at their edge hosts,
 * {@code https://<label>.qits.<domain>}, and the edge refuses an anonymous request on a service
 * vhost. Before that move every read went out bare on qits-net, where callers were trusted.
 *
 * <p><b>An interface here, the mint in {@code service}.</b> This module dials its peers but holds no
 * identity: the token is minted by the deployable's named oidc client {@code qits}, from the
 * deployer's {@code idp:client} resource, and the {@code service} module provides the one bean. A
 * deployable that bundles this module without providing one fails at build on the unsatisfied
 * injection point — a loud failure rather than a quiet default that sends nothing.
 *
 * <p><b>Empty is not an error.</b> A token that cannot be minted (the client is off, the idp is
 * unreachable) answers empty, the read goes out bare, the edge answers 401, and the reader's own
 * refusal names the source — which is what fails the run closed, exactly as any other unreachable
 * source does.
 */
public interface PinSourceBearer {

  /** The bearer to present, or empty when there is none to present. */
  Optional<String> token();

  /**
   * A GET of {@code url} carrying {@code bearer}'s token when it has one. {@code bearer} may be null:
   * a reader built outside CDI (the contract tests) presents nothing.
   */
  static HttpRequest.Builder get(String url, PinSourceBearer bearer) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    if (bearer != null) {
      bearer.token().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    }
    return request;
  }
}
