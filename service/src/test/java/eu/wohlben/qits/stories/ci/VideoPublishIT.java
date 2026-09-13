package eu.wohlben.qits.stories.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryMedia;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The {@code ci-videos} category's producer — the screenshots intake one media type over, and the
 * place where the two CI types stop being the same thing.
 *
 * <p>The wire difference is a single metadata key: a recording declares {@code
 * media.resolution.length} where a still declares a width and a height. Everything else — the six
 * pairing keys, the {@code qits:ci-run} upload, the content-addressed id — is identical, which is
 * exactly why the difference is worth a story rather than a comment. <b>The retention rules are not
 * identical and were never meant to be</b>: screenshots are branch-scoped (keep the newest per
 * branch and flow while the branch lives), videos are byte-budgeted (keep the newest N per flow
 * with N measured in bytes, because one recording outweighs a branch of stills). Both rules are
 * written down in their own strategy class, both classes are stubs, and both types are excluded
 * from collection today — so nothing published here is ever swept, and a run that produced rows
 * would make each stub refuse to plan rather than guess.
 *
 * <p>Like the screenshot half, this documents a wire <b>no producer exists for yet</b>.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class VideoPublishIT {

  static final String CATEGORY = "ci-videos";
  static final String SLUG = "a-userflow-run-publishes-the-recording-it-captured";

  /** The seeded repository row this category's whole chain addresses. */
  public static final String REPOSITORY = "ci-videos";

  public static final String BRANCH = "main";

  /** Its own flow name, so the two CI chains can never answer each other's golden query. */
  public static final String FLOW = "story-video-flow";

  static final String COMMIT = "d".repeat(40);
  static final String FLOW_HASH = "b704e21c9d380f56";
  static final String DISPLAY = "The store's repositories, recorded";
  static final String DIFF_HASH = "3c96f5e17ab0d842";

  /** The one key a recording has where a still has two. Milliseconds, unvalidated by the store. */
  static final long LENGTH_MILLIS = 12_500;

  private static final int SALT = 0x5702;

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a userflow run";

  /** The intake's wire path — the screenshots one with the repository segment changed, and only that. */
  public static final String BLOBS_PATH =
      StoryTarget.API_PATH + "/repositories/" + REPOSITORY + "/blobs";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A userflow run publishes the recording it captured",
      category = "ci-videos")
  @UserStoryDescription(
      """
      **No producer exists for this wire yet.** The recording half of the CI media plane, built and
      never met: a run captures a clip, declares the same pairing keys a screenshot declares plus
      the one key that differs — a length where a still has a resolution — and posts it as a
      machine. The two types are separate rather than one "CI media" type because their retention
      diverges in kind: golden stills are kept per branch, recordings are kept to a byte budget.
      Neither rule runs today, and both are written down where the type that needs it lives.
      """)
  void aUserflowRunPublishesItsRecording(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);

    // Who the access log's next line belongs to, and what kind of traffic it is — `http` for the
    // reason ScreenshotPublishIT gives, and identical to it, which is itself part of the point:
    // the two CI types differ in what the store KEEPS, never in how a run reaches it.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    Path work = commands.workDir();
    byte[] webm = StoryMedia.webm(SALT);
    Files.write(work.resolve("recording.webm"), webm);
    String expected = StoryMedia.sha256Hex(webm);
    story
        .note("a userflow run recorded " + LENGTH_MILLIS + "ms of a browser journey")
        .as("recording-captured");

    Map<String, String> meta =
        StoryMedia.videoHeaders(
            BRANCH, COMMIT, FLOW, FLOW_HASH, DISPLAY, DIFF_HASH, LENGTH_MILLIS);

    commands
        .run(
            "{} -sS -X POST -H 'Expect:' -H {}"
                + " -H {} -H {} -H {} -H {} -H {} -H {} -H {}"
                + " -H {} -H {}"
                + " --data-binary @{} -o {} -w %{http_code} {}",
            Cli.curl(),
            "Content-Type: video/webm",
            ScreenshotPublishIT.header(meta, "git.branch.name"),
            ScreenshotPublishIT.header(meta, "git.commit.hash"),
            ScreenshotPublishIT.header(meta, "qits.userflow.name"),
            ScreenshotPublishIT.header(meta, "qits.userflow.hash"),
            ScreenshotPublishIT.header(meta, "qits.display.name"),
            ScreenshotPublishIT.header(meta, "qits.diff.hash"),
            ScreenshotPublishIT.header(meta, "media.resolution.length"),
            "X-Qits-User: " + ScreenshotPublishIT.PUBLISHER,
            "X-Qits-Roles: qits:ci-run",
            "recording.webm",
            "publish-response.json",
            target.apiBase() + "/repositories/" + REPOSITORY + "/blobs")
        .as("recording-published");
    assertEquals("201", commands.lastOutput().strip(), "the publish status");

    JsonNode receipt = JSON.readTree(Files.readString(work.resolve("publish-response.json")));
    String id = receipt.path("id").asText();
    assertEquals(expected, id, "the id is the sha256 of the bytes the store received");
    // The narrative the wire cannot carry: what makes this a DIFFERENT type from a screenshot is
    // one declared key and a retention rule, not anything visible in the request.
    story
        .note("the same intake, one key apart: a length where a still declares a resolution")
        .as("publish-recorded");
    AccessLogSource.awaitLogged("POST " + BLOBS_PATH);

    context.put("story.ci-videos.id", id);
    context.put("story.ci-videos.branch", BRANCH);
    context.put("story.ci-videos.flow", FLOW);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "recording-captured");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "recording-published");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "-X POST", 0);
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "-X POST", "201");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "publish-recorded");
    // Observed and counted, exactly as the screenshot intake is — and the two diagrams differing by
    // one path segment is the cheapest possible statement that this is one mechanism over two types.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.HTTP,
        ACTOR,
        AccessLogSource.SERVICE,
        "POST " + BLOBS_PATH + " -> 201");
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 1);
  }
}
