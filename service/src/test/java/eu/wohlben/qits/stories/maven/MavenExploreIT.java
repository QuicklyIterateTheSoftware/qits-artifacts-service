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
 * same packaged process the two command-line stories drove.
 *
 * <p>It used to be the only story in this repository that used the explorer's <b>search box</b>,
 * over the maven repository's coordinate listing. That listing is the repository page, which moved
 * to the landing app in qits-2316 — this app's {@code repositories/:repo} now hands the browser off
 * to it — so the story addresses the coordinate page directly, spelling the coordinate the way that
 * listing's routerLink did: one segment, the {@code :} literal.
 *
 * <p>A selector fact learned the expensive way: on the coordinate page the version is the header's
 * <b>own text</b> rather than a link, so {@code th:text-is(…)} is right there and would be wrong on
 * the daemon page, where the same-looking cell wraps a download anchor.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#mvnPresent")
public class MavenExploreIT {

  static final String CATEGORY = "maven";
  static final String SLUG = "an-operator-finds-a-deployed-coordinate-and-its-files";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(value = "An operator finds a deployed coordinate and its files", category = "maven")
  @UserflowPrecondition(MavenDeployIT.class)
  @UserflowRunsAfter(MavenResolveIT.class)
  @UserStoryDescription(
      """
      The repository's own web UI, served by the same process that stores the bytes. An operator
      opens the store's repositories at the host root, then the coordinate they want — the screen
      that answers the question a maven client cannot be asked: not "is this version there?",
      which a resolve answers, but "what files IS this version?", which is the jar, the pom and
      every checksum sidecar a deploy uploaded, with the size each one occupies. (The maven
      repository's own coordinate listing moved to the landing app in qits-2316.)
      """)
  void anOperatorFindsADeployedCoordinate(
      Flow flow, Interactions story, UserflowContext context) {
    String coordinate = context.require("story.maven.coordinate", String.class);
    String version = context.require("story.maven.version", String.class);

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
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository page between the two — the coordinate listing and its search box — is the
    // landing app's since qits-2316, so the coordinate page is addressed directly.
    flow.navigate("{}repositories/{}/maven-packages/{}", root, "maven", coordinate);
    // The version is the header cell's own text here, so :text-is matches the th itself.
    flow.waitFor("tbody th.mono:text-is('" + version + "')");
    // The files column, which is the whole reason this page exists rather than a version list.
    flow.expectText("tbody td.subtle", MavenDeployIT.ARTIFACT_ID + "-" + version + ".jar");
    flow.expectText("tbody td.subtle", MavenDeployIT.ARTIFACT_ID + "-" + version + ".pom");
    flow.screenshot("the coordinate's versions and the files behind one").as("files-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.mvnPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "files-shown");
    // No edge is pinned by name — a browser story fetches the SPA's own build-hashed bundle beside
    // the reads it is about, and NpmExploreIT carries the long form of that decision. What every
    // explore story does close is its initiator: every arrow here is the operator's, which is the
    // one failure a screen assertion cannot see (a dropped attribute() call leaves the screens
    // passing and the diagram reading `a caller`).
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
