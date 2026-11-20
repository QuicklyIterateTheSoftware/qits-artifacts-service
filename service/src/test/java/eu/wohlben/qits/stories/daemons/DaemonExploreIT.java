package eu.wohlben.qits.stories.daemons;

import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryBrowser;
import eu.wohlben.qits.userflows.Flow;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.UserflowPrecondition;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The daemon category's <b>browser</b> story: finding the platform repository a published daemon
 * landed in.
 *
 * <p>It used to go one page further — one daemon's versions, each with the exact sha256 it is, the
 * string a deployment pin is written from. That page, {@code repositories/:repo/daemons/:daemon},
 * moved to the landing app in qits-2327, and this app's route becomes a full-document hand-off to
 * it once the bundled client takes that change: a story addressing it would assert a page this
 * process no longer draws. So the story ends at the one screen that stays here, the store's
 * repositories at the root, and asserts only what that screen says about the publish — that the
 * {@code daemons} repository is there, typed {@code daemon-binaries}. The versions-and-digests view
 * is the landing app's to show now.
 *
 * <p>The wire under that listing has <b>no enumeration of its own</b>: every route below {@code
 * /artifacts/daemons} is version-addressed, so the repositories screen — not a convenience over the
 * daemon listing, but the only place the question "what daemons are in there?" could ever be
 * asked from this app — is now also the only screen this story can still assert against.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class DaemonExploreIT {

  static final String CATEGORY = "daemons";
  static final String SLUG = "an-operator-finds-the-repository-a-published-daemon-landed-in";

  /** The seeded daemon-binaries repository every daemon publish lands in. */
  static final String REPOSITORY = "daemons";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds the repository a published daemon landed in",
      category = "daemons")
  @UserflowPrecondition(DaemonPublishIT.class)
  @UserflowRunsAfter(DaemonDownloadIT.class)
  @UserStoryDescription(
      """
      An operator asking what the platform would boot. The store's repositories at the host root,
      and among them the daemons repository a real publish wrote into earlier in this chain —
      listed beside the npm, maven, image and docs rows it shares the store with. (The daemon
      listing moved to the landing app in qits-2316 and one daemon's own versions in qits-2327, so
      the story stops at the store's own screen.)
      """)
  void anOperatorFindsTheRepository(Flow flow, Interactions story, UserflowContext context) {
    // Required, not read: the chain's subject must exist, or this story has nothing to find.
    context.require("story.daemon.name", String.class);

    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);
    story.note("the daemon wire lists nothing of its own: this screen is the only enumeration");

    flow.navigate("{}", root);
    // :text-is is exact, so the `daemon-binaries` type badge in the row's second cell cannot shadow
    // the repository link in its header. The link is not followed: `repositories/:repo` hands the
    // browser off to the landing app (qits-2316).
    flow.waitFor("tbody th a:text-is('" + REPOSITORY + "')");
    flow.expectText(
        "tbody tr:has(th a:text-is('" + REPOSITORY + "')) qits-badge", "daemon-binaries");
    flow.screenshot("the store's repositories, the daemons repository among them")
        .as("repositories-listed");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    // No edge is pinned by name — a browser story fetches the SPA's own build-hashed bundle beside
    // the reads it is about, and NpmExploreIT carries the long form of that decision. What every
    // explore story does close is its initiator: every arrow here is the operator's, which is the
    // one failure a screen assertion cannot see (a dropped attribute() call leaves the screens
    // passing and the diagram reading `a caller`).
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
