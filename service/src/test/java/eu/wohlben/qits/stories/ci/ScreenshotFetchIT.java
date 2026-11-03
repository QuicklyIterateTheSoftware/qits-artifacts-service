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
 * The consuming half of the screenshots chain: what a diff loop does before it compares anything.
 *
 * <p>The interesting part is the query, not the download. A golden image is identified by a
 * <b>pair</b> — the branch it was produced from and the flow it records — and the store answers
 * that pair directly: exact-match {@code meta.<key>=<value>} predicates, plus {@code latest}, which
 * collapses the result to the newest record per {@code (git.branch.name, qits.userflow.name)}
 * group. That is the whole reason this type keeps its metadata in a queryable map instead of in a
 * file name: "the golden for main's login flow" is a question, not a naming convention somebody has
 * to maintain.
 *
 * <p>Both requests are read as an <b>operator</b>, not as the machine that published. The CI media
 * plane splits its roles: the upload is {@code qits:system} and every read — the query and the blob
 * itself — is {@code qits:admin}. A diff loop reading with its publish identity gets a 403, which
 * is a fact worth having a story say out loud.
 *
 * <p>Like its producer, this documents a wire <b>no consumer exists for yet</b>; the loop it
 * describes has never run.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class ScreenshotFetchIT {

  static final String CATEGORY = "ci-screenshots";
  static final String SLUG = "the-diff-loop-fetches-the-golden-screenshot-for-a-branch";

  /** The reader every CI-media read in this chain is answered for. */
  static final String READER = "story-diff";

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "the diff loop";

  /**
   * The golden pairing key as a query, in the order a caller spells it — which is what the access
   * log records and therefore what an edge's label carries. {@code latest} is a bare flag: a blank
   * value is how a caller says "collapse", and the reader treats it as true.
   */
  static String goldenQuery(String branch, String flow) {
    return "?meta.git.branch.name=" + branch + "&meta.qits.userflow.name=" + flow + "&latest";
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "The diff loop fetches the golden screenshot for a branch",
      category = "ci-screenshots")
  @UserflowPrecondition(ScreenshotPublishIT.class)
  @UserStoryDescription(
      """
      What a golden-diff run does before it can diff. It does not know an id — it knows a branch
      and a flow — so it asks the store for the newest record matching that pair, takes the id off
      the answer, downloads those bytes and checks them against the digest the id already is. No
      consumer of this wire exists yet; this is the read half of the contract the intake story
      writes down, and the shape a diff loop would be built to.
      """)
  void theDiffLoopFetchesTheGoldenScreenshot(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);
    String published = context.require("story.ci-screenshots.id", String.class);
    String branch = context.require("story.ci-screenshots.branch", String.class);
    String flow = context.require("story.ci-screenshots.flow", String.class);

    // Whose traffic the access log's next lines are, and what kind. `http` for the reason the
    // intake story gives: this is a JSON API a loop calls, not a package manager.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    Path work = commands.workDir();
    String blobs = target.apiBase() + "/repositories/" + ScreenshotPublishIT.REPOSITORY + "/blobs";

    // `latest` carries no value on purpose: a bare flag is how a caller says "collapse", and the
    // reader treats a blank value as true. The two predicates are the golden pairing key, and
    // nothing else in this store answers to it.
    commands
        .run(
            "{} -sS -H {} -o {} -w %{http_code} {}",
            Cli.curl(),
            StoryIdentities.authorizationHeader(commands, StoryIdentities.operator(READER)),
            "golden.json",
            blobs + goldenQuery(branch, flow))
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
            StoryIdentities.authorizationHeader(commands, StoryIdentities.operator(READER)),
            "golden.png",
            blobs + "/" + published)
        .as("golden-downloaded");
    assertEquals("200", commands.lastOutput().strip(), "the download status");

    // The id IS the digest, so the check needs nothing else transported alongside it — which is
    // what makes a content-addressed store cheap to consume.
    assertEquals(
        published,
        StoryMedia.sha256Hex(work.resolve("golden.png")),
        "the bytes that arrived must hash to the id they were fetched by");
    story.note("the downloaded bytes hash to the id, which is the digest").as("bytes-verified");

    // The narrative the wire cannot carry: this loop never knew an id — it asked a question and the
    // answer told it which bytes to fetch.
    story
        .note("the loop knew a branch and a flow, never an id: the query is what produced one")
        .as("fetch-recorded");
    AccessLogSource.awaitLogged("GET " + ScreenshotPublishIT.BLOBS_PATH + "/");
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
    ReportAssertions.assertCommand(CATEGORY, SLUG, "golden.png", 0);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "bytes-verified");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "fetch-recorded");
    // The two requests a diff loop makes, observed. The query rides in the label because the
    // access-log pattern records the whole URL — so the golden PREDICATE, which is the thing this
    // story is about, is in the diagram rather than summarised away as `?meta…`.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.HTTP,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET "
            + ScreenshotPublishIT.BLOBS_PATH
            + goldenQuery(ScreenshotPublishIT.BRANCH, ScreenshotPublishIT.FLOW)
            + " -> 200");
    // The download is addressed by the digest the query answered with, so the scrubber templates it
    // — which is right: the label is about the SHAPE of the read, and the bytes are proved above.
    ReportAssertions.assertEdge(
        CATEGORY,
        SLUG,
        NetworkEdge.HTTP,
        ACTOR,
        AccessLogSource.SERVICE,
        "GET " + ScreenshotPublishIT.BLOBS_PATH + "/{digest} -> 200");
    ReportAssertions.assertEdgeCount(CATEGORY, SLUG, 2);
  }
}
