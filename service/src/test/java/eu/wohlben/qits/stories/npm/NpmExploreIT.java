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
 * same packaged process the two command-line stories drove.
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
  static final String SLUG = "an-operator-finds-a-published-package-and-its-immutable-versions";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds a published package and its immutable versions",
      category = "npm")
  @UserflowPrecondition(NpmPublishIT.class)
  @UserflowRunsAfter(NpmInstallIT.class)
  @UserStoryDescription(
      """
      The registry's own web UI, served by the same process that stores the bytes. An operator
      opens the store's repositories at the host root, then the package a pipeline published earlier
      in this chain — its version list, which is the one screen that says out loud what
      immutability means here: a version appears once and never changes. (The npm repository's own
      package listing moved to the landing app in qits-2316, so the story addresses the package
      page directly.)
      """)
  void anOperatorFindsAPublishedPackage(Flow flow, Interactions story, UserflowContext context) {
    String name = context.require("story.npm.name", String.class);
    String version = context.require("story.npm.version", String.class);

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
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository page between the two — the package listing a click on the `npm` row used to
    // open — is the landing app's since qits-2316 (this app's `repositories/:repo` now hands the
    // browser off to it), so the package page is addressed directly. A scoped name is ONE route
    // segment, spelled the way that listing's routerLink spelled it: the slash escaped to %2F, the
    // `@` literal.
    flow.navigate("{}repositories/{}/packages/{}", root, "npm", name.replace("/", "%2F"));
    flow.waitFor("th:text-is('" + version + "')");
    flow.screenshot("the package's immutable versions").as("versions-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.npmPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "versions-shown");
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
