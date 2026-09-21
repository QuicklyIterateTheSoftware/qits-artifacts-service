package eu.wohlben.qits.artifacts.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * What an accepted publish says it was published by. {@link PublishGuard#publisher} is the only
 * thing that builds that description, which is why pinning it here pins the whole accept log: the
 * four call sites differ in what they pass, not in how it is rendered.
 *
 * <p><b>Plain JUnit, no {@code @QuarkusTest} and no {@code @TestProfile}</b> — the function is
 * static and pure precisely so this suite costs nothing (AGENTS.md's test-profile budget rule: a
 * profile is a whole Quarkus app). Asserting the same property through a log handler would need a
 * running application to say something a string comparison already says.
 */
class PublishGuardPublisherTest {

  @Test
  void aCallerWithNoNameIsExactlyAnonymous() {
    // The word is load-bearing: it is what a reader greps for while counting how many publishers
    // on a still-open surface have not adopted the run's credential yet.
    assertEquals("anonymous", PublishGuard.publisher(null, null));
  }

  @Test
  void anEmptyOrBlankNameIsAnonymousToo() {
    // A forwarded header is a string from the wire; blank is no name, not a name made of spaces.
    assertEquals("anonymous", PublishGuard.publisher("", null));
    assertEquals("anonymous", PublishGuard.publisher("   ", null));
  }

  @Test
  void theTwoNamelessAcceptsAreToldApart() {
    // Gate off is "nothing could have presented a credential"; the open-surface accept is "a
    // publisher could have and did not". Only the second one is the rollout's business.
    assertEquals("anonymous (gate off)", PublishGuard.publisher(null, "gate off"));
    assertEquals("anonymous", PublishGuard.publisher(null, null));
  }

  @Test
  void aNamedPublisherIsItsNameAndTheForwardedPairSaysSo() {
    assertEquals("dyn-ci-run-1f2e", PublishGuard.publisher("dyn-ci-run-1f2e", null));
    assertEquals(
        "dyn-ci-run-1f2e (forwarded)", PublishGuard.publisher("dyn-ci-run-1f2e", "forwarded"));
  }

  @Test
  void noCredentialCanRideOutInTheDescription() {
    // The guard never hands this function a token or an Authorization header — but the assertion
    // that matters to a reader of the log is the one made from the outside: an anonymous publish
    // renders as one word, so there is no field a bearer could have been rendered into.
    String anonymous = PublishGuard.publisher(null, null);
    assertEquals("anonymous".length(), anonymous.length());
    assertFalse(anonymous.contains("."));
    assertFalse(anonymous.contains("Bearer"));
  }
}
