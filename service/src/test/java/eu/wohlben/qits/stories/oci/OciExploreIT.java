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
 * The {@code qits} category's <b>browser</b> story: finding the platform image repository a pushed
 * image landed in.
 *
 * <p>It used to go one page further — one image's tags, each with the manifest digest it points at
 * and the bytes that digest references, with the full-digest disclosure opened and checked against
 * the push story's digest. That page, {@code repositories/:repo/images/:image}, moved to the landing
 * app in qits-2324, and this app's route becomes a full-document hand-off to it once the bundled
 * client takes that change: a story addressing it would assert a page this process no longer
 * draws. So the story ends at the one screen that stays here, the store's repositories at the root,
 * and asserts only what that screen says about the push — that the {@code qits} registry is there,
 * typed {@code oci-images}. The tag-and-digest triple is the landing app's to show now, and its golden
 * masters ({@code listImageTagSummaries}, {@code listImageManifestSummaries}) are the contract for it.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#skopeoPresent")
public class OciExploreIT {

  static final String CATEGORY = "qits";
  static final String SLUG = "an-operator-finds-the-registry-a-pushed-image-landed-in";

  /** How the diagram names the initiator of everything this story's browser fetches. */
  static final String ACTOR = "an operator";

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "An operator finds the registry a pushed image landed in",
      category = "qits")
  @UserflowPrecondition(OciPushIT.class)
  @UserflowRunsAfter(OciPullIT.class)
  @UserStoryDescription(
      """
      An operator asking what the platform would run. The store's repositories at the host root,
      and among them the platform image repository a real client pushed into earlier in this chain
      — an OCI registry, listed beside the npm, maven, daemon and docs rows it shares the store
      with. (The repository's image listing moved to the landing app in qits-2316 and one image's
      tags and manifests in qits-2324, so the story stops at the store's own screen.)
      """)
  void anOperatorFindsTheRegistry(Flow flow, Interactions story, UserflowContext context) {
    // Required, not read: the chain's subject must exist, or this story has nothing to find.
    context.require("story.qits.image", String.class);

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
    // :text-is is exact, so the `oci-images` type badge in the row's second cell cannot shadow the
    // repository link in its header. The link is not followed: `repositories/:repo` hands the
    // browser off to the landing app (qits-2316).
    flow.waitFor("tbody th a:text-is('" + OciPushIT.REPOSITORY + "')");
    flow.expectText(
        "tbody tr:has(th a:text-is('" + OciPushIT.REPOSITORY + "')) qits-badge", "oci-images");
    flow.screenshot("the store's repositories, the platform registry among them")
        .as("repositories-listed");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.skopeoPresent()) {
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
