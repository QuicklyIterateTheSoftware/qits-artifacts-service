package eu.wohlben.qits.stories.ci;

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
 * The {@code ci-videos} category's <b>browser</b> story.
 *
 * <p>The same screen as the screenshots one, over the type whose bytes are the reason the screen
 * carries a size filter at all. A recording is orders of magnitude larger than a still, so "what is
 * in here above N bytes, and has anything ever read it" is the question this listing exists to
 * answer — and it is the question the type's intended byte-budgeted retention would be argued from.
 * The story applies the time window and screenshots the answer; the size column beside it is the
 * evidence.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class VideoExploreIT {

  static final String CATEGORY = "ci-videos";
  static final String SLUG = "an-operator-finds-a-recording-by-when-it-was-captured";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds a recording by when it was captured",
      category = "ci-videos")
  @UserflowPrecondition(VideoPublishIT.class)
  @UserflowRunsAfter(VideoFetchIT.class)
  @UserStoryDescription(
      """
      An operator asking what the CI media plane is holding, on the type where the answer costs
      real disk. Recordings have no names either — the row is the digest of its bytes — so the same
      time window and the same size and access-state controls are the whole interface. Nothing here
      is ever collected today: both CI types are excluded from garbage collection, and this listing
      is where the case for a retention rule would be read off rather than guessed at.
      """)
  void anOperatorFindsARecordingByTime(Flow flow, Interactions story, UserflowContext context) {
    String published = context.require("story.ci-videos.id", String.class);
    String shortened = ScreenshotExploreIT.shortDigest(published);

    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    story.note("both CI types are excluded from collection: nothing on this screen is ever swept");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // :text-is is exact, so `ci-videos` cannot be matched by the `ci-screenshots` row above it.
    flow.click("tbody th a:text-is('" + VideoPublishIT.REPOSITORY + "')");
    flow.waitFor("tbody th[scope=\"row\"].mono :is(a,button):text-is('" + shortened + "')");
    flow.screenshot("the repository's recordings").as("records-listed");

    flow.fill(
        "form.filters input[name=\"createdAfter\"]", ScreenshotExploreIT.CREATED_AFTER);
    flow.click("form.filters button[type=\"submit\"]");
    flow.waitFor("tbody th[scope=\"row\"].mono :is(a,button):text-is('" + shortened + "')");
    flow.screenshot("the recordings captured since the window opened").as("filters-applied");

    // The metadata cell of THIS row: the length key is where this type differs from screenshots,
    // and it is printed rather than interpreted. The digest sits on the row header's control — an
    // anchor or, since qits-543, a download button — so `:has()` matches either.
    flow.expectText(
        "tbody tr:has(:is(a,button):text-is('" + shortened + "')) td.metadata",
        "media.resolution.length");
    flow.screenshot("the recording, with the length its run declared").as("record-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "records-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "filters-applied");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "record-shown");
    // No edge is pinned by name — a browser story fetches the SPA's own build-hashed bundle beside
    // the reads it is about, and NpmExploreIT carries the long form of that decision. What every
    // explore story does close is its initiator: every arrow here is the operator's, which is the
    // one failure a screen assertion cannot see (a dropped attribute() call leaves the screens
    // passing and the diagram reading `a caller`).
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
