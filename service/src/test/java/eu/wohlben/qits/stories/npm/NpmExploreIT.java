package eu.wohlben.qits.stories.npm;

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
 * The npm category's <b>browser</b> story: headless Chromium over the explorer SPA, against the
 * same packaged process the two command-line stories drove — finding the repository a published
 * package landed in.
 *
 * <p>It used to go one page further — the package's versions, the one screen that said out loud
 * what immutability means here. That page, {@code repositories/:repo/packages/:package}, moves to
 * the landing app in qits-2326, and this app's route becomes a full-document hand-off to it once
 * the bundled client takes that change: a story addressing it would assert a page this process no
 * longer draws. So the story ends at the one screen that stays here, the store's repositories at
 * the root, and asserts only what that screen says about the publish — that the {@code npm}
 * repository is there, typed {@code npm-packages}. The versions view is the landing app's to show
 * now, and its golden masters ({@code listNpmVersionSummaries}, {@code listSbomVersions}) are the
 * contract for it.
 *
 * <p>The web UI is <b>embedded</b> — Quinoa builds {@code src/main/webui} into the artifact and the
 * process serves it at {@code /} — so this flow runs through the service itself and there is no
 * separate frontend to point a browser at. It is also the only place the UI can be seen at all:
 * Quinoa is disabled under {@code @QuarkusTest}, so a unit test asserting anything about {@code /}
 * would pass against a process with no client in it.
 *
 * <p>A <b>mixed</b> story: the {@link Interactions} half narrates, the {@link Flow} half drives the
 * browser, and both land in one interleaved step log with screenshots and a video under {@code
 * target/userstories/}. It browses the package the chain already published rather than publishing
 * one of its own, which is what makes it the chain's last link instead of a third producer.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#npmPresent")
public class NpmExploreIT {

  static final String CATEGORY = "npm";
  static final String SLUG = "an-operator-finds-the-repository-a-published-package-landed-in";

  /** The hosted npm repository the publish story's pipeline published into. */
  static final String REPOSITORY = "npm";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds the repository a published package landed in",
      category = "npm")
  @UserflowPrecondition(NpmPublishIT.class)
  @UserflowRunsAfter(NpmInstallIT.class)
  @UserStoryDescription(
      """
      The registry's own web UI, served by the same process that stores the bytes. An operator
      opens the store's repositories at the host root, and among them the npm repository a
      pipeline published into earlier in this chain — listed beside the maven, image, daemon and
      docs rows it shares the store with. (The npm repository's package listing moved to the
      landing app in qits-2316 and one package's versions in qits-2326, so the story stops at the
      store's own screen.)
      """)
  void anOperatorFindsTheRepository(Flow flow, Interactions story, UserflowContext context) {
    // Required, not read: the chain's subject must exist, or this story has nothing to find.
    context.require("story.npm.name", String.class);

    // The gateway's job, played for the browser, before the first navigate.
    StoryBrowser.asOperator(flow);

    // A browser is a caller like any other and the access log records everything it fetches, so
    // this story names its initiator too. `http` and not `package`: what the operator's browser
    // asks for is the explorer's own screens, not an artifact — and setting it here is also what
    // stops the publish story's `package` kind carrying over, since only the actor is reset for us.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    story.note("the package this operator is looking for was published over the wire, not seeded");

    // The template form keeps the definition hash stable although the launched process sits on a
    // random port: the fingerprint records `navigate {}`, the display the real URL.
    flow.navigate("{}", root);
    // :text-is is exact, so the `npm-packages` type badge in the row's second cell cannot shadow
    // the repository link in its header. The link is not followed: `repositories/:repo` hands the
    // browser off to the landing app (qits-2316).
    flow.waitFor("tbody th a:text-is('" + REPOSITORY + "')");
    flow.expectText("tbody tr:has(th a:text-is('" + REPOSITORY + "')) qits-badge", "npm-packages");
    flow.screenshot("the store's repositories, the npm repository among them")
        .as("repositories-listed");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.npmPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    // No edge is pinned by NAME here, and that is a decision rather than a gap. A browser story
    // fetches the SPA's own bundle beside the reads it is about, and those filenames carry a build
    // hash — so the diagram records what the run really made and the assertions stay on the
    // screens, which is what this story is about. The command-line halves of every chain are where
    // the wire is pinned.
    //
    // The one network claim a browser story CAN make is about its initiator, and it makes it: every
    // arrow in this diagram is the operator's. That is what stops a story silently emitting `a
    // caller -> qits-artifacts` because a later edit dropped the attribute() call — the one failure
    // mode a screen assertion cannot see, since the screens still pass without it. Every explore
    // story in the catalogue closes the same way.
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
