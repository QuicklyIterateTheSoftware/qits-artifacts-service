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
 * The docs category's browser story: finding the docs repository a published site landed in.
 *
 * <p>It used to go one page further — one site's versions, each with the branch and commit it was
 * published from and an "Open" link straight into the bundle, with the site addressed by the
 * {@code %2F}-escaped spelling the old listing's routerLink produced. That page, {@code
 * repositories/:repo/docs/:site}, moved to the landing app in qits-2328, and this app's route
 * becomes a full-document hand-off to it once the bundled client takes that change: a story
 * addressing it would wait for an {@code open-bundle} link this process no longer draws. So the
 * story ends at the one screen that stays here, the store's repositories at the root, and asserts
 * only what that screen says about the publish — that the {@code docs} repository is there, typed
 * {@code docs}. A site's versions are the landing app's to show now, and its golden master ({@code
 * listDocsVersionSummaries}) is the contract for it.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlAndTarPresent")
public class DocsExploreIT {

  static final String CATEGORY = "docs";
  static final String SLUG = "an-operator-finds-the-repository-a-published-site-landed-in";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  /** The seeded docs repository every publish in this chain lands in. */
  static final String REPOSITORY = "docs";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds the repository a published site landed in",
      category = "docs")
  @UserflowPrecondition(DocsPublishIT.class)
  @UserflowRunsAfter(DocsReadIT.class)
  @UserStoryDescription(
      """
      An operator who has a documentation site and no link to it. The store's repositories at the
      host root, and among them the docs repository a real publish landed in earlier in this chain
      — listed beside the image, npm, maven and daemon rows it shares the store with. (The docs
      repository's site listing moved to the landing app in qits-2316 and one site's versions in
      qits-2328, so the story stops at the store's own screen.)
      """)
  void anOperatorFindsTheRepository(Flow flow, Interactions story, UserflowContext context) {
    // Required, not read: the chain's subject must exist, or this story has nothing to find.
    context.require("story.docs.site", String.class);

    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);
    story.note("the site this operator is looking for was published by a real client, not seeded");

    flow.navigate("{}", root);
    // :text-is is exact, and the repository and its type share the word: the header link is the
    // row's name and the qits-badge in its second cell the type, so each is matched by its own
    // element. The link is not followed: `repositories/:repo` hands the browser off to the landing
    // app (qits-2316).
    flow.waitFor("tbody th a:text-is('" + REPOSITORY + "')");
    flow.expectText("tbody tr:has(th a:text-is('" + REPOSITORY + "')) qits-badge", "docs");
    flow.screenshot("the store's repositories, the docs repository among them")
        .as("repositories-listed");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlAndTarPresent()) {
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
