package eu.wohlben.qits.artifacts.api;

import static eu.wohlben.qits.artifacts.api.PublishWires.assertAccepted;
import static eu.wohlben.qits.artifacts.api.PublishWires.assertStatus;
import static eu.wohlben.qits.artifacts.api.PublishWires.bearer;
import static eu.wohlben.qits.artifacts.api.PublishWires.forwarded;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.MachineTokens;
import eu.wohlben.qits.artifacts.api.PublishWires.Wire;
import eu.wohlben.qits.docs.TinyBundle;
import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.registry.OciClient;
import eu.wohlben.qits.registry.TinyImage;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Only a CI run publishes (USER RULING 2026-09-13): {@link PublishGuard} on all six wires, with the
 * machine-token gate on.
 *
 * <p>Every identity here is explicit — a {@link MachineTokens} bearer or a forwarded pair. The
 * {@code %test} dev user never answers, because the guard reads the headers and the token itself.
 * Each refused case expects a status the wire's own handler never gives for that request (403, or
 * 401), and each accepted case is a real publish of real content, so neither half can pass for the
 * wrong reason.
 *
 * <p><b>The anonymous caller is refused on every one of the six wires</b>, since 2026-09-21: the
 * rollout that closed them one at a time finished when {@code npm} and {@code docs} flipped, and
 * this suite no longer has an open half to pin. What it pins instead is the stronger statement —
 * nothing writes to this store without naming itself — and the {@link
 * PublishGuard.Anonymous#REFUSE} rows are asserted as a whole map below so that re-opening a
 * surface is a deliberate edit here as well as there. {@code PublishGuardGateOffTest} pins the
 * other axis, and it is the reason {@link PublishGuard.Anonymous#ALLOW_ANONYMOUS} still exists:
 * with the gate off nothing is refused anywhere.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class PublishGuardTest {

  /** Fixture content must be unique per run: versions are immutable and blobs dedupe. */
  private static final String RUN = PublishWires.RUN;

  /**
   * The five surfaces that answer a plain 401 — every one but {@code /v2}, which challenges. The
   * only split left across the six wires, now that the rollout has closed them all: everything a
   * flipped/still-open constant used to say is said by {@code @EnumSource(Wire.class)}.
   */
  private static final String PLAIN_REFUSALS = "NPM|MAVEN|DAEMON|DOCS|SBOM";

  @TestHTTPResource("/")
  URL root;

  private PublishWires wires;

  /** The six hosted roots, ensured by a platform service the way a deployment would. */
  @BeforeEach
  void ensureRepositories() {
    wires = new PublishWires(root);
    PublishWires.roots()
        .forEach(
            (repository, type) ->
                given()
                    .header("Authorization", "Bearer " + MachineTokens.forSystem())
                    .contentType(ContentType.JSON)
                    .body(Map.of("type", type))
                    .when()
                    .put("/artifacts/api/repositories/" + repository)
                    .then()
                    .statusCode(200));
  }

  // --- the role table -------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aCiRunPublishes(Wire wire) {
    assertAccepted(wires.publish(wire, bearer(MachineTokens.forCiRun())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aCiRunPublishesOnAFlippedSurface(Wire wire) {
    // The point of flipping a surface is that the credential works there, not merely that its
    // absence does not — the worst way to close a gap is to refuse everybody, and that failure
    // looks identical to success from the anonymous rows alone. It reads as the row above now that
    // every wire is flipped, and it is kept saying its own thing: the row above is about the role
    // table, this one is the standing obligation on a surface that refuses, and it is what the
    // seventh wire will be added under.
    assertAccepted(wires.publish(wire, bearer(MachineTokens.forCiRun())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPersonsBearerIsRefused(Wire wire) {
    assertStatus(403, wires.publish(wire, bearer(MachineTokens.forAdmin())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPlatformServicesBearerIsRefused(Wire wire) {
    // The explicit exception to the open calling model: qits:system may not publish.
    assertStatus(403, wires.publish(wire, bearer(MachineTokens.forSystem())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void anAgentsBearerIsRefused(Wire wire) {
    assertStatus(403, wires.publish(wire, bearer(MachineTokens.forAgent())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPersonsBrowserSessionIsRefused(Wire wire) {
    // What the edge forwards for a signed-in person: the X-Qits pair, roles from introspection.
    assertStatus(403, wires.publish(wire, forwarded("alice", "qits:admin")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aForwardedServiceOrAgentIsRefused(Wire wire) {
    assertStatus(403, wires.publish(wire, forwarded("dev-qits-ci", "qits:system")));
    assertStatus(403, wires.publish(wire, forwarded("dyn-workspace-1", "qits:agent")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aForwardedCiRunPublishes(Wire wire) {
    // The role decides, not the channel. The edge never asserts this pair; anything on qits-net
    // could. The branch is terminal: a pair naming a CI run has been judged, and on a flipped
    // surface a fallthrough would judge it a second time as a caller with no bearer — a 401 for a
    // request the edge already authenticated.
    assertAccepted(wires.publish(wire, forwarded("dyn-ci-run-1", "qits:ci-run")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aClientSecretSentAsBasicIsRefused(Wire wire) {
    // The edge passes a maven/npm Basic id:secret through unchanged. This service cannot check a
    // secret, so it cannot tell a CI run's pair from an agent's.
    String pair =
        Base64.getEncoder()
            .encodeToString("dyn-ci-run-1:secret".getBytes(StandardCharsets.UTF_8));
    assertStatus(401, wires.publish(wire, "Authorization", "Basic " + pair));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aTokenThisServiceDoesNotAcceptIsRefused(Wire wire) {
    // Correctly signed, but addressed to an audience this platform never issues: quarkus-oidc
    // refuses it.
    assertStatus(401, wires.publish(wire, bearer(MachineTokens.forAnotherAudience())));
  }

  // --- the anonymous caller: every surface closed ----------------------------------------------

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPublisherWithNoIdentityIsRefusedOnEverySurface(Wire wire) {
    // Every publisher on all six holds the run's credential now: buildctl and `docker push` after
    // the challenge below, `qits-publish daemon submit`, `qits-publish sbom submit`, every `mvn
    // deploy` out of the settings file qits-ci-service 2026.921.80307's BOOTSTRAP step appends to
    // MAVEN_ARGS as `-gs`, and — since wrapper commit 7f917fb reached main — the `npm-library`
    // archetype's `_authToken` and the `java-service` archetype's `curl -H "Authorization:
    // Bearer …"`.
    assertStatus(401, wires.publish(wire));
  }

  @Test
  void theRefusedMavenPublishStoresNothing() {
    // 401 is the answer; the property is that the jar never reached the store. A refusal that
    // still wrote would be the worst of both — a guard that reads as closed over an open surface.
    String path =
        "artifacts/maven/maven/eu/wohlben/guard/refused-"
            + RUN
            + "/1.0.0/refused-"
            + RUN
            + "-1.0.0.jar";
    assertStatus(401, wires.send("PUT", path, TinyArtifact.jar("refused-" + RUN)));
    assertStatus(404, wires.send("HEAD", path, null));
    assertStatus(404, wires.send("GET", path, null));

    // And the same coordinate lands for a CI run, so the 404s above are the refusal's doing and
    // not a path this wire would never have served.
    assertAccepted(
        wires.send("PUT", path, TinyArtifact.jar("refused-" + RUN), bearer(MachineTokens.forCiRun())));
    assertStatus(200, wires.send("HEAD", path, null));
  }

  @Test
  void theRefusedNpmPublishStoresNothing() {
    // npm's turn at the same property, and the one where a guard that refused too late would do the
    // most damage: an npm version is immutable, so a half-written one cannot be republished over —
    // it would need a tombstone and a GC pass to clear.
    String name = "@qits/refused-" + RUN;
    String path = "artifacts/npm/npm/@qits%2frefused-" + RUN;
    byte[] document = TinyPackage.of(name, "1.0.0").publishDocument("latest");
    assertStatus(401, wires.send("PUT", path, document, "Content-Type", "application/json"));
    assertStatus(404, wires.send("HEAD", path, null));
    assertStatus(404, wires.send("GET", path, null));

    // And the same coordinate lands for a CI run, so the 404s above are the refusal's doing and
    // not a path this wire would never have served.
    assertAccepted(
        wires.send(
            "PUT",
            path,
            document,
            PublishWires.with(
                bearer(MachineTokens.forCiRun()), "Content-Type", "application/json")));
    assertStatus(200, wires.send("HEAD", path, null));
  }

  @Test
  void theRefusedDocsPublishStoresNothing() {
    // The same property on the wire with the quietest failure mode: a docs bundle is a website, so
    // a partially stored version is a page served with pieces missing rather than an error anybody
    // reports. `docs/DocsOpenPublishTest` states this wire's posture in its own protocol's terms;
    // this row is the store-side half, beside maven's and npm's.
    String site = "refused-" + RUN;
    String path = "artifacts/docs/docs/" + site + "/-/1.0.0";
    byte[] bundle = TinyBundle.storybookLike(site).toTarGz();
    assertStatus(401, wires.send("PUT", path, bundle));
    assertStatus(404, wires.send("GET", path, null));
    // HEAD is read on a FILE of the bundle, not on the version path: DocsRoutes gives BUNDLE a GET
    // and no HEAD twin, so a HEAD there is the base catch-all's 404 whether the version exists or
    // not, and asserting it would be asserting nothing.
    assertStatus(404, wires.send("HEAD", path + "/index.html", null));

    assertAccepted(wires.send("PUT", path, bundle, bearer(MachineTokens.forCiRun())));
    assertStatus(200, wires.send("GET", path, null));
    assertStatus(200, wires.send("HEAD", path + "/index.html", null));
  }

  @Test
  void theAnonymousRegistryRefusalCarriesTheBearerChallenge() {
    // The status alone is not the contract on /v2: docker and buildkit send a credential only
    // after a realm they can reach names one, so a 401 without this header would break every image
    // push instead of making it authenticate.
    HttpResponse<String> refused = wires.publish(Wire.OCI);
    assertStatus(401, refused);
    String challenge =
        refused.headers().firstValue(RegistryChallenge.WWW_AUTHENTICATE).orElseThrow();
    assertEquals(
        RegistryChallenge.bearerChallenge(
            "http", URI.create(root.toString()).getAuthority(), Optional.empty()),
        challenge);
    assertTrue(
        challenge.contains("realm=\"http://" + URI.create(root.toString()).getAuthority()
            + RegistryChallenge.TOKEN_PATH + "\""),
        challenge);
  }

  @ParameterizedTest
  @EnumSource(value = Wire.class, names = PLAIN_REFUSALS, mode = EnumSource.Mode.MATCH_ANY)
  void theOtherFiveRefusalsArePlain(Wire wire) {
    // Nothing that publishes a daemon binary, an SBOM, a maven artifact, an npm package or a docs
    // bundle speaks the docker token dance, so a Bearer challenge there would name a door nobody
    // knocks on. maven specifically authenticates PREEMPTIVELY out of its <server> entry and never
    // does a 401-then-retry; npm would read the header as a reason to re-send its _authToken
    // ceremony, and the `curl` that publishes docs as a reason to prompt. Each would make its own
    // wrong thing of a header meant for one client family.
    HttpResponse<String> refused = wires.publish(wire);
    assertStatus(401, refused);
    assertFalse(
        refused.headers().firstValue(RegistryChallenge.WWW_AUTHENTICATE).isPresent(),
        "a plain refusal carries no challenge");
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aBearerThatIsNotAJwtIsNoIdentityAndIsRefusedEverywhere(Wire wire) {
    // jwtBearer returning null is not only the caller who sent no Authorization header at all:
    // npm's mandatory _authToken ceremony puts `Bearer qits-ci` on the wire, which is not three
    // base64url segments either, so it is never handed to OIDC and lands in exactly the same
    // branch — judged by the surface's own state rather than as an invalid token. "No identity" is
    // a judgement about the credential, not about whether one was sent.
    assertStatus(401, wires.publish(wire, "Authorization", "Bearer qits-ci"));
  }

  // --- the registry's other write verbs, and reads ---------------------------------------------

  @Test
  void aCiRunPushesAWholeImageThatAPersonCannotPush() {
    String name = "qits/publish-guard-image-" + RUN;
    TinyImage image = TinyImage.of("publish-guard-" + RUN);
    try (OciClient ci = new OciClient(URI.create(root.toString())).bearer(MachineTokens.forCiRun())) {
      ci.push(name, "latest", image);
      assertArrayEquals(image.manifest(), ci.pull(name, "latest").manifest());
    }

    // Every write verb is guarded, not only the POST that opens a session. Unguarded, these would
    // answer 404 (no such session) or 201.
    String[] admin = bearer(MachineTokens.forAdmin());
    assertStatus(
        403, wires.send("PATCH", "v2/" + name + "/blobs/uploads/nope", new byte[] {1}, admin));
    assertStatus(
        403,
        wires.send(
            "PUT",
            "v2/" + name + "/blobs/uploads/nope?digest=" + image.layer().digest(),
            image.layer().bytes(),
            admin));
    assertStatus(
        403,
        wires.send(
            "PUT",
            "v2/" + name + "/manifests/by-a-person",
            image.manifest(),
            "Authorization",
            admin[1],
            "Content-Type",
            image.manifestMediaType()));
  }

  @Test
  void readsAreNotPublishing() {
    // A person reads what CI published: GET and HEAD pass the guard whatever the identity.
    String name = "qits/publish-guard-read-" + RUN;
    TinyImage image = TinyImage.of("publish-guard-read-" + RUN);
    try (OciClient ci = new OciClient(URI.create(root.toString())).bearer(MachineTokens.forCiRun())) {
      ci.push(name, "latest", image);
    }
    try (OciClient person =
        new OciClient(URI.create(root.toString())).bearer(MachineTokens.forAdmin())) {
      assertArrayEquals(image.manifest(), person.pull(name, "latest").manifest());
    }
    assertStatus(200, wires.send("GET", "v2/", null, forwarded("alice", "qits:admin")));
    // And an anonymous read is not a publish either: the flip touched the write verbs alone, so a
    // pull from a machine with no credential — which is every bootstrap — still answers.
    assertStatus(200, wires.send("GET", "v2/", null));
  }

  @Test
  void theGuardClaimsExactlyThePublishRoutes() {
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.POST, "/v2/qits/x/blobs/uploads/"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PATCH, "/v2/qits/x/blobs/uploads/s"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/v2/qits/x/manifests/1"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/npm/npm/x"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/maven/maven/a"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/daemons/d/1"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/docs/docs/s/-/1"));
    assertTrue(PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/sboms/npm/x/-/1"));
    // Reads, the 405 deletes, npm's POST audit and the JSON API are not the guard's.
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.GET, "/v2/qits/x/manifests/1"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.DELETE, "/v2/qits/x/manifests/1"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.POST, "/artifacts/npm/-/npm/v1/security/audits"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/api/repositories/x"));
    // And neither is the token endpoint, which is the door that BUYS the credential this guard
    // demands on /v2 — a guard in front of it would be a loop. It is inside /artifacts but outside
    // the five named wire prefixes, so it falls through; this pins that it stays so.
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.GET, "/artifacts/token"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.POST, "/artifacts/token"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/token"));
  }

  @Test
  void theSurfaceListIsTheRollout() {
    // The list was the document of how far the rollout had come, and it is asserted as one so that
    // no surface can change state by accident — which is why the two rows that moved on 2026-09-21
    // were moved HERE deliberately too, and not merely observed to have moved. What it says now:
    // all six refuse the anonymous publisher, exactly one of them with a challenge. A row coming
    // back to ALLOW_ANONYMOUS is a surface re-opened to an uncredentialed publisher, and it breaks
    // this test on the way.
    assertEquals(
        Map.of(
            "/v2/", PublishGuard.Anonymous.REFUSE_WITH_CHALLENGE,
            "/artifacts/npm/", PublishGuard.Anonymous.REFUSE,
            "/artifacts/maven/", PublishGuard.Anonymous.REFUSE,
            "/artifacts/daemons/", PublishGuard.Anonymous.REFUSE,
            "/artifacts/docs/", PublishGuard.Anonymous.REFUSE,
            "/artifacts/sboms/", PublishGuard.Anonymous.REFUSE),
        PublishGuard.SURFACES.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    PublishGuard.Surface::prefix, PublishGuard.Surface::anonymous)));
  }
}
