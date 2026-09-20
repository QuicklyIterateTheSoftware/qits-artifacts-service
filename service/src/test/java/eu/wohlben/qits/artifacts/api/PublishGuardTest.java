package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.MachineTokens;
import eu.wohlben.qits.daemon.TinyDaemon;
import eu.wohlben.qits.docs.TinyBundle;
import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.registry.OciClient;
import eu.wohlben.qits.registry.TinyImage;
import eu.wohlben.qits.sbom.TinySbom;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class PublishGuardTest {

  /** Fixture content must be unique per run: versions are immutable and blobs dedupe. */
  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  private static final AtomicInteger SEQ = new AtomicInteger();

  private static final HttpClient HTTP =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @TestHTTPResource("/")
  URL root;

  /** The six wires, each by the one request that creates content on it. */
  enum Wire {
    OCI,
    NPM,
    MAVEN,
    DAEMON,
    DOCS,
    SBOM
  }

  /** The six hosted roots, ensured by a platform service the way a deployment would. */
  @BeforeEach
  void ensureRepositories() {
    Map.of(
            "qits", "oci-images",
            "npm", "npm-packages",
            "maven", "maven-packages",
            "daemons", "daemon-binaries",
            "docs", "docs",
            "sboms", "sboms")
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
    assertAccepted(publish(wire, bearer(MachineTokens.forCiRun())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPersonsBearerIsRefused(Wire wire) {
    assertStatus(403, publish(wire, bearer(MachineTokens.forAdmin())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPlatformServicesBearerIsRefused(Wire wire) {
    // The explicit exception to the open calling model: qits:system may not publish.
    assertStatus(403, publish(wire, bearer(MachineTokens.forSystem())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void anAgentsBearerIsRefused(Wire wire) {
    assertStatus(403, publish(wire, bearer(MachineTokens.forAgent())));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPersonsBrowserSessionIsRefused(Wire wire) {
    // What the edge forwards for a signed-in person: the X-Qits pair, roles from introspection.
    assertStatus(403, publish(wire, forwarded("alice", "qits:admin")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aForwardedServiceOrAgentIsRefused(Wire wire) {
    assertStatus(403, publish(wire, forwarded("dev-qits-ci", "qits:system")));
    assertStatus(403, publish(wire, forwarded("dyn-workspace-1", "qits:agent")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aForwardedCiRunPublishes(Wire wire) {
    // The role decides, not the channel. The edge never asserts this pair; anything on qits-net
    // could, but such a caller could as well send nothing (the known gap below).
    assertAccepted(publish(wire, forwarded("dyn-ci-run-1", "qits:ci-run")));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aClientSecretSentAsBasicIsRefused(Wire wire) {
    // The edge passes a maven/npm Basic id:secret through unchanged. This service cannot check a
    // secret, so it cannot tell a CI run's pair from an agent's.
    String pair =
        Base64.getEncoder()
            .encodeToString("dyn-ci-run-1:secret".getBytes(StandardCharsets.UTF_8));
    assertStatus(401, publish(wire, "Authorization", "Basic " + pair));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aTokenThisServiceDoesNotAcceptIsRefused(Wire wire) {
    // Correctly signed, but addressed to an audience this platform never issues: quarkus-oidc
    // refuses it.
    assertStatus(401, publish(wire, bearer(MachineTokens.forAnotherAudience())));
  }

  // --- the known gap, pinned so that closing it is a visible change --------------------------

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aPublisherWithNoIdentityStillPublishes(Wire wire) {
    // Every CI step on qits-net and every bootstrap seed publishes like this today.
    assertAccepted(publish(wire));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void npmsCeremonialTokenIsNoIdentity(Wire wire) {
    // CI's .npmrc carries _authToken=qits-ci. Not a JWT, so never handed to OIDC.
    assertAccepted(publish(wire, "Authorization", "Bearer qits-ci"));
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
    assertStatus(403, send("PATCH", "v2/" + name + "/blobs/uploads/nope", new byte[] {1}, admin));
    assertStatus(
        403,
        send(
            "PUT",
            "v2/" + name + "/blobs/uploads/nope?digest=" + image.layer().digest(),
            image.layer().bytes(),
            admin));
    assertStatus(
        403,
        send(
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
    assertStatus(200, send("GET", "v2/", null, forwarded("alice", "qits:admin")));
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
    // will one day demand — a guard in front of it would be a loop. It is inside /artifacts but
    // outside the five named wire prefixes, so it falls through; this pins that it stays so.
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.GET, "/artifacts/token"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.POST, "/artifacts/token"));
    assertEquals(false, PublishGuard.isPublish(io.vertx.core.http.HttpMethod.PUT, "/artifacts/token"));
  }

  // --- plumbing ---------------------------------------------------------------------------------

  private HttpResponse<String> publish(Wire wire, String... headers) {
    String id = RUN + "-" + SEQ.incrementAndGet();
    return switch (wire) {
      // The first write of a push; its 202 opens an upload session. The whole push is above.
      case OCI -> send("POST", "v2/qits/publish-guard-" + id + "/blobs/uploads/", new byte[0], headers);
      case NPM ->
          send(
              "PUT",
              "artifacts/npm/npm/@qits%2fpublish-guard-" + id,
              TinyPackage.of("@qits/publish-guard-" + id, "1.0.0").publishDocument("latest"),
              with(headers, "Content-Type", "application/json"));
      case MAVEN ->
          send(
              "PUT",
              "artifacts/maven/maven/eu/wohlben/guard/g-" + id + "/1.0.0/g-" + id + "-1.0.0.jar",
              TinyArtifact.jar(id),
              headers);
      case DAEMON ->
          send("PUT", "artifacts/daemons/qits-guard-daemon/" + id, TinyDaemon.binary(id, 256), headers);
      case DOCS ->
          send(
              "PUT",
              "artifacts/docs/docs/guard-" + id + "/-/1.0.0",
              TinyBundle.storybookLike(id).toTarGz(),
              headers);
      case SBOM ->
          send(
              "PUT",
              "artifacts/sboms/maven/eu.wohlben.guard:g-" + id + "/-/1.0.0",
              TinySbom.document("eu.wohlben.guard:g-" + id, "1.0.0"),
              headers);
    };
  }

  private HttpResponse<String> send(String method, String path, byte[] body, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(root + path))
            .timeout(Duration.ofMinutes(1))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    try {
      return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String[] bearer(String token) {
    return new String[] {"Authorization", "Bearer " + token};
  }

  private static String[] forwarded(String user, String roles) {
    return new String[] {"X-Qits-User", user, "X-Qits-Roles", roles};
  }

  private static String[] with(String[] headers, String name, String value) {
    String[] all = java.util.Arrays.copyOf(headers, headers.length + 2);
    all[headers.length] = name;
    all[headers.length + 1] = value;
    return all;
  }

  private static void assertAccepted(HttpResponse<String> response) {
    assertTrue(
        response.statusCode() >= 200 && response.statusCode() < 300,
        response.uri() + " answered " + response.statusCode() + ": " + response.body());
  }

  private static void assertStatus(int expected, HttpResponse<String> response) {
    assertEquals(
        expected,
        response.statusCode(),
        response.uri() + " answered " + response.statusCode() + ": " + response.body());
  }
}
