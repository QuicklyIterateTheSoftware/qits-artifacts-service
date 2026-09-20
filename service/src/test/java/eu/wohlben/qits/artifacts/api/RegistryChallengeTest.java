package eu.wohlben.qits.artifacts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.vertx.core.http.HttpMethod;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The exact bytes of the {@code WWW-Authenticate} challenge, pinned without an application: a
 * challenge docker does not parse fails with no message anywhere, so the string itself is the
 * contract rather than the code that builds it.
 *
 * <p>Nothing in the main tree sends this header yet — {@link PublishGuard} still lets an anonymous
 * publisher through, and the change that makes it call {@link RegistryChallenge#challenge} is a
 * later one. This is what makes that flip a one-line diff rather than a design.
 */
class RegistryChallengeTest {

  /**
   * The in-network case, and the reason the realm is derived rather than configured: a CI step
   * reaches this store by its qits-net alias and must be sent back to that alias.
   */
  @Test
  void aQitsNetCallerIsSentBackToTheAliasItCalled() {
    assertEquals(
        "Bearer realm=\"http://dev-qits-artifacts:8080/artifacts/token\","
            + "service=\"dev-qits-artifacts:8080\"",
        RegistryChallenge.bearerChallenge("http", "dev-qits-artifacts:8080", Optional.empty()));
  }

  /** The other front door the SAME deployment serves, from the same code and no configuration. */
  @Test
  void aCallerThroughTheEdgeIsSentToTheEdge() {
    assertEquals(
        "Bearer realm=\"https://registry.dev.example.com/artifacts/token\","
            + "service=\"registry.dev.example.com\"",
        RegistryChallenge.bearerChallenge(
            "https", "registry.dev.example.com", Optional.empty()));
  }

  /**
   * The override, for a front door that rewrites neither {@code Host} nor {@code
   * X-Forwarded-Proto}. It replaces the realm ONLY: {@code service} stays the authority the caller
   * actually used, because that is what docker echoes back and what tells one door from the other.
   */
  @Test
  void aConfiguredRealmReplacesTheDerivedOne() {
    assertEquals(
        "Bearer realm=\"https://registry.dev.example.com/artifacts/token\","
            + "service=\"dev-qits-artifacts:8080\"",
        RegistryChallenge.bearerChallenge(
            "http",
            "dev-qits-artifacts:8080",
            Optional.of("https://registry.dev.example.com/artifacts/token")));
  }

  /**
   * A blank override is the shipped value of {@code qits.artifacts.registry.token-realm} — and
   * SmallRye hands a configured-empty key over as absent, so both spellings have to mean "derive
   * it" or the deployment that ships the key blank advertises {@code realm=""}.
   */
  @Test
  void aBlankOverrideStillDerives() {
    String derived =
        RegistryChallenge.bearerChallenge("http", "dev-qits-artifacts:8080", Optional.empty());
    assertEquals(
        derived,
        RegistryChallenge.bearerChallenge("http", "dev-qits-artifacts:8080", Optional.of("   ")));
  }

  /** The token endpoint authenticates WITH Basic, so its own challenge names Basic and nothing else. */
  @Test
  void theTokenEndpointsOwnChallengeIsBasicAlone() {
    assertEquals(
        "Basic realm=\"dev-qits-artifacts:8080\"",
        RegistryChallenge.basicChallenge("dev-qits-artifacts:8080"));
  }

  /**
   * The {@code Host} header is attacker-controlled and this value is reflected into a response
   * header: a quote, a comma, an equals or a newline in it would forge a second directive in the
   * challenge. The filter keeps the host-name charset — so a forged suffix survives as inert
   * letters and the punctuation that would have given it meaning does not.
   */
  @Test
  void theAuthorityCarriesNothingThatCouldForgeADirective() {
    String forged =
        RegistryChallenge.safeAuthority("evil.example.com:8080\",service=\"other\"\r\n");
    assertEquals("evil.example.com:8080serviceother", forged);
    String challenge = RegistryChallenge.bearerChallenge("http", forged, Optional.empty());
    assertEquals(
        "Bearer realm=\"http://evil.example.com:8080serviceother/artifacts/token\","
            + "service=\"evil.example.com:8080serviceother\"",
        challenge);
    assertEquals("", RegistryChallenge.safeAuthority(null));
  }

  /** The Distribution spec's envelope, the shape {@link PublishGuard} already refuses with. */
  @Test
  void theErrorEnvelopeIsTheOneTheWireAlreadyUses() {
    assertEquals(
        "{\"errors\":[{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\"}]}",
        RegistryChallenge.errors("UNAUTHORIZED", "authentication required").encode());
  }

  /**
   * The endpoint the realm names is not a publish route, so {@link PublishGuard} never claims the
   * door that BUYS the credential it will one day demand. The wire half of this is in {@code
   * RegistryTokenEndpointTest}; this is the path arithmetic on its own.
   */
  @Test
  void theRealmPathIsNotAPublishRoute() {
    assertFalse(PublishGuard.isPublish(HttpMethod.GET, RegistryChallenge.TOKEN_PATH));
    assertFalse(PublishGuard.isPublish(HttpMethod.POST, RegistryChallenge.TOKEN_PATH));
    assertFalse(PublishGuard.isPublish(HttpMethod.PUT, RegistryChallenge.TOKEN_PATH));
  }
}
