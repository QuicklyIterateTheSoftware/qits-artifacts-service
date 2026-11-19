package eu.wohlben.qits.stories.oci;

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
 * The {@code qits} category's <b>browser</b> story: finding a pushed image and the digest its tag
 * publishes.
 *
 * <p>The registry answers {@code /v2/_catalog} and {@code /v2/<name>/tags/list}, so unlike the
 * daemon plane this listing is not the only enumeration — but it is the only one that puts a tag,
 * the manifest digest behind it and the bytes that digest references on one screen. That triple is
 * the whole subject: a deploy plan pins a digest, a human reads a tag, and the row is where the two
 * are seen to be the same thing.
 *
 * <p>The story opens the full-digest disclosure rather than asserting the shortened form. The table
 * prints {@code sha256:} plus twelve characters by default — enough to recognise, not enough to
 * pin — and the toggle is a local signal costing no request, which is exactly why the page offers
 * it. Asserting the full string is also what makes this a check against the pull story's digest
 * rather than against a prefix that a second image could share.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#skopeoPresent")
public class OciExploreIT {

  static final String CATEGORY = "qits";
  static final String SLUG = "an-operator-finds-a-pushed-image-and-the-tag-it-published";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds a pushed image and the tag it published",
      category = "qits")
  @UserflowPrecondition(OciPushIT.class)
  @UserflowRunsAfter(OciPullIT.class)
  @UserStoryDescription(
      """
      An operator asking what the platform would run. The store's repositories, then one image's
      tags in the platform image repository — each with the manifest digest it points at and the
      bytes that manifest references. The sizes on that screen deliberately do not add up:
      consecutive builds share their base layers, so the same bytes appear on most rows and the
      only honest total is the union printed above them. (The repository's own image listing moved
      to the landing app in qits-2316, so the story addresses the image page directly.)
      """)
  void anOperatorFindsAPushedImage(Flow flow, Interactions story, UserflowContext context) {
    String image = context.require("story.qits.image", String.class);
    String tag = context.require("story.qits.tag", String.class);
    String digest = context.require("story.qits.manifest-digest", String.class);

    // The gateway's job, played for the browser, before the first navigate.
    StoryBrowser.asOperator(flow);
    // A browser is a caller like any other and the launched process' access log records everything
    // it fetches, so this story names its initiator too. `http` and not `package`: what this
    // operator's browser asks for is the explorer's own screens rather than an artifact — and
    // setting it here is also what stops a preceding package story's kind carrying over, since the
    // framework resets the actor for us and nothing can reset a kind this repository invented.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    story.note("the image this operator is looking for was pushed by a real client, not seeded");

    flow.navigate("{}", root);
    flow.waitFor("table tbody th a");
    flow.screenshot("the store's repositories").as("repositories-listed");

    // The repository page between the two — the image listing a click on the row used to open — is
    // the landing app's since qits-2316 (this app's `repositories/:repo` now hands the browser off
    // to it), so the image page is addressed directly, spelled the way that listing's routerLink
    // spelled it. The fingerprint keeps `{}` for every part, as the root navigate does.
    flow.navigate("{}repositories/{}/images/{}", root, OciPushIT.REPOSITORY, image);
    // A tag that does not look like a git commit sha is the header cell's own text rather than a
    // link out to the CI explorer, so :text-is matches the th itself here.
    flow.waitFor("tbody th[scope=\"row\"].mono:text-is('" + tag + "')");

    // The disclosure, so the digest on screen is the whole string a deploy plan would pin.
    flow.click("button.disclosure");
    flow.expectText("tbody tr:has(th:text-is('" + tag + "')) td.mono.subtle", digest);
    flow.screenshot("the image's tags and the digest each one publishes").as("tags-shown");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.skopeoPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "repositories-listed");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "tags-shown");
    // No edge is pinned by name — a browser story fetches the SPA's own build-hashed bundle beside
    // the reads it is about, and NpmExploreIT carries the long form of that decision. What every
    // explore story does close is its initiator: every arrow here is the operator's, which is the
    // one failure a screen assertion cannot see (a dropped attribute() call leaves the screens
    // passing and the diagram reading `a caller`).
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR));
  }
}
