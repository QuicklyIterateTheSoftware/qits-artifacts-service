package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.testdb.EmbeddedPg;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b>, with the machine-token rollout gate <b>on</b> — the
 * posture a deployment with qits-platform-idp runs, and one no other suite here can prove:
 * {@link MachineTokens.Enforced} swaps the tenant's key distribution for a configured public key,
 * so the shipped {@code quarkus.oidc.*} block (auth-server-url + jwks-path against a real listener,
 * the boot-time JWKS fetch under {@code connection-delay}, audience enforcement, groups→roles
 * mapping) is exercised nowhere else. The far side is {@link MockIdp}, whose recordings make the
 * interaction assertable on <b>both ends</b>. qits-githost's IT of the same name is the pattern.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with a network diagram beside the steps. The story is browserless (no
 * {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The diagram is observed, never narrated.</b> {@link NetworkTaps#restAssured} taps what a
 * story sends into this service, {@link MockIdp}'s recordings supply what this service sent to the
 * idp, and the framework drains both at story end. A story method therefore asserts and notes; it
 * draws nothing.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a cumulative
 * source is attributed by a cursor, so traffic that happened before any story ran — the startup JWKS
 * fetch, which is the whole subject of the first story — lands in whichever story drains
 * <i>first</i>. Pinning the order is what keeps that the story it belongs to.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-artifacts";

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-the-registry-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-registry-s-admin-door";

  /**
   * Hands the launched artifact its config the way a deployment does — the resource triple as the
   * <b>variable names the shipped expressions read</b>, and the rollout gate as the {@code
   * qits.auth.machine.required} key the shipped {@code quarkus.oidc.tenant-enabled} expression
   * follows — so the expressions themselves stay under test ({@code PackagedProcessIT} predates
   * that pattern and moves the datasource keys directly; the githost IT is the precedent here).
   *
   * <p>The database is the same embedded postgres the surefire suite spawns, under an IT-own name
   * so nothing is shared with {@code PackagedProcessIT}'s. The mock idp starts here — before the
   * application — via {@link MockIdp#ensureStarted()}, which parks its coordinates in system
   * properties: a test profile is instantiated in more than one classloader, and the property
   * table is the one thing every copy (and the story method's {@link MockIdp#attach()}) shares.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    /** The shipped {@code qits.auth.machine.audience} — deliberately NOT overridden. */
    static final String AUDIENCE = "qits-platform";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url("artifacts_userflows_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("quarkus.flyway.artifacts.clean-at-start", "true");
      // The rollout gate, exactly as a deployment flips it: the shipped
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false} expression is what turns
      // the tenant on, so the gate-follows-tenant coupling is itself under test.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test moves: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — jwks-path stays `jwks`, joined onto this base.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      // No qits-platform-deployments, qits-ci or public registry on a build machine — closed ports
      // refuse deterministically, the same three lines PackagedProcessIT passes.
      overrides.put(
          "qits.artifacts.gc.pins.cd-base-url", "http://localhost:1/platform-deployments/api");
      overrides.put("qits.artifacts.gc.pins.ci-base-url", "http://localhost:1/ci/api");
      overrides.put("qits.artifacts.oci.mirror.endpoint-override", "http://localhost:1");
      // Dark outside a deployment, like %dev/%test — a runtime key.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      return overrides;
    }
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>{@link NetworkTaps#restAssured(String)} is the near side (what a story sends here): the
   * framework's own RestAssured filter — the twenty lines four service repositories had each
   * hand-copied as a local {@code StoryNetworkFilter} before the library shipped one, which is why
   * none should be written again. Its default skip is any path with a {@code
   * /q/} <b>segment</b>, which is right here — {@code
   * quarkus.http.non-application-root-path=/artifacts/q} is where health and openapi live, a story
   * readily calls readiness to say the service is up, and a diagram in which every node hangs off
   * {@code /q/health/ready} documents nothing.
   *
   * <p>The idp is the far side, registered as a <b>cumulative</b> source: the supplier hands over
   * the mock's whole request log every time it is asked and the framework remembers how much of it
   * earlier stories already consumed, so the startup fetch — recorded long before any story existed
   * — is attributed to the first story and to that one only. It is invoked lazily at story end, so
   * registering it here is safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  @UserStory(
      value = "On start, the registry fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A qits-artifacts deployed with the machine-token gate on must validate service bearers
      before any caller arrives: at startup it fetches the signing keys (JWKS) from
      qits-platform-idp — discovery stays off, the path is configured — so the very first admin
      write carrying a platform token is accepted.
      """)
  @Order(1)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-artifacts starts with the rollout gate on, beside a reachable qits-platform-idp");
    given().get("/artifacts/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented
    // any token at all. connection-delay=30S is what turns the boot attempt into a retried one.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    // The edge itself is drained from the mock's recording; what is asserted here is that it
    // happened, and the note is the one thing the recording cannot carry — WHEN.
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the registry side: those keys are what token validation now runs on. A platform
    // service's bearer (aud = the platform, roles in `groups`) opens the guarded admin write —
    // AdminWriteGuard's MachineAuth.require(), behind the same PUT every CI process uses.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `a platform service -> qits-artifacts`.
    NetworkCapture.actor("a platform service");
    String platformToken =
        idp.token()
            .subject("qits-ci")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-boot-it")
        .then()
        .statusCode(200);
    story
        .note("a platform service's bearer (aud=" + PackagedWithMockIdp.AUDIENCE
            + ", groups=[qits:system]) opens the admin write")
        .as("admin-write-accepted");
  }

  @UserStory(
      value = "A stranger's token never opens the registry's admin door",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or carrying an audience this platform never issues, is refused at the admin
      door — however well-formed it looks. The refusal is scoped to that door: the byte wires (`docker`,
      `npm`, `mvn`) keep answering on qits-net trust, gate on or off, until machine auth arrives
      for all of them at once.
      """)
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // Both refused credentials are an impostor's, so the actor is set once, up front.
    NetworkCapture.actor("an impostor");

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-denied-it")
        .then()
        .statusCode(401);
    // Both refusals are the same edge — same actor, same route, same status — so the diagram draws
    // one arrow and the notes are what keep the two credentials distinguishable. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-audience").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .contentType("application/json")
        .body("{\"type\":\"ci-screenshots\"}")
        .put("/artifacts/api/repositories/userflows-denied-it")
        .then()
        .statusCode(401);
    story
        .note("a token carrying an audience this platform never issues is refused just the same")
        .as("wrong-audience-refused");

    // The scope of the refusal, asserted from the other side: an anonymous docker ping still
    // answers with the gate on. The wires are unguarded on purpose (qits-net trust; versions are
    // immutable) — RegistryOpenPushTest pins it on the JVM, this pins it in the packaged process
    // with a real tenant validating in front of it.
    //
    // A different caller, so a different actor — set before the request, because the tap reads it
    // when the request is made and knows nothing about who is behind a socket.
    NetworkCapture.actor("an anonymous docker client");
    given()
        .get("/v2/")
        .then()
        .statusCode(200)
        .header("Docker-Distribution-Api-Version", "registry/2.0");
    // "(qits-net trust)" used to ride in this edge's label. It is a REASON rather than an
    // observation — no tap can see a posture — so it belongs in the step log, where an observed 200
    // beside a note explaining why it is a 200 says more than a label claiming both.
    story
        .note("the byte wires stay open on qits-net trust, gate on or off: this one is anonymous")
        .as("wire-stays-open");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        SERVICE,
        MockIdp.SERVICE_NAME,
        "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        NetworkEdge.HTTP,
        "a platform service",
        SERVICE,
        "PUT /artifacts/api/repositories/userflows-boot-it -> 200");
    // And nobody else initiated anything: this story has exactly two initiators, the service (the
    // startup fetch) and the platform service it then admitted. Stated as a set rather than as a
    // count, because how many times quarkus-oidc reads the JWKS is the extension's business — but
    // WHO acted is the story's, and the default `a caller` a story that forgot to name itself would
    // leak is exactly what this refuses.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, ACCEPTED_SLUG, List.of(SERVICE, "a platform service"));
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "admin-write-accepted");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    // Two credentials, one edge: same actor, same route, same status, so the diagram draws one
    // arrow and the two notes below are what keep them apart.
    ReportAssertions.assertEdge(
        CATEGORY,
        DENIED_SLUG,
        NetworkEdge.HTTP,
        "an impostor",
        SERVICE,
        "PUT /artifacts/api/repositories/userflows-denied-it -> 401");
    // The scope of the refusal, as an edge rather than as a sentence: a caller with no credential
    // at all still gets a 200 from the byte wire, with the tenant validating in front of it.
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, NetworkEdge.HTTP, "an anonymous docker client", SERVICE,
        "GET /v2/ -> 200");
    // The sharpest claim this story makes about the impostor: nothing they sent reached anything
    // else. Stated as an absence over the actor rather than as a total count, because the count
    // here is not this service's to promise — quarkus-oidc may re-read the JWKS when it meets a kid
    // the cached set does not carry, and that is a fact about the extension, not about the door.
    ReportAssertions.assertNoEdgesFrom(CATEGORY, DENIED_SLUG, "a platform service");
    // The same claim closed: only these three ever initiated anything here — the impostor, the
    // anonymous docker client, and this service itself if quarkus-oidc went back to the idp for the
    // kid it had never seen. Any fourth initiator is either an actor a later edit forgot to name or
    // traffic that belongs to another story.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, DENIED_SLUG, List.of("an impostor", "an anonymous docker client", SERVICE));
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wire-stays-open");
  }
}
