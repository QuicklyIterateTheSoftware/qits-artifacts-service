package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JSON admin API with the machine-token gate ON — the posture a deployment with
 * qits-platform-idp runs. Writes (PUT/POST/DELETE) need a bearer carrying the platform audience;
 * reads are not this guard's, only their own {@code @RolesAllowed} (any platform role reads).
 *
 * <p>The gate-OFF posture is what every other suite here runs under, so "unchanged when the gate is
 * off" is asserted by all of them at once rather than by one test.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class AdminWriteGuardTest {

  private static String bearer(String token) {
    return "Bearer " + token;
  }

  @Test
  void aWriteWithoutATokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void aWriteWithATokenForAnotherAudienceIsRejected() {
    // Signed by the same issuer and perfectly valid — just not addressed to us. quarkus-oidc
    // refuses it on quarkus.oidc.token.audience before MachineAuth's own audience check is even
    // reached, so this is a 401 rather than the 403 an already-authenticated caller would get.
    given()
        .header("Authorization", bearer(MachineTokens.forAnotherAudience()))
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(401);
  }

  @Test
  void aUserWithoutTheSystemRoleIsForbidden() {
    // A person's qits CLI token (qits:admin, the platform audience); this write is the platform's,
    // and a person must not reach it just because their token reached the service.
    given()
        .header("Authorization", bearer(MachineTokens.forAdmin()))
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        // The token authenticated a real identity, so this is authorization (403), not a
        // missing-authentication challenge (401). The person still cannot cross the system role.
        .statusCode(403);
  }

  @Test
  void aWriteWithAPlatformTokenSucceedsAndReadsStayOpen() {
    String token = bearer(MachineTokens.forSystem());

    given()
        .header("Authorization", token)
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/guarded")
        .then()
        .statusCode(200);

    // The upload is a publish, so it takes a CI run's token; the service token that ensured the
    // repository is refused on it.
    given()
        .header("Authorization", token)
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
        .body(ArtifactsTestMedia.png(100, 50, 10))
        .when()
        .post("/artifacts/api/repositories/guarded/blobs")
        .then()
        .statusCode(403);

    String id =
        given()
            .header("Authorization", bearer(MachineTokens.forCiRun()))
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
            .body(ArtifactsTestMedia.png(100, 50, 11))
            .when()
            .post("/artifacts/api/repositories/guarded/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    // A read is not this guard's: an operator's token reads the blob the CI run uploaded.
    given()
        .header("Authorization", bearer(MachineTokens.forAdmin()))
        .when()
        .get("/artifacts/api/repositories/guarded/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void everyGuardedPrefixIsCoveredAndItsReadsAreNot() {
    // The prefix set is extended by hand, never inherited — a resource served outside it ships
    // unguarded. The mirror-upstreams cases that used to lead this method went to
    // qits-platform-mirror with the controller; what stays is the shape of the rule: writes under a
    // guarded prefix are 401 without a token, and reads under the same prefix are not this guard's:
    // an operator's token, which holds no qits:system, reads all three (epic qits-543, qits-1358 —
    // a header-less read was the %test dev user, which leaves with qits-1338).
    String operator = bearer(MachineTokens.forAdmin());
    given()
        .header("Authorization", operator)
        .get("/artifacts/api/store/summary")
        .then()
        .statusCode(200);
    given()
        .header("Authorization", operator)
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200);
    given()
        .header("Authorization", operator)
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/guard-probe")
        .then()
        .statusCode(401);

    given()
        .header("Authorization", bearer(MachineTokens.forSystem()))
        .contentType(ContentType.JSON)
        .body(Map.of("type", "oci-images"))
        .when()
        .put("/artifacts/api/repositories/guard-probe")
        .then()
        .statusCode(200);
  }

  @Test
  void thePurgeIsGuardedLikeTheSweeps() {
    // `applications` joined the prefix set with its first door (qits-1941), which deletes past
    // every pin and grace: no token is a 401, a person's token a 403, the platform's a receipt —
    // empty here, because nothing was ever published for the name.
    String purge = "/artifacts/api/applications/guard-probe-application/purge";
    given().contentType(ContentType.JSON).body("{}").when().post(purge).then().statusCode(401);
    given()
        .header("Authorization", bearer(MachineTokens.forAdmin()))
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(purge)
        .then()
        .statusCode(403);
    given()
        .header("Authorization", bearer(MachineTokens.forSystem()))
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(purge)
        .then()
        .statusCode(200)
        .body("removed", org.hamcrest.Matchers.empty());
  }

  @Test
  void theMachineIdentityMayPostTheGcPlanItIsAllowedToSweep() {
    // qits-platform-orchestrator authenticates as qits:system and never holds
    // qits:admin, so a qits:admin-only POST /gc/plan would 403 the one caller the route exists for
    // — while the sweep it feeds stayed open to that same token. This asserts the pair: the machine
    // reads the plan, and it reads it with a body of supplied pins, which is the whole call.
    //
    // SIX members since 2026-09-05, and all six are needed for `executable`: a member left out is
    // that source unanswered. Near-empty answers, because what is on trial here is the role rather
    // than the keep-set — but one pin each from the deployer and maintenance, whose empty answer is
    // a refusal (qits-1172).
    given()
        .header("Authorization", bearer(MachineTokens.forSystem()))
        .contentType(ContentType.JSON)
        .body(
            """
            {"pins":{"deployments":{"pins":[{"applicationName":"qits-artifacts",
                                             "shas":["2026.1.1"]}]},
                     "ciDaemon":{"daemonName":"qits-ci-daemon","daemonVersion":"",
                                 "previousDaemonVersion":"","source":"none"},
                     "dependencies":{"repositories":[],"pins":[{"ecosystem":"maven",
                                     "name":"eu.wohlben.qits:qits-auth-core",
                                     "version":"2026.1.1","repository":"qits-artifacts-service",
                                     "manifestPath":"pom.xml"}]},
                     "workspaceLaunches":{"pins":[]},
                     "projectLaunches":{"pins":[]}}}
            """)
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("executable", org.hamcrest.Matchers.is(true));

    // The guard is untouched by the wider role: no token is still 401 on a write method.
    given().contentType(ContentType.JSON).when().post("/artifacts/api/gc/plan").then().statusCode(401);
  }

  @Test
  void anUploadWithoutATokenIs401() {
    given()
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders("main", "checkout", 100, 50))
        .body(ArtifactsTestMedia.png(100, 50, 12))
        .when()
        .post("/artifacts/api/repositories/guarded/blobs")
        .then()
        .statusCode(401);
  }
}
