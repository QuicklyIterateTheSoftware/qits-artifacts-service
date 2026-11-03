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
 * The {@code ci-screenshots} category's <b>browser</b> story: finding one captured image without
 * knowing its id.
 *
 * <p>The CI types are the only ones in this explorer whose listing is a <b>time</b> question rather
 * than a name question. A screenshot has no name — its identity is its digest and its meaning is in
 * its metadata — so the repository page renders a filter form (created/accessed windows, a size
 * range, an access state) instead of the search box every named type gets. That is what this story
 * exercises: an operator who knows roughly when a run happened and nothing else.
 *
 * <p>The row's link text is the <b>short</b> digest the table prints, not the full id — the first
 * nineteen characters of a bare sha256 hex, which is what {@code shortDigest} in the client's
 * formatting module produces for an id with no {@code sha256:} prefix. Waiting on the full id would
 * wait forever against a table that never prints one.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class ScreenshotExploreIT {

  static final String CATEGORY = "ci-screenshots";
  static final String SLUG = "an-operator-finds-a-screenshot-by-when-it-was-captured";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  /**
   * The lower bound the operator types. Deliberately far in the past and a fixed string: the point
   * of the step is that a window is applied and the record survives it, not that a boundary is
   * exact — and a bound computed from the clock would make the recorded step unreproducible.
   */
  static final String CREATED_AFTER = "2020-01-01T00:00";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds a screenshot by when it was captured",
      category = "ci-screenshots")
  @UserflowPrecondition(ScreenshotPublishIT.class)
  @UserflowRunsAfter(ScreenshotFetchIT.class)
  @UserStoryDescription(
      """
      An operator with a time and no id. The CI media types are the only ones here that cannot be
      browsed by name — a screenshot's identity is the digest of its bytes — so the repository page
      answers the question that can actually be asked: what was captured in this window, how big
      was it, what metadata did the run declare, and has anything ever read it. The last column is
      the one that will matter: it is what a retention rule for this type would be argued from.
      """)
  void anOperatorFindsAScreenshotByTime(Flow flow, Interactions story, UserflowContext context) {
    String published = context.require("story.ci-screenshots.id", String.class);
    String shortened = shortDigest(published);

    // The gateway's job, played for the browser, before the first navigate.
    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    story.note("a screenshot has no name: the row is addressed by the digest of its own bytes");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // :text-is is exact, so the `ci-screenshots` type badge in the row's second cell cannot shadow
    // the repository link in its header — and `ci-videos` cannot be matched by a prefix.
    flow.click("tbody th a:text-is('" + ScreenshotPublishIT.REPOSITORY + "')");
    flow.waitFor("tbody th[scope=\"row\"].mono :is(a,button):text-is('" + shortened + "')");
    flow.screenshot("the repository's captured artifacts").as("records-listed");

    // The filter form: a datetime-local input takes the `YYYY-MM-DDTHH:mm` spelling and nothing
    // else, and the submit button is the form's own — `Clear` beside it is type=button, so a
    // selector matching both would be ambiguous rather than wrong.
    flow.fill("form.filters input[name=\"createdAfter\"]", CREATED_AFTER);
    flow.click("form.filters button[type=\"submit\"]");
    flow.waitFor("tbody th[scope=\"row\"].mono :is(a,button):text-is('" + shortened + "')");
    flow.screenshot("the artifacts captured since the window opened").as("filters-applied");

    // The metadata cell of THIS row, not of any row: the repository holds other captures, so
    // `:has()` narrows the assertion to the record this chain published rather than to whichever
    // one the table happened to print first. The digest text sits on the row header's control —
    // an anchor or, since qits-543, a download button — so `:has()` matches either.
    flow.expectText(
        "tbody tr:has(:is(a,button):text-is('" + shortened + "')) td.metadata",
        ScreenshotPublishIT.FLOW);
    flow.screenshot("the record, with the metadata its run declared").as("record-shown");
  }

  /**
   * The client's {@code shortDigest}, restated: an id with an algorithm prefix keeps it and twelve
   * hex characters, a bare hex id is cut at nineteen. Blob ids here are the bare form, and the
   * branch for the prefixed one is kept so this helper cannot quietly become wrong if that changes.
   */
  static String shortDigest(String id) {
    int colon = id.indexOf(':');
    if (colon >= 0) {
      return id.substring(0, Math.min(id.length(), colon + 13));
    }
    return id.length() > 19 ? id.substring(0, 19) : id;
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
