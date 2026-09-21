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
 * <p><b>The anonymous rows are per surface now</b>, because closing that gap is a rollout: {@code
 * /v2}, {@code daemons} and {@code sboms} refuse a caller with no credential, while {@code npm},
 * {@code maven} and {@code docs} still carry one. Both halves are pinned below, the still-open half
 * explicitly, so that a later edit flipping one of those three by accident fails here rather than
 * on the estate. {@code PublishGuardGateOffTest} pins the other axis: with the gate off nothing is
 * refused anywhere.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class PublishGuardTest {

  /** Fixture content must be unique per run: versions are immutable and blobs dedupe. */
  private static final String RUN = PublishWires.RUN;

  /** The three surfaces where an anonymous publish is refused today. */
  private static final String FLIPPED = "OCI|DAEMON|SBOM";

  /** The three whose publishers are still the wrapper's archetypes, writing with no credential. */
  private static final String STILL_OPEN = "NPM|MAVEN|DOCS";

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
  @EnumSource(value = Wire.class, names = FLIPPED, mode = EnumSource.Mode.MATCH_ANY)
  void aCiRunPublishesOnAFlippedSurface(Wire wire) {
    // The point of flipping a surface is that the credential works there, not merely that its
    // absence does not. Stated separately from the row above so a flip that refuses everybody —
    // the worst way to close the gap — cannot hide inside a six-wire parameterisation.
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

  // --- the anonymous caller, per surface: three closed, three still open -----------------------

  @ParameterizedTest
  @EnumSource(value = Wire.class, names = FLIPPED, mode = EnumSource.Mode.MATCH_ANY)
  void aPublisherWithNoIdentityIsRefusedOnAFlippedSurface(Wire wire) {
    // Every publisher on these three holds the run's credential now: buildctl and `docker push`
    // after the challenge below, `qits-publish daemon submit` and `qits-publish sbom submit`.
    assertStatus(401, wires.publish(wire));
  }

  @ParameterizedTest
  @EnumSource(value = Wire.class, names = STILL_OPEN, mode = EnumSource.Mode.MATCH_ANY)
  void aPublisherWithNoIdentityStillPublishesOnASurfaceNotYetFlipped(Wire wire) {
    // npm, maven and docs are published to by the npm-library, maven-library and java-service
    // archetypes in the qits-qits wrapper. Their credentialing change reaches CI only once that
    // wrapper is released, and refusing them before then would stop every release on the estate.
    // Nothing else pins maven and docs, and they are exactly what a careless edit would flip.
    assertAccepted(wires.publish(wire));
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
  @EnumSource(value = Wire.class, names = "DAEMON|SBOM", mode = EnumSource.Mode.MATCH_ANY)
  void theOtherTwoRefusalsArePlain(Wire wire) {
    // Nothing that publishes a daemon binary or an SBOM speaks the docker token dance, so a Bearer
    // challenge there would name a door nobody knocks on.
    HttpResponse<String> refused = wires.publish(wire);
    assertStatus(401, refused);
    assertFalse(
        refused.headers().firstValue(RegistryChallenge.WWW_AUTHENTICATE).isPresent(),
        "a plain refusal carries no challenge");
  }

  @ParameterizedTest
  @EnumSource(value = Wire.class, names = STILL_OPEN, mode = EnumSource.Mode.MATCH_ANY)
  void npmsCeremonialTokenIsNoIdentity(Wire wire) {
    // CI's .npmrc carries _authToken=qits-ci. Not three base64url segments, so never handed to
    // OIDC — it is judged as the anonymous caller, and npm is a surface still carrying one.
    assertAccepted(wires.publish(wire, "Authorization", "Bearer qits-ci"));
  }

  @ParameterizedTest
  @EnumSource(value = Wire.class, names = FLIPPED, mode = EnumSource.Mode.MATCH_ANY)
  void aBearerThatIsNotAJwtIsRefusedOnAFlippedSurface(Wire wire) {
    // The same non-JWT bearer on the other side of the rollout: "no identity" is a judgement about
    // the credential, not about whether an Authorization header was sent at all.
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
    // The list is the document of how far the rollout has come, so it is asserted as one: three
    // surfaces refuse, of which exactly one challenges, and three still carry the anonymous
    // publisher. Flipping the next one is a deliberate edit here as well as there.
    assertEquals(
        Map.of(
            "/v2/", PublishGuard.Anonymous.REFUSE_WITH_CHALLENGE,
            "/artifacts/npm/", PublishGuard.Anonymous.ALLOW_ANONYMOUS,
            "/artifacts/maven/", PublishGuard.Anonymous.ALLOW_ANONYMOUS,
            "/artifacts/daemons/", PublishGuard.Anonymous.REFUSE,
            "/artifacts/docs/", PublishGuard.Anonymous.ALLOW_ANONYMOUS,
            "/artifacts/sboms/", PublishGuard.Anonymous.REFUSE),
        PublishGuard.SURFACES.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    PublishGuard.Surface::prefix, PublishGuard.Surface::anonymous)));
  }
}
