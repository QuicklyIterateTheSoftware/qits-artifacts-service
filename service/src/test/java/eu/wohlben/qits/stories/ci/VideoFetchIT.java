package eu.wohlben.qits.stories.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.PackagedProcessIT;
import eu.wohlben.qits.stories.support.AccessLogSource;
import eu.wohlben.qits.stories.support.Cli;
import eu.wohlben.qits.stories.support.StoryMedia;
import eu.wohlben.qits.stories.support.StoryTarget;
import eu.wohlben.qits.stories.support.StoryIdentities;
import eu.wohlben.qits.userflows.Commands;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowContext;
import eu.wohlben.qits.userflows.UserflowPrecondition;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The consuming half of the recordings chain — and proof that the golden query is one mechanism
 * over two types rather than a screenshots feature that videos happen to reuse.
 *
 * <p>The same pair, the same {@code latest} collapse, the same {@code qits:admin} reads; only the
 * repository segment and the flow name change. That matters because the <b>retention</b> rules for
 * the two types diverge, and a reader that had to know which type it was talking to would push that
 * divergence into every consumer. It does not: what differs is what the store keeps, not how a
 * consumer asks for it.
 *
 * <p>Like every story in these two categories, this documents a wire <b>no consumer exists for
 * yet</b>.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class VideoFetchIT {

  static final String CATEGORY = "ci-videos";
  static final String SLUG = "the-diff-loop-fetches-the-golden-recording-for-a-branch";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "the diff loop";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "The diff loop fetches the golden recording for a branch",
      category = "ci-videos")
  @UserflowPrecondition(VideoPublishIT.class)
  @UserStoryDescription(
      """
      The recording half of the read contract, and deliberately the same shape as the screenshot
      half: a branch and a flow go in, the newest record for that pair comes back, the bytes are
      downloaded by the id and checked against it. No consumer of this wire exists yet. What is
      worth noticing is what is NOT different here — the query, the collapse and the roles are one
      mechanism over both CI types, and the divergence between them lives entirely in what the
      store chooses to keep.
      """)
  void theDiffLoopFetchesTheGoldenRecording(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);
    String published = context.require("story.ci-videos.id", String.class);
    String branch = context.require("story.ci-videos.branch", String.class);
    String flow = context.require("story.ci-videos.flow", String.class);

    // Whose traffic the access log's next lines are, and what kind — the screenshot half verbatim,
    // which is the claim this story exists to make.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    Path work = commands.workDir();
    String blobs = target.apiBase() + "/repositories/" + VideoPublishIT.REPOSITORY + "/blobs";

    commands
        .run(
            "{} -sS -H {} -o {} -w %{http_code} {}",
            Cli.curl(),
            StoryIdentities.authorizationHeader(commands, StoryIdentities.operator(ScreenshotFetchIT.READER)),
            "golden.json",
            blobs + ScreenshotFetchIT.goldenQuery(branch, flow))
        .as("golden-queried");
    assertEquals("200", commands.lastOutput().strip(), "the query's status");

    JsonNode matched = JSON.readTree(Files.readString(work.resolve("golden.json")));
    assertEquals(
        1,
        matched.path("records").size(),
        "the pair collapses to exactly one golden record per branch and flow");
    assertEquals(
        published,
        matched.path("records").get(0).path("id").asText(),
        "the golden for this pair is the record the run published");

    commands
        .run(
            "{} -sS -f -H {} -o {} -w %{http_code} {}",
            Cli.curl(),
            StoryIdentities.authorizationHeader(commands, StoryIdentities.operator(ScreenshotFetchIT.READER)),
            "golden.webm",
            blobs + "/" + published)
        .as("golden-downloaded");
    assertEquals("200", commands.lastOutput().strip(), "the download status");

    assertEquals(
        published,
        StoryMedia.sha256Hex(work.resolve("golden.webm")),
        "the bytes that arrived must hash to the id they were fetched by");
    story.note("the downloaded bytes hash to the id, which is the digest").as("bytes-verified");

    // The narrative the wire cannot carry: nothing about this read had to know it was a recording.
    story
        .note("the same question, the same collapse, the same roles — only the repository differs")
        .as("fetch-recorded");
    AccessLogSource.awaitLogged("GET " + VideoPublishIT.BLOBS_PATH + "/");
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "golden-queried");
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "golden.json", "200");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "golden-downloaded");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "golden.webm", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "bytes-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "fetch-recorded");
    // The screenshot half's two edges with one segment changed — which is the whole assertion this
    // story makes about the read surface, now made against observed traffic rather than a sentence.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.HTTP,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET "
            + VideoPublishIT.BLOBS_PATH
            + ScreenshotFetchIT.goldenQuery(VideoPublishIT.BRANCH, VideoPublishIT.FLOW)
            + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.HTTP,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + VideoPublishIT.BLOBS_PATH + "/{digest} -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 2);
  }
}
