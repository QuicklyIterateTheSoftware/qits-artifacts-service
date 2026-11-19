package eu.wohlben.qits.stories.docs;

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
 * The docs category's browser story: finding a published site without knowing its URL.
 *
 * <p>The site segment is where the two spellings of a name with a slash in it meet, and they are
 * not interchangeable. The SPA's router encodes {@code @userflows/story-site} to {@code %2F} on the
 * way into its route and decodes it back out, so the address bar carries the escape — while the
 * "Open" link on the page spells the slashes <b>literally</b>, because it leaves the application
 * for the docs wire, whose grammar accepts no percent-encoded separator. Asserting {@code %2F} on
 * that link would be asserting a 404.
 *
 * <p>The story writes the escaped spelling itself: the docs repository's site listing — the
 * repository page whose routerLink used to produce it — moved to the landing app in qits-2316, so
 * the site page is addressed directly.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlAndTarPresent")
public class DocsExploreIT {

  static final String CATEGORY = "docs";
  static final String SLUG = "an-operator-finds-a-published-site-and-its-versions";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a published site and its versions", category = "docs")
  @UserflowPrecondition(DocsPublishIT.class)
  @UserflowRunsAfter(DocsReadIT.class)
  @UserStoryDescription(
      """
      An operator who has a documentation site and no link to it. The store's repositories, then
      the site's versions — each with the branch and commit it
      was published from printed beside it, and a way straight into the bundle. Those two columns
      are what the publisher declared on the upload rather than anything this store verified, which
      is why they are printed to match against a build and are deliberately not links. (The docs
      repository's own site listing moved to the landing app in qits-2316, so the story addresses
      the site page directly.)
      """)
  void anOperatorFindsAPublishedSite(Flow flow, Interactions story, UserflowContext context) {
    String site = context.require("story.docs.site", String.class);
    String version = context.require("story.docs.version", String.class);

    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);
    story.note("a site name may contain a slash: this is one site, not a scope and a name");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository page between the two is the landing app's since qits-2316, so the site page
    // is addressed directly — the name ONE route segment, the slash escaped to %2F and the `@`
    // literal, exactly as the old listing's routerLink spelled it.
    flow.navigate("{}repositories/{}/docs/{}", root, "docs", site.replace("/", "%2F"));
    flow.waitFor("th:text-is('" + version + "')");
    // The branch/commit cell, which is the whole reason those two columns exist.
    flow.expectText("tbody tr td.mono.subtle", DocsPublishIT.BRANCH);
    // And the way out of the explorer and into the bundle itself.
    flow.waitFor("[data-testid=\"open-bundle\"]");
    flow.screenshot("the site's versions, with the build each came from").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlAndTarPresent()) {
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
