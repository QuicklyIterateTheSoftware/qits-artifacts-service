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
 * The daemon category's browser story — and the only way to answer "what daemons are in there?" at
 * all.
 *
 * <p>The wire under this listing has <b>no enumeration of its own</b>: every route below {@code
 * /artifacts/daemons} is version-addressed, so what exists is only askable through the explorer.
 * That makes this screen the daemon plane's index rather than a convenience over one.
 *
 * <p>The browse endpoint behind it reads {@code /artifacts/api/repositories/daemons/daemons} — the
 * doubled segment is correct and not a typo: the wire carries no repository segment, while the
 * explorer's subject is a repository throughout, so the seeded {@code daemons} row is both the
 * repository and the listing under it.
 *
 * <p>That listing — the repository page — moved to the landing app in qits-2316 (this app's {@code
 * repositories/:repo} now hands the browser off to it), so the story goes from the store's
 * repositories straight to the daemon's own page.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class DaemonExploreIT {

  static final String CATEGORY = "daemons";
  static final String SLUG = "an-operator-finds-a-published-daemon-and-its-versions";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a published daemon and its versions", category = "daemons")
  @UserflowPrecondition(DaemonPublishIT.class)
  @UserflowRunsAfter(DaemonDownloadIT.class)
  @UserStoryDescription(
      """
      An operator asking what the platform would boot. The daemon wire is version-addressed from
      end to end and lists nothing, so this screen is the only place the question can be asked: the
      store's repositories, then one daemon's versions with the exact sha256 each one is — the
      string a deployment pin is written from. (The daemons repository's own listing moved to the
      landing app in qits-2316, so the story addresses the daemon page directly.)
      """)
  void anOperatorFindsAPublishedDaemon(Flow flow, Interactions story, UserflowContext context) {
    String daemon = context.require("story.daemon.name", String.class);
    String version = context.require("story.daemon.version", String.class);

    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);
    story.note("the daemon wire lists nothing of its own: this screen is the only enumeration");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository page between the two — the daemon listing a click on the `daemons` row used
    // to open — is the landing app's since qits-2316, so the daemon page is addressed directly.
    // The doubled segment is the repository and then the listing under it, as on the wire.
    flow.navigate("{}repositories/{}/daemons/{}", root, "daemons", daemon);
    // The version is waited for on its CONTROL, not on the row header around it: `:text-is()`
    // matches the SMALLEST element carrying the text, so on a page where the version cell is a
    // download control `th:text-is(…)` matches nothing at all — which is exactly the difference
    // between this table and the npm package page's, where the version is the header's own text.
    // Since qits-543 that control is a download BUTTON, not an anchor — a plain href cannot carry
    // the bearer the download needs — so the selector matches either element.
    flow.waitFor("tbody th[scope=\"row\"] :is(a,button):text-is('" + version + "')");
    flow.screenshot("the daemon's versions and their digests").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-shown");
    // No edge is pinned by name — a browser story fetches the SPA's own build-hashed bundle beside
    // the reads it is about, and NpmExploreIT carries the long form of that decision. What every
    // explore story does close is its initiator: every arrow here is the operator's, which is the
    // one failure a screen assertion cannot see (a dropped attribute() call leaves the screens
    // passing and the diagram reading `a caller`).
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
