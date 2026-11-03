package eu.wohlben.qits.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Commands;
import java.time.Duration;

/**
 * Who the packaged ITs and the stories are, as bearers the launched artifact validates.
 *
 * <p>The packaged process ({@code PackagedProcessIT.TargetDirState}) runs the shipped OIDC tenant
 * against this run's {@link MockIdp}, with the machine gate off. Every caller of the JSON API
 * presents a token minted there: a CI run's {@code qits:ci-run}, the platform's {@code
 * qits:system}, a signed-in operator's {@code qits:admin} — the operator's name in {@code sub}, the
 * platform audience, the roles in {@code groups}. Forward-auth — the {@code X-Qits-User} / {@code
 * X-Qits-Roles} pair the edge used to assert for a person — leaves qits-auth-core with qits-1338
 * (epic qits-543, qits-1358), so a person reaches this service the way a machine does.
 *
 * <p>The byte wires take none of this: with the gate off a publish is open and a read always is,
 * so the package-manager stories stay tokenless.
 */
public final class StoryIdentities {

  /** The one platform audience every token carries — the shipped {@code token.audience}. */
  public static final String AUDIENCE = "qits-platform";

  private StoryIdentities() {}

  /**
   * The {@code Authorization} value of {@code subject} holding {@code roles}, minted by the mock
   * idp the packaged process trusts. An hour, so a slow browser story never outlives it.
   */
  public static String bearer(String subject, String... roles) {
    return "Bearer "
        + MockIdp.attach()
            .token()
            .subject(subject)
            .audience(AUDIENCE)
            .groups(roles)
            .ttl(Duration.ofHours(1))
            .mint();
  }

  /** A signed-in operator's bearer: {@code qits:admin}. */
  public static String operator(String subject) {
    return bearer(subject, "qits:admin");
  }

  /** A CI run's bearer: {@code qits:ci-run}, the one role the media upload takes. */
  public static String ciRun(String subject) {
    return bearer(subject, "qits:ci-run");
  }

  /**
   * The {@code Authorization: Bearer <token>} header line a {@code curl -H} sends, with the token
   * {@link Commands#redact redacted} so the report shows the shape and never a minted JWT — which
   * is a secret, and different on every run.
   */
  public static String authorizationHeader(Commands commands, String bearer) {
    commands.redact(bearer.substring("Bearer ".length()));
    return "Authorization: " + bearer;
  }
}
