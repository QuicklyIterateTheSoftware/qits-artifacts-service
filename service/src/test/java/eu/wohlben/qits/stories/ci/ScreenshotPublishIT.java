package eu.wohlben.qits.stories.ci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * The {@code ci-screenshots} category's producer — and the one story in this repository that
 * documents a wire <b>no producer exists for yet</b>.
 *
 * <p>Say that plainly, because it is the story's whole value: nothing in the platform has ever
 * uploaded a golden screenshot. The repository row is seeded, the type's profile validates uploads,
 * the query surface collapses to the newest per branch and flow, and {@code CiScreenshotsGcStrategy}
 * is a stub that refuses to plan the moment a row appears. Every one of those parts is built and
 * none of them has met the other end. This story is the intake contract written down as something
 * that runs, so the loop that eventually captures screenshots has a shape to build against rather
 * than a design document to interpret.
 *
 * <p>Two things about the request are easy to get wrong and both answer with a status code that
 * blames the wrong layer.
 *
 * <ul>
 *   <li><b>All eight metadata keys are required.</b> The type's profile refuses an upload missing
 *       any of them with a {@code 400}, so a partial header set reads as a malformed image rather
 *       than as an incomplete declaration. {@link StoryMedia#screenshotHeaders} is the complete set
 *       precisely so a caller cannot drift from it.
 *   <li><b>The upload is {@code @RolesAllowed("qits:ci-run")}</b>: only a CI run publishes. The CI
 *       media plane is a JAX-RS boundary rather than a raw-Vert.x one, so tokenless-on-qits-net
 *       does not apply to it: the request carries an {@code X-Qits-User}/{@code X-Qits-Roles} pair
 *       naming a CI run, because the packaged process runs {@code LaunchMode.NORMAL} and is
 *       anonymous without them.
 * </ul>
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
@EnabledIf("eu.wohlben.qits.stories.support.Cli#curlPresent")
public class ScreenshotPublishIT {

  static final String CATEGORY = "ci-screenshots";
  static final String SLUG = "a-userflow-run-publishes-the-screenshot-it-captured";

  /** The seeded repository row this category's whole chain addresses. */
  public static final String REPOSITORY = "ci-screenshots";

  /** The machine identity the CI media plane's upload is answered for. */
  public static final String PUBLISHER = "story-pipeline";

  /** Half of the golden pairing key. Every story in this category declares the same branch. */
  public static final String BRANCH = "main";

  /** The other half: one logical flow, the thing a golden image is golden *for*. */
  public static final String FLOW = "story-flow";

  /**
   * The remaining required keys, all static. A commit and two hashes that move per run would make
   * the fetch story's predicate a moving target and prove nothing extra — the query is exact-match
   * over strings, and one fixed set is what lets this chain state what it looked for.
   */
  static final String COMMIT = "c".repeat(40);

  static final String FLOW_HASH = "5f2c1ab90d7e4361";
  static final String DISPLAY = "The store's repositories";
  static final String DIFF_HASH = "8ad41c07b6e25f93";

  /**
   * The declared resolution — and it is not decoration: {@code BlobService} reads the PNG's own
   * IHDR and refuses an upload whose declared width and height disagree with the bytes, so these
   * two numbers and {@link StoryMedia#png} have to be one statement.
   */
  static final int WIDTH = 1280;

  static final int HEIGHT = 720;

  /** Distinct content, so this blob is this story's rather than another case's already-stored one. */
  private static final int SALT = 0x5701;

  /** How the diagram names the initiator of everything this story sends. */
  static final String ACTOR = "a userflow run";

  /** The intake's wire path, as the launched process' access log spells it. */
  public static final String BLOBS_PATH =
      StoryTarget.API_PATH + "/repositories/" + REPOSITORY + "/blobs";

  private static final ObjectMapper JSON = new ObjectMapper();

  @TestHTTPResource("/")
  URL root;

  @UserStory(
      value = "A userflow run publishes the screenshot it captured",
      category = "ci-screenshots")
  @UserStoryDescription(
      """
      **No producer exists for this wire yet.** Nothing in the platform has ever uploaded a golden
      screenshot: the repository is seeded, the validating upload path is built, and the two ends
      have never met. This story is that intake written as something that runs — a run captures an
      image, declares the eight keys the diff loop pairs on (the branch and commit it came from,
      the flow and its definition hash, the display name, the diff hash and the resolution), and
      posts it as a machine. What comes back is a content-addressed id: the same bytes uploaded
      twice are one blob and one id, which is what makes an unchanged screenshot cost nothing.
      """)
  void aUserflowRunPublishesItsScreenshot(
      Interactions story, Commands commands, UserflowContext context) throws IOException {
    StoryTarget target = new StoryTarget(root);

    // Who the access log's next line belongs to, and what kind of traffic it is. `http` and not
    // `package`, unlike every other producer in this catalogue: the CI media plane is a JSON API
    // that a run POSTs to, not a package manager — nothing here is resolved, versioned or pinned by
    // a client, and calling it a package edge would put it in a family it does not belong to.
    AccessLogSource.attribute(ACTOR, NetworkEdge.HTTP);

    // workDir() creates and wipes the scratch on first use, so it is taken before anything is
    // written into it.
    Path work = commands.workDir();
    byte[] png = StoryMedia.png(WIDTH, HEIGHT, SALT);
    Files.write(work.resolve("screenshot.png"), png);
    String expected = StoryMedia.sha256Hex(png);
    story
        .note("a userflow run captured a " + WIDTH + "x" + HEIGHT + " screenshot of a step")
        .as("screenshot-captured");

    Map<String, String> meta =
        StoryMedia.screenshotHeaders(
            BRANCH, COMMIT, FLOW, FLOW_HASH, DISPLAY, DIFF_HASH, WIDTH, HEIGHT);

    // -H 'Expect:' for the reason every publish here carries it: Quarkus does not answer
    // 100-continue automatically and curl sends the header for any body over a kilobyte. The body
    // goes to a file and the status code to stdout, so both are evidence rather than one being
    // lost to the other.
    commands
        .run(
            "{} -sS -X POST -H 'Expect:' -H {}"
                + " -H {} -H {} -H {} -H {} -H {} -H {} -H {} -H {}"
                + " -H {} -H {}"
                + " --data-binary @{} -o {} -w %{http_code} {}",
            Cli.curl(),
            "Content-Type: image/png",
            header(meta, "git.branch.name"),
            header(meta, "git.commit.hash"),
            header(meta, "qits.userflow.name"),
            header(meta, "qits.userflow.hash"),
            header(meta, "qits.display.name"),
            header(meta, "qits.diff.hash"),
            header(meta, "media.resolution.width"),
            header(meta, "media.resolution.height"),
            "X-Qits-User: " + PUBLISHER,
            "X-Qits-Roles: qits:ci-run",
            "screenshot.png",
            "publish-response.json",
            target.apiBase() + "/repositories/" + REPOSITORY + "/blobs")
        .as("screenshot-published");
    assertEquals("201", commands.lastOutput().strip(), "the publish status");

    JsonNode receipt = JSON.readTree(Files.readString(work.resolve("publish-response.json")));
    String id = receipt.path("id").asText();
    assertEquals(expected, id, "the id is the sha256 of the bytes the store received");
    assertFalse(
        receipt.path("existing").asBoolean(),
        "no earlier case in this run holds these bytes, so this upload stored them");
    // The narrative the wire cannot carry: the id that came back IS the digest, which is what makes
    // an unchanged screenshot cost nothing on the next run.
    story
        .note("the id the intake answered with is the digest of the bytes, so the same image twice"
            + " is one blob")
        .as("publish-recorded");
    AccessLogSource.awaitLogged("POST " + BLOBS_PATH);

    context.put("story.ci-screenshots.id", id);
    context.put("story.ci-screenshots.branch", BRANCH);
    context.put("story.ci-screenshots.flow", FLOW);
  }

  /** One {@code -H} argument, taken from the complete required set rather than respelled. */
  static String header(Map<String, String> headers, String key) {
    String name = "X-Artifacts-Meta-" + key;
    return name + ": " + headers.get(name);
  }

  @AfterAll
  static void storyReportIsComplete() {
    if (!Cli.curlPresent()) {
      return;
    }
    ReportAssertions.assertComplete(CATEGORY, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, SLUG, "screenshot-captured");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "screenshot-published");
    ReportAssertions.assertCommand(CATEGORY, SLUG, "-X POST", 0);
    ReportAssertions.assertCommandOutputContains(CATEGORY, SLUG, "-X POST", "201");
    ReportAssertions.assertStepId(CATEGORY, SLUG, "publish-recorded");
    // Observed in the launched process' access log, and counted: the intake this story documents is
    // ONE request carrying the bytes and the eight declared keys together, and a producer built to
    // this contract should find nothing else here to imitate.
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
