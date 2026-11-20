package eu.wohlben.qits.stories.maven;

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
 * The maven category's <b>browser</b> story: headless Chromium over the explorer SPA, against the
 * same packaged process the two command-line stories drove — finding the repository a deployed
 * coordinate landed in.
 *
 * <p>It used to go one page further — the coordinate's versions, each with the files a deploy
 * uploaded (the jar, the pom, every checksum sidecar) and the size each one occupies. That page,
 * {@code repositories/:repo/maven-packages/:coordinate}, moves to the landing app in qits-2325, and
 * this app's route becomes a full-document hand-off to it once the bundled client takes that
 * change: a story addressing it would assert a page this process no longer draws. So the story ends
 * at the one screen that stays here, the store's repositories at the root, and asserts only what
 * that screen says about the deploy — that the {@code maven} repository is there, typed {@code
 * maven-packages}. The versions-and-files view is the landing app's to show now, and its golden
 * masters ({@code listMavenVersionSummaries}, {@code listSbomVersions}) are the contract for it.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#mvnPresent")
public class MavenExploreIT {

  static final String CATEGORY = "maven";
  static final String SLUG = "an-operator-finds-the-repository-a-deployed-coordinate-landed-in";

  /** The hosted maven repository the deploy story's release pipeline deployed into. */
  static final String REPOSITORY = "maven";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds the repository a deployed coordinate landed in",
      category = "maven")
  @UserflowPrecondition(MavenDeployIT.class)
  @UserflowRunsAfter(MavenResolveIT.class)
  @UserStoryDescription(
      """
      The repository's own web UI, served by the same process that stores the bytes. An operator
      opens the store's repositories at the host root, and among them the maven repository a real
      deploy wrote into earlier in this chain — listed beside the npm, image, daemon and docs rows
      it shares the store with. (The maven repository's coordinate listing moved to the landing
      app in qits-2316 and one coordinate's versions and files in qits-2325, so the story stops at
      the store's own screen.)
      """)
  void anOperatorFindsTheRepository(Flow flow, Interactions story, UserflowContext context) {
    // Required, not read: the chain's subject must exist, or this story has nothing to find.
    context.require("story.maven.coordinate", String.class);

    // The gateway's job, played for the browser, before the first navigate.
    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    story.note("the coordinate this operator is looking for was deployed over the wire, not seeded");

    // The template form keeps the definition hash stable although the launched process sits on a
    // random port: the fingerprint records `navigate {}`, the display the real URL.
    flow.navigate("{}", root);
    // :text-is is exact, so the `maven-packages` type badge in the row's second cell cannot shadow
    // the repository link in its header. The link is not followed: `repositories/:repo` hands the
    // browser off to the landing app (qits-2316).
    flow.waitFor("tbody th a:text-is('" + REPOSITORY + "')");
    flow.expectText("tbody tr:has(th a:text-is('" + REPOSITORY + "')) qits-badge", "maven-packages");
    flow.screenshot("the store's repositories, the maven repository among them")
        .as("repositories-listed");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.mvnPresent()) {
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
