package eu.wohlben.qits;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;
import jakarta.json.Json;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A test issuer for the machine tokens qits-platform-idp mints, and the profile that turns
 * enforcement on.
 *
 * <p>The suite needs no qits-platform-idp and no HTTP stub: quarkus-oidc verifies a bearer against
 * {@code quarkus.oidc.public-key} when one is configured, so this fixture signs with a keypair
 * whose public half it hands to Quarkus. Everything the deployed path does still happens — real
 * RS256 signature, real issuer, real expiry, and the real {@code quarkus.oidc.token.audience} check
 * — only the key distribution is local.
 *
 * <p>The keypair is a FIXED one in {@code src/test/resources}, not a generated one, and that is not
 * a shortcut. A {@code @QuarkusTest} loads this class twice — once in the test classloader that
 * signs, once in the application's, which reads the profile — so a keypair generated in a static
 * initialiser would be two different keypairs and every signature would fail verification. A
 * checked-in test key signs nothing a deployment trusts.
 *
 * <p>Use {@link Enforced} as the test profile and one of the role factories below as the
 * Authorization value — each mints the one platform audience and differs only in {@code groups},
 * which is what decides anything here. {@link #forAnotherAudience()} is a correctly signed token
 * addressed somewhere else entirely, which is how "a token exists but is not ours" gets asserted
 * without collapsing into "no token".
 */
public final class MachineTokens {

  /**
   * What a deployed qits-platform-idp would put in {@code iss}. Any URL works; it just has to
   * match.
   */
  public static final String ISSUER = "https://qits-platform-idp.test/idp";

  /**
   * The one platform audience — the {@code aud} every machine token carries, and {@code
   * qits.auth.machine.audience}.
   */
  public static final String AUDIENCE = "qits-platform";

  /** The client qits-platform-idp minted the token for, which is what lands in {@code sub}. */
  private static final String SUBJECT = "qits-platform-artifacts";

  private static final String SIGNING_KEY = "/machine-token-signing-key.pem";

  /** The public half of {@link #SIGNING_KEY}, base64 DER — the form the config key reads. */
  private static final String PUBLIC_KEY =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2qrClQP1+1Ue9uiVSctC6ciV1fSlft/PywhLRRqZzDU6"
          + "hL9t8mWS14EuRDjfxH8zZtI3mQoTCAS5iVPhepiMSLam7n8SLfOw9YtNTbguqN9jNHsJ9w8Xrkwo+gFTa09Q"
          + "sE6xa7B9MVVlR1MNYQuPp/n3s1slSqGta7/608gfZtyvwi/3POTfQ1ZQO0PR/t685/vISFZCQXxvjxA3+P0h"
          + "hBDJvCO89U8VjwKQN0c3iBDrsyDUiJa5S6WYpepg3yqgihFoztm6kXd8EGFzb/FfXwMO1i64xi5KgNVkqzK8"
          + "GYwM9Q4rFQPqvJLD98RAbJQ+7166lBDFuV4ReSsBAKCANQIDAQAB";

  /**
   * The enforced posture: the gate on, and this fixture's key as the one quarkus-oidc trusts.
   *
   * <p>{@code auth-server-url} and {@code jwks-path} are blanked because a configured public key is
   * the alternative to an OIDC server connection, not an addition to one — left set, the tenant
   * would try to reach a host named qits-platform-idp that no test has.
   */
  public static class Enforced implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.machine.required", "true",
          "quarkus.oidc.auth-server-url", "",
          "quarkus.oidc.jwks-path", "",
          "quarkus.oidc.public-key", PUBLIC_KEY,
          "quarkus.oidc.token.issuer", ISSUER);
    }
  }

  /**
   * A valid bearer carrying an audience this platform never issues: correctly signed, and refused
   * by quarkus-oidc before any guard runs.
   */
  public static String forAnotherAudience() {
    return withRoles(Set.of("qits:system"), "some-other-audience");
  }

  /** A CI run's commissioned credential: {@code qits:ci-run}, the platform audience. */
  public static String forCiRun() {
    return withRoles(Set.of("qits:ci-run"), AUDIENCE);
  }

  /** A platform service's credential: {@code qits:system}, the platform audience. */
  public static String forSystem() {
    return withRoles(Set.of("qits:system"), AUDIENCE);
  }

  /** A person's {@code qits} CLI token: {@code qits:admin}, the platform audience. */
  public static String forAdmin() {
    return withRoles(Set.of("qits:admin"), AUDIENCE);
  }

  /** An agent's commissioned credential: {@code qits:agent}, the platform audience. */
  public static String forAgent() {
    return withRoles(Set.of("qits:agent"), AUDIENCE);
  }

  /**
   * A signed token with exactly these roles in {@code groups}, for the given audiences. {@code aud}
   * is spelled as a JSON array explicitly, because that is what qits-platform-idp emits even for one
   * audience — and a builder left to itself collapses a single value to a bare string, which would
   * test a shape no token ever has.
   */
  public static String withRoles(Set<String> roles, String... audiences) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject(SUBJECT)
        .claim("aud", Json.createArrayBuilder(List.of(audiences)).build())
        // The IDP copies the configured client roles into `groups`; @RolesAllowed consumes this
        // claim after OIDC has authenticated the token. Audience-only tokens authenticate but are
        // correctly forbidden, which was the stale fixture behind the service-suite failures.
        .groups(roles)
        .expiresIn(Duration.ofMinutes(5))
        .sign(signingKey());
  }

  private static PrivateKey signingKey() {
    try {
      return KeyUtils.readPrivateKey(SIGNING_KEY);
    } catch (Exception e) {
      throw new IllegalStateException("test signing key " + SIGNING_KEY, e);
    }
  }

  private MachineTokens() {}
}
