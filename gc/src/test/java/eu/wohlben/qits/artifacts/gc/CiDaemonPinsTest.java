package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The ci pin adapter and the fold into {@link GcPins}, against a stub serving qits-ci's real shape.
 *
 * <p>Two semantics carry the whole safety of the daemon binaries, and neither is obvious from the
 * wire: <b>a blank version is an answer</b> meaning "this deployment has pinned no daemon", and a
 * <b>64-hex version pins a blob</b> as well as a row, because the pin has historically been a
 * sha256 digest fetched straight from the blob route.
 *
 * <p>Getting the first one wrong aborts every run on a platform that has published no daemon, which
 * is most of this platform's life so far. Getting the second wrong deletes the binary every CI step
 * downloads.
 */
class CiDaemonPinsTest {

  private static final String DIGEST = "c04a603e95cf".repeat(5) + "abcd";

  @Test
  void bothLadderRungsPinAndTheDaemonNameComesBackWithThem() throws IOException {
    try (StubPinService ci =
        StubPinService.serving(
            "/daemon",
            """
            {"daemonName":"qits-ci-daemon","daemonVersion":"2026.805.1",
             "previousDaemonVersion":"2026.804.9","source":"adopted"}
            """)) {
      GcPins pins = sources(ci.baseUrl()).fetch();

      assertTrue(pins.complete());
      assertEquals(Set.of("2026.805.1", "2026.804.9"), pins.daemonVersions());
      assertEquals(GcPins.BY_CI, pins.pinsDaemonVersion("qits-ci-daemon", "2026.805.1"));
      assertEquals(
          GcPins.BY_CI,
          pins.pinsDaemonVersion("qits-ci-daemon", "2026.804.9"),
          "the fallback rung pins too — deleting it removes the rung the ladder exists to have");
      assertNull(
          pins.pinsDaemonVersion("some-other-daemon", "2026.805.1"),
          "daemon_binary rows are keyed (name, version); another daemon's like-named version is not"
              + " what qits-ci pinned");
      assertNull(pins.pinsDaemonVersion("qits-ci-daemon", "2026.803.1"));
    }
  }

  @Test
  void aBlankVersionIsAnAnswerMeaningNoPinAndNeverAbortsTheRun() throws IOException {
    // The shipped default of a platform that has adopted no daemon. Treating it as "unknown" would
    // abort every plan and every sweep on exactly the deployments with the least to lose.
    try (StubPinService ci =
        StubPinService.serving(
            "/daemon",
            """
            {"daemonName":"qits-ci-daemon","daemonVersion":"","previousDaemonVersion":"",
             "source":"none"}
            """)) {
      GcPins pins = sources(ci.baseUrl()).fetch();

      assertTrue(pins.complete(), "an answer, not a failure");
      assertEquals(Set.of(), pins.daemonVersions());
      assertEquals(Set.of(), pins.blobs());
    }
  }

  @Test
  void aSixtyFourHexVersionAlsoPinsTheBlobAtThatDigest() throws IOException {
    // QITS_CI_DAEMON_VERSION has been a sha256 digest since the daemon shipped, fetched as
    // /v2/qits/ci-daemon/blobs/sha256:<digest> — so the pin may name bytes for which no
    // version-addressed row exists at all. Protecting only the row would leave those bytes exposed.
    try (StubPinService ci =
        StubPinService.serving(
            "/daemon",
            "{\"daemonName\":\"qits-ci-daemon\",\"daemonVersion\":\""
                + DIGEST
                + "\",\"previousDaemonVersion\":\"2026.804.9\",\"source\":\"configured\"}")) {
      GcPins pins = sources(ci.baseUrl()).fetch();

      assertEquals(Set.of(DIGEST), pins.blobs());
      assertEquals(GcPins.BY_CI, pins.pinsAnyBlob(Set.of("something-else", DIGEST)));
      assertNull(pins.pinsAnyBlob(Set.of("something-else")));
      assertEquals(
          GcPins.BY_CI,
          pins.pinsDaemonVersion("qits-ci-daemon", DIGEST),
          "a digest is still a version — it pins the row keyed with it as well as the bytes");
      assertFalse(
          pins.blobs().contains("2026.804.9"), "a calver version names no blob and never should");
    }
  }

  @Test
  void anUnreachableOrUnreadableQitsCiIsAFailureRatherThanAnEmptyAnswer() throws IOException {
    try (StubPinService ci = StubPinService.answering("/daemon", 500, "boom")) {
      GcPins pins = sources(ci.baseUrl()).fetch();

      assertFalse(pins.complete());
      assertEquals(1, pins.failures().size());
      assertTrue(pins.whyIncomplete().contains("qits-ci daemon pin"));
      assertTrue(pins.whyIncomplete().contains("500"));
    }
    GcPins closed = sources("http://127.0.0.1:1/ci/api").fetch();
    assertFalse(closed.complete());
    assertTrue(closed.whyIncomplete().contains("unreachable"));
  }

  @Test
  void everySourceIsAskedSoOneOutageNeverHidesAnother() throws IOException {
    // A run that stopped at the first failure would report one broken service and hide the other
    // five, and whoever fixed it would find the next run just as dead with nothing to say why.
    GcPinSources sources = sources("http://127.0.0.1:1/ci/api");
    sources.cd =
        () -> {
          throw new IllegalStateException("qits-platform-deployments unreachable at http://qits-platform-deployments:8080/platform-deployments/api/pins");
        };
    sources.ci =
        () -> {
          throw new IllegalStateException("qits-ci unreachable at http://qits-ci:8080/ci/api/daemon");
        };
    sources.maintenance =
        () -> {
          throw new IllegalStateException(
              "qits-platform-maintenance unreachable at http://qits-platform-maintenance:8080");
        };
    sources.configuration =
        () -> {
          throw new IllegalStateException(
              "qits-configuration unreachable at http://qits-configuration:8080");
        };
    sources.workspaces =
        () -> {
          throw new IllegalStateException(
              "qits-workspaces unreachable at http://qits-workspaces:8080");
        };
    sources.projects =
        () -> {
          throw new IllegalStateException(
              "qits-projects unreachable at http://qits-projects:8080");
        };

    GcPins pins = sources.fetch();

    assertEquals(6, pins.failures().size());
    assertTrue(pins.whyIncomplete().contains("qits-platform-deployments"));
    assertTrue(pins.whyIncomplete().contains("qits-ci"));
    assertTrue(pins.whyIncomplete().contains("qits-platform-maintenance"));
    assertTrue(pins.whyIncomplete().contains("qits-configuration"));
    assertTrue(pins.whyIncomplete().contains("qits-workspaces"));
    assertTrue(pins.whyIncomplete().contains("qits-projects"));
  }

  @Test
  void aPinnedIdentityIsKeptByTheEngineUnderThePinsOwnName() throws IOException {
    // The loop closed: pins fetched here, read by an engine through GcPinned, reported under the
    // named rule. The candidate is a year old and is not a release, so nothing but the pin can save
    // it — which is what makes the assertion about the pin rather than about the belt.
    try (StubPinService ci =
        StubPinService.serving(
            "/daemon",
            """
            {"daemonName":"qits-ci-daemon","daemonVersion":"2026.805.1",
             "previousDaemonVersion":"","source":"adopted"}
            """)) {
      GcPins pins = sources(ci.baseUrl()).fetch();
      FakeGcTypeAdapter adapter =
          new FakeGcTypeAdapter()
              .add(
                  "qits-ci-daemon@2026.805.1",
                  "qits-ci-daemon",
                  false,
                  java.time.Instant.now().minus(Duration.ofDays(365)),
                  "binary");
      GcPinned pinned =
          candidate ->
              pins.pinsDaemonVersion(
                  candidate.group(),
                  candidate.identity().substring(candidate.identity().lastIndexOf('@') + 1));

      GcStrategy.Plan plan =
          new OwnArtifactsStrategy()
              .plan(adapter, Duration.ofDays(90), java.time.Instant.now(), pinned);

      assertEquals(List.of(), plan.dead());
      assertEquals(GcPins.BY_CI, plan.kept().get(0).rule());
      assertNotNull(plan.blobsRetained());
      assertTrue(plan.blobsRetained().contains("binary"));
    }
  }

  /**
   * The six sources as CDI would wire them, with only ci at the stub and the other five answering
   * with nothing.
   *
   * <p>All six are set because they are all injection points: a collector built by hand with any of
   * them left null meets an NPE rather than the fold the case is about. Answering with nothing is
   * the honest stand-in — this suite is about qits-ci, and a source that answers empty neither fails
   * the aggregate nor contributes to it.
   */
  private static GcPinSources sources(String ciBaseUrl) {
    GcPinSources sources = new GcPinSources();
    // One pin each: an empty answer from these two is a refusal (qits-1172), not "nothing pinned".
    sources.cd = GcPinsTest::oneDeployment;
    CiHttpDaemonPins ci = new CiHttpDaemonPins();
    ci.baseUrl = ciBaseUrl;
    ci.timeout = Duration.ofSeconds(5);
    ci.objectMapper = new ObjectMapper();
    sources.ci = ci;
    sources.maintenance = GcPinsTest::oneDependency;
    sources.configuration = List::of;
    sources.workspaces = List::of;
    sources.projects = List::of;
    return sources;
  }
}
