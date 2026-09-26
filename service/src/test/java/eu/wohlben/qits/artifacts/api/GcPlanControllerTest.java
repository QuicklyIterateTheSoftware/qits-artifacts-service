package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The GC plan on the wire, and the things it must say when there is nothing to say.
 *
 * <p>The report is all zeros here — and the whole point of this suite is that zeros arrive with
 * reasons attached: the six types on an engine naming the strategy that would collect them and the
 * reason it refused, the two CI stubs naming the rule they intend, a sweep that would unlink
 * nothing, and the row-less pool listed as untouchable. A report that answered {@code {}} would be
 * indistinguishable from a broken one.
 *
 * <p>Refusing is the deployed behaviour under a broken dependency, not a test artefact: every type
 * on an engine reads live pins, this repository has none of the six pin peers, and the suite points
 * all six base urls at a closed port. Fail-closed on the wire is worth an assertion of its own — a
 * plan that answered with an empty keep-set would condemn every sha tag on the platform.
 *
 * <p>The execute route exists now — {@code POST /gc/sweep}, landed after the user reviewed the
 * dry-run — and here it never gets as far as the store: the pin sources are closed ports, so the
 * run aborts whole with nothing deleted and a reason on the receipt. Two independent things would
 * have to break for a byte to go: the pins would have to answer, and the grace window would have to
 * pass on content this suite created seconds ago. The plan path still refuses a {@code POST}:
 * reading and executing stay two different URLs.
 */
@QuarkusTest
class GcPlanControllerTest {

  /**
   * A repository of this suite's own, because the per-repository routes need a row to be about.
   *
   * <p>The service module's suite has no table reset and never self-seeds, so which repositories
   * exist depends on which other suites ran first. Naming one here — {@code ensure} is idempotent
   * and additive — is what makes these two cases assertions rather than a reading of suite order.
   */
  private static final String REPO = "gc-scope-case";

  @jakarta.inject.Inject
  eu.wohlben.qits.blobstore.control.ArtifactRepositoryService repositories;

  @org.junit.jupiter.api.BeforeEach
  void seedRepository() {
    repositories.ensure(REPO, eu.wohlben.qits.artifacts.control.OciImagesProfile.KEY);
  }

  @Test
  void thePlanNamesEveryTypeIncludingTheOnesNobodyCollects() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("generatedAt", notNullValue())
        .body("graceWindow", is("PT6H"))
        .body("types", hasSize(8))
        .body(
            "types.type",
            org.hamcrest.Matchers.containsInAnyOrder(
                "ci-screenshots",
                "ci-videos",
                "oci-images",
                "npm-packages",
                "maven-packages",
                "daemon-binaries",
                "docs",
                "sboms"))
        .body("types.find { it.type == 'oci-images' }.strategy", is("OciImageGcStrategy"))
        .body("types.find { it.type == 'npm-packages' }.strategy", is("NpmPackagesGcStrategy"))
        .body(
            "types.find { it.type == 'ci-screenshots' }.strategy", is("CiScreenshotsGcStrategy"))
        .body("types.find { it.type == 'ci-videos' }.strategy", is("CiVideosGcStrategy"))
        .body("types.find { it.type == 'docs' }.strategy", is("DocsGcStrategy"))
        .body("types.find { it.type == 'sboms' }.strategy", is("SbomGcStrategy"))
        .body(
            "types.find { it.type == 'maven-packages' }.strategy", is("MavenPackagesGcStrategy"))
        .body("types.find { it.type == 'maven-packages' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'maven-packages' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        .body(
            "types.find { it.type == 'npm-packages' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        // daemon-binaries is claimed now, and it refuses here for the reason it was unclaimed
        // before: its keep-set is qits-ci's ladder, this suite has no qits-ci, and the one blob
        // class a running service EXECUTES must never be planned against "nothing is pinned".
        .body(
            "types.find { it.type == 'daemon-binaries' }.strategy",
            is("DaemonBinariesGcStrategy"))
        .body(
            "types.find { it.type == 'daemon-binaries' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"))
        .body("types.find { it.type == 'daemon-binaries' }.dead", hasSize(0))
        .body("types.reclaimableBytes", everyItem(is(0)));
  }

  @Test
  void thePlanEchoesTheConfiguredPolicyAndSaysWhetherASweepCouldExecuteIt() {
    // Two halves a reader needs before the outcomes mean anything: what this deployment told the
    // collector to do (the settlement's mapping, echoed with its window and its rule as a sentence),
    // and whether a sweep run now could execute the plan at all. It could not here — the pin sources
    // are closed ports — so the zeros beside oci-images are a refusal, not a finding.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("configuration", hasSize(8))
        .body("configuration.find { it.type == 'oci-images' }.strategy", is("own"))
        .body("configuration.find { it.type == 'oci-images' }.window", is("P0D"))
        .body("configuration.find { it.type == 'maven-packages' }.strategy", is("own"))
        .body("configuration.find { it.type == 'maven-packages' }.window", is("P0D"))
        .body(
            "configuration.find { it.type == 'maven-packages' }.rule",
            org.hamcrest.Matchers.containsString("last 2 released versions"))
        .body("configuration.find { it.type == 'sboms' }.strategy", is("own"))
        .body("configuration.find { it.type == 'sboms' }.window", is("P0D"))
        .body("configuration.find { it.type == 'ci-videos' }.strategy", is("excluded"))
        .body("configuration.find { it.type == 'ci-videos' }.window", nullValue())
        .body("executable", is(false))
        .body("pinFailures", hasSize(6))
        .body(
            "types.find { it.type == 'oci-images' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"));
  }

  @Test
  void theStubsClaimTheirTypesAndSayEitherTheirRuleOrTheirRefusal() {
    // The claimed set is complete: both CI types are claimed by stubs. Their wire state depends
    // on suite order — BlobControllerTest uploads real screenshot records into this shared process —
    // so both honest answers are accepted and pinned: at zero rows, a note naming the intended rule
    // (branch-scoped for screenshots, byte-budgeted for videos) and that the loop has never
    // produced content; with rows, the fail-closed refusal that names the rule to implement. What
    // is never acceptable is a plan: dead stays empty in both states.
    io.restassured.path.json.JsonPath json =
        given().when().get("/artifacts/api/gc/plan").then().statusCode(200).extract().jsonPath();
    assertStubState(json, "ci-screenshots", "branch-scoped", "branch", "dead");
    assertStubState(json, "ci-videos", "byte-budgeted", "byte", "dead");
  }

  private static void assertStubState(
      io.restassured.path.json.JsonPath json,
      String type,
      String ruleWord,
      String refusalWord,
      String deadKey) {
    java.util.Map<String, Object> plan = json.getMap("types.find { it.type == '" + type + "' }");
    org.junit.jupiter.api.Assertions.assertEquals(
        java.util.List.of(), plan.get(deadKey), type + " must never plan a deletion as a stub");
    Object error = plan.get("error");
    if (error == null) {
      String note = (String) plan.get("note");
      org.junit.jupiter.api.Assertions.assertNotNull(note, type + " at zero rows carries its note");
      org.junit.jupiter.api.Assertions.assertTrue(note.contains(ruleWord), note);
      org.junit.jupiter.api.Assertions.assertTrue(note.contains("never produced"), note);
    } else {
      String refused = (String) error;
      org.junit.jupiter.api.Assertions.assertTrue(refused.contains("stub"), refused);
      org.junit.jupiter.api.Assertions.assertTrue(
          refused.contains(refusalWord), "the refusal names the rule to implement: " + refused);
    }
  }

  @Test
  void theReportLeadsWithASummaryAndSaysHowItReadItsPins() {
    // The two halves a review starts with. The summary is the paragraph a human reads before any
    // of the detail means anything — and here it has to lead with the refusal, because a plan that
    // cannot run must never be skimmed as a plan that would free nothing. The pins section is the
    // provenance under every keep the report claims: which service, at which url, answering what.
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("summary.executable", is(false))
        .body("summary.headline", org.hamcrest.Matchers.startsWith("NOT EXECUTABLE"))
        .body("summary.headline", org.hamcrest.Matchers.containsString("qits-platform-deployments"))
        .body("summary.reclaimableBytes", is(0))
        .body("summary.reclaimable", is("0 B"))
        .body("summary.identitiesCondemned", is(0))
        .body("summary.types", hasSize(8))
        .body(
            "summary.types.find { it.startsWith('ci-videos') }",
            org.hamcrest.Matchers.containsString("excluded by configuration"))
        .body("pins", hasSize(6))
        .body(
            "pins.source",
            org.hamcrest.Matchers.containsInAnyOrder(
                "qits-platform-deployments",
                "qits-ci",
                "qits-platform-maintenance",
                "qits-configuration",
                "qits-workspaces",
                "qits-projects"))
        .body("pins.find { it.source == 'qits-platform-deployments' }.url", is("http://localhost:1/deployments/api/pins"))
        .body("pins.find { it.source == 'qits-platform-deployments' }.answered", is(false))
        .body("pins.find { it.source == 'qits-ci' }.url", is("http://localhost:1/ci/api/daemon"))
        .body("pins.find { it.source == 'qits-ci' }.keeps", hasSize(0))
        .body(
            "pins.find { it.source == 'qits-platform-maintenance' }.url",
            is("http://localhost:1/maintenance/api/pins"))
        .body(
            "pins.find { it.source == 'qits-configuration' }.url",
            is("http://localhost:1/configuration/api/pins"))
        .body(
            "pins.find { it.source == 'qits-workspaces' }.url",
            is("http://localhost:1/workspaces/api/pins"))
        .body(
            "pins.find { it.source == 'qits-projects' }.url",
            is("http://localhost:1/projects/api/pins"));
  }

  @Test
  void anUnreachableQitsCdAbortsTheOciPlanRatherThanCondemningEveryTag() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body(
            "types.find { it.type == 'oci-images' }.error",
            org.hamcrest.Matchers.containsString("qits-platform-deployments"))
        .body("types.find { it.type == 'oci-images' }.dead", hasSize(0))
        .body("types.find { it.type == 'oci-images' }.kept", hasSize(0))
        .body("types.find { it.type == 'oci-images' }.blobsSweepable", is(0));
  }

  @Test
  void theSweepWouldUnlinkNothingAndTheRowLessPoolIsReportedAsUntouchable() {
    given()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("sweep.blobCount", is(0))
        .body("sweep.reclaimableBytes", is(0))
        .body("sweep.blobIds", hasSize(0))
        .body("untouchable.reason", notNullValue())
        .body("untouchable.blobCount", greaterThanOrEqualTo(0))
        .body("untouchable.bytes", greaterThanOrEqualTo(0));
  }

  @Test
  void theSweepRefusesTheWholeRunWhileThePinSourcesCannotAnswer() {
    // The settlement's abort rule on the wire. This suite has neither qits-platform-deployments nor qits-ci, so the
    // run stops before the census: nothing is deleted, every type carries the same reason, and the
    // row-less pool is reported as UNCOMPUTED rather than empty — an empty list there would be a
    // claim about a store this run never read.
    given()
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("dryRun", is(false))
        .body("executedAt", notNullValue())
        .body("aborted", org.hamcrest.Matchers.containsString("qits-platform-deployments"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-ci"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-platform-maintenance"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-configuration"))
        .body("types", hasSize(8))
        .body("types.deleted.flatten()", hasSize(0))
        .body("sweep.blobsUnlinked", is(0))
        .body("sweep.bytesReclaimed", is(0))
        .body("sweep.unlinkedBlobIds", hasSize(0))
        .body("untouchable.reason", org.hamcrest.Matchers.containsString("not computed"));
  }

  @Test
  void theRepositoryListingAnswersEveryRepositoryFromOneRunWithItsReasonBesideItsZeros() {
    // What the explorer's Cleanup column reads, and the property that makes it drawable at all:
    // one call, every row. Every figure here is a refusal in this suite — the pin sources are
    // closed ports — so the envelope has to say so once, run-wide, rather than leaving eight
    // repositories looking clean.
    given()
        .when()
        .get("/artifacts/api/gc/repositories")
        .then()
        .statusCode(200)
        .body("generatedAt", notNullValue())
        .body("graceWindow", is("PT6H"))
        .body("executable", is(false))
        .body("pinFailures", hasSize(6))
        .body("repositories.size()", greaterThanOrEqualTo(1))
        .body("repositories.blobsSweepable", everyItem(is(0)))
        .body("repositories.reclaimableBytes", everyItem(is(0)))
        .body("repositories.find { it.repository == '" + REPO + "' }.type", is("oci-images"))
        .body(
            "repositories.find { it.repository == '" + REPO + "' }.strategy",
            is("OciImageGcStrategy"))
        .body(
            "repositories.find { it.repository == '" + REPO + "' }.error",
            org.hamcrest.Matchers.containsString("live pins unavailable"));
  }

  @Test
  void oneRepositorysPlanIsItsOwnUrlAndAnUnknownNameIsA404() {
    // Scope is a path segment, and this is the half of that decision a test can show: a name that
    // is not a repository answers 404. A query parameter that went missing would have answered the
    // whole store instead, which on the sweep route is the difference between collecting one
    // repository and collecting the platform.
    given()
        .when()
        .get("/artifacts/api/gc/repositories/no-such-repository/plan")
        .then()
        .statusCode(404)
        .body("message", org.hamcrest.Matchers.containsString("no-such-repository"));

    given()
        .when()
        .get("/artifacts/api/gc/repositories/" + REPO + "/plan")
        .then()
        .statusCode(200)
        .body("repository", is(REPO))
        .body("type", is("oci-images"))
        .body("dryRun", is(true))
        .body("executable", is(false))
        .body("graceWindow", is("PT6H"))
        .body("pins", hasSize(6))
        .body("configuration.type", notNullValue())
        .body("dead", hasSize(0))
        .body("sweep.blobCount", is(0))
        .body("structural.blobCount", is(0))
        .body("untouchable.reason", notNullValue());
  }

  @Test
  void aScopedSweepRefusesTheWholeRunTooWhileThePinSourcesCannotAnswer() {
    // The abort rule does not shrink with the scope. One repository is not a smaller blast radius
    // at the blob layer — bytes it releases can be the last local reference to content a pin names
    // by digest — so a run with an unreadable qits-platform-deployments stops before the census here exactly as it
    // does on the whole-store route, and says which source failed.
    given()
        .when()
        .post("/artifacts/api/gc/repositories/" + REPO + "/sweep")
        .then()
        .statusCode(200)
        .body("repository", is(REPO))
        .body("type", is("oci-images"))
        .body("dryRun", is(false))
        .body("executedAt", notNullValue())
        .body("aborted", org.hamcrest.Matchers.containsString("qits-platform-deployments"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-ci"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-configuration"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-workspaces"))
        .body("aborted", org.hamcrest.Matchers.containsString("qits-projects"))
        .body("pins", hasSize(6))
        .body("deleted", hasSize(0))
        .body("withheldByGraceWindow", hasSize(0))
        .body("sweep.blobsUnlinked", is(0))
        .body("sweep.unlinkedBlobIds", hasSize(0))
        .body("untouchable.reason", org.hamcrest.Matchers.containsString("not computed"));
  }

  @Test
  void readingAndExecutingStayTwoDifferentUrls() {
    // A GET must never sweep: the reviewed-report-then-invoke order is carried by the verbs. The
    // scoped pair carries it the same way, and adds the one a path segment buys: a name that is not
    // a repository can never widen into the whole store. POST /gc/plan is a reader too — it exists
    // to carry supplied pins in a body — so it answers a report, never a deletion.
    given().when().post("/artifacts/api/gc/plan").then().statusCode(200).body("dryRun", is(true));
    given().when().get("/artifacts/api/gc/sweep").then().statusCode(405);
    given()
        .when()
        .post("/artifacts/api/gc/repositories/" + REPO + "/plan")
        .then()
        .statusCode(405);
    given().when().get("/artifacts/api/gc/repositories/" + REPO + "/sweep").then().statusCode(405);
    given()
        .when()
        .post("/artifacts/api/gc/repositories/no-such-repository/sweep")
        .then()
        .statusCode(404);
  }

  /** The deployer's answer, verbatim as {@code GET /deployments/api/pins} spells it. */
  private static final String DEPLOYMENTS =
      """
      {"pins":[{"applicationName":"qits-artifacts","shas":["aaaa","bbbb"]}]}
      """;

  /** qits-ci's answer, verbatim as {@code GET /ci/api/daemon} spells it. */
  private static final String CI_DAEMON =
      """
      {"daemonName":"qits-ci-daemon","daemonVersion":"2026.805.1",
       "previousDaemonVersion":"","source":"adopted"}
      """;

  /** qits-platform-maintenance's answer, verbatim as {@code GET /maintenance/api/pins} spells it. */
  private static final String DEPENDENCIES =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "repositories":[{"name":"qits-githost-service","status":"OK"}],
       "pins":[{"ecosystem":"maven","name":"eu.wohlben.qits:qits-blobstore",
                "version":"2026.903.85122","repository":"qits-githost-service",
                "manifestPath":"pom.xml"}]}
      """;

  /** qits-configuration's answer, verbatim as {@code GET /configuration/api/pins} spells it. */
  private static final String CONFIGURED_IMAGES =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "pins":[{"image":"qits/workspace","version":"2026.904.160522",
                "application":"qits-workspaces","key":"env.QITS_WORKSPACE_IMAGE_VERSION"}]}
      """;

  /**
   * qits-workspaces' answer, verbatim as {@code GET /workspaces/api/pins} spells it.
   *
   * <p>The workspace version here is deliberately OLDER than {@link #CONFIGURED_IMAGES}': that gap
   * is what the effective sources exist for, and a fixture agreeing with itself would prove nothing
   * about why there are six sources rather than four.
   */
  private static final String WORKSPACE_LAUNCHES =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[{"image":"qits/workspace","version":"2026.903.120000","launches":"workspace"}]}
      """;

  /** qits-projects' answer, verbatim as {@code GET /projects/api/pins} spells it. */
  private static final String PROJECT_LAUNCHES =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[{"image":"qits/project-agent","version":"2026.903.090000","launches":"agent"}]}
      """;

  /** The whole envelope the orchestrator sends. */
  private static String body() {
    return body(
        DEPLOYMENTS,
        CI_DAEMON,
        DEPENDENCIES,
        CONFIGURED_IMAGES,
        WORKSPACE_LAUNCHES,
        PROJECT_LAUNCHES);
  }

  /** The same, with any member left out to make that source unanswered. */
  private static String body(
      String deployments,
      String ciDaemon,
      String dependencies,
      String configuredImages,
      String workspaceLaunches,
      String projectLaunches) {
    java.util.List<String> members = new java.util.ArrayList<>();
    if (deployments != null) {
      members.add("\"deployments\":" + deployments);
    }
    if (ciDaemon != null) {
      members.add("\"ciDaemon\":" + ciDaemon);
    }
    if (dependencies != null) {
      members.add("\"dependencies\":" + dependencies);
    }
    if (configuredImages != null) {
      members.add("\"configuredImages\":" + configuredImages);
    }
    if (workspaceLaunches != null) {
      members.add("\"workspaceLaunches\":" + workspaceLaunches);
    }
    if (projectLaunches != null) {
      members.add("\"projectLaunches\":" + projectLaunches);
    }
    return "{\"pins\":{" + String.join(",", members) + "}}";
  }

  @Test
  void suppliedPinsDriveThePlanAndAreNamedAsSupplied() {
    // What qits-platform-orchestrator sends. It reads the platform's pins once per run and hands
    // the same set to every deleter, which is the only way two deleters in one run work off one
    // truth — and, on an authenticated platform, the only component that can read them at all.
    // The report says so: the sources are named "supplied", and they carry no url because this
    // service made no call.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(body())
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", is(true))
        .body("executable", is(true))
        .body("pinFailures", hasSize(0))
        .body("pins", hasSize(6))
        .body(
            "pins.source",
            org.hamcrest.Matchers.containsInAnyOrder(
                "supplied: qits-platform-deployments",
                "supplied: qits-ci",
                "supplied: qits-platform-maintenance",
                "supplied: qits-configuration",
                "supplied: qits-workspaces",
                "supplied: qits-projects"))
        .body("pins.url", everyItem(is("")))
        .body(
            "pins.find { it.source == 'supplied: qits-platform-deployments' }.keeps",
            org.hamcrest.Matchers.containsInAnyOrder(
                "qits-artifacts:aaaa", "qits-artifacts:bbbb"))
        .body(
            "pins.find { it.source == 'supplied: qits-ci' }.keeps",
            org.hamcrest.Matchers.contains("qits-ci-daemon@2026.805.1"))
        .body(
            "pins.find { it.source == 'supplied: qits-platform-maintenance' }.keeps",
            org.hamcrest.Matchers.contains("eu.wohlben.qits:qits-blobstore:2026.903.85122"))
        .body(
            "pins.find { it.source == 'supplied: qits-configuration' }.keeps",
            org.hamcrest.Matchers.contains("qits/workspace:2026.904.160522"))
        // The EFFECTIVE coordinate, and it is a different version of the same image — which is the
        // whole reason the two sources are not one. Both survive the run.
        .body(
            "pins.find { it.source == 'supplied: qits-workspaces' }.keeps",
            org.hamcrest.Matchers.contains("qits/workspace:2026.903.120000"))
        .body(
            "pins.find { it.source == 'supplied: qits-projects' }.keeps",
            org.hamcrest.Matchers.contains("qits/project-agent:2026.903.090000"))
        // With both sources answered, the pin-dependent types plan instead of refusing.
        .body("types.find { it.type == 'oci-images' }.error", nullValue());
  }

  @Test
  void aSuppliedSetMissingASourceRefusesExactlyAsAnUnreachableOneDoes() {
    // The fail-closed rule does not soften because the pins arrived by hand. A caller that supplied
    // one document supplied half a keep-set, and half a keep-set condemns whatever the other half
    // protected — so the plan is not executable and the sweep deletes nothing.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(
            body(
                DEPLOYMENTS, null, DEPENDENCIES, CONFIGURED_IMAGES, WORKSPACE_LAUNCHES,
                PROJECT_LAUNCHES))
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("executable", is(false))
        .body("pinFailures", hasSize(1))
        .body("pinFailures[0]", org.hamcrest.Matchers.containsString("ciDaemon"))
        .body("pins.find { it.source == 'supplied: qits-ci' }.answered", is(false))
        .body("pins.find { it.source == 'supplied: qits-platform-deployments' }.answered", is(true))
        .body("pins.find { it.source == 'supplied: qits-platform-maintenance' }.answered", is(true));

    // The four members added since 2026-09-04, left out together: an orchestrator that has not been
    // upgraded sends an older envelope, and that has to refuse rather than plan against a partial
    // keep-set. This is why this service ships LAST in every one of those rollouts.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(body(DEPLOYMENTS, CI_DAEMON, null, null, null, null))
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("executable", is(false))
        .body("pinFailures", hasSize(4))
        .body(
            "pinFailures",
            org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("dependencies")))
        .body(
            "pinFailures",
            org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("configuredImages")))
        .body(
            "pinFailures",
            org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("workspaceLaunches")))
        .body(
            "pinFailures",
            org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString("projectLaunches")));

    // …and one of the launch members alone, because that is the state an orchestrator mid-rollout
    // is in: everything else answered and the effective versions missing, which is exactly the
    // keep-set a run must not proceed without.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(
            body(DEPLOYMENTS, CI_DAEMON, DEPENDENCIES, CONFIGURED_IMAGES, null, PROJECT_LAUNCHES))
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("executable", is(false))
        .body("pinFailures", hasSize(1))
        .body("pinFailures[0]", org.hamcrest.Matchers.containsString("workspaceLaunches"))
        .body("pins.find { it.source == 'supplied: qits-workspaces' }.answered", is(false))
        .body("pins.find { it.source == 'supplied: qits-projects' }.answered", is(true));

    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(
            body(
                DEPLOYMENTS, null, DEPENDENCIES, CONFIGURED_IMAGES, WORKSPACE_LAUNCHES,
                PROJECT_LAUNCHES))
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("aborted", org.hamcrest.Matchers.containsString("supplied: qits-ci"))
        .body("types.deleted.flatten()", hasSize(0))
        .body("sweep.blobsUnlinked", is(0))
        .body("untouchable.reason", org.hamcrest.Matchers.containsString("not computed"));
  }

  @Test
  void aSweepWithSuppliedPinsRunsAndStillDeletesNothingOnAStoreThisYoung() {
    // The executing half of the orchestrator's two calls. It runs — no abort — and the only reason
    // nothing dies is the store's own age: every byte here was written seconds ago, inside the
    // grace window. Two independent things would have to be true for a deletion, which is the
    // property that makes this case safe to run at all.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(body())
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("aborted", nullValue())
        .body("types", hasSize(8))
        .body("types.deleted.flatten()", hasSize(0))
        .body("sweep.blobsUnlinked", is(0))
        .body("sweep.bytesReclaimed", is(0))
        .body("pins.source", org.hamcrest.Matchers.hasItem("supplied: qits-ci"));

    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(body())
        .when()
        .post("/artifacts/api/gc/repositories/" + REPO + "/sweep")
        .then()
        .statusCode(200)
        .body("repository", is(REPO))
        .body("aborted", nullValue())
        .body("deleted", hasSize(0))
        .body("pins.url", everyItem(is("")));
  }

  @Test
  void aCallWithNoBodyIsTheOldCallInEveryRespect() {
    // The SPA and every operator recipe send no body — some with no Content-Type at all — and that
    // must keep meaning "read the pins over HTTP". Here those readers are closed ports, so the
    // refusal on the wire is the proof the HTTP path is what ran.
    given()
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("executable", is(false))
        .body("pinFailures", hasSize(6))
        .body(
            "pins.find { it.source == 'qits-platform-deployments' }.url",
            is("http://localhost:1/deployments/api/pins"))
        .body(
            "pins.find { it.source == 'qits-configuration' }.url",
            is("http://localhost:1/configuration/api/pins"))
        .body(
            "pins.find { it.source == 'qits-workspaces' }.url",
            is("http://localhost:1/workspaces/api/pins"));

    // An empty JSON object is the SPA's scoped sweep, which posts {} rather than nothing.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{}")
        .when()
        .post("/artifacts/api/gc/repositories/" + REPO + "/sweep")
        .then()
        .statusCode(200)
        .body("aborted", org.hamcrest.Matchers.containsString("qits-platform-deployments"))
        .body("pins.find { it.source == 'qits-ci' }.url", is("http://localhost:1/ci/api/daemon"));
  }

  @Test
  void aMalformedBodyIsA400RatherThanASilentFallBackToTheHttpReaders() {
    // A caller that meant to supply pins and mistyped them must never be answered with a run that
    // read different ones — quietly reading the closed ports instead would be a plan about a
    // keep-set nobody asked for.
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{\"pins\": not json}")
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(400);
    given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body("{\"pins\": 5}")
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(400);
  }
}
