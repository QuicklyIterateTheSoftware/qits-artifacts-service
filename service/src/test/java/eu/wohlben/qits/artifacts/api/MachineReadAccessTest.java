package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.MachineTokens;
import eu.wohlben.qits.daemon.TinyDaemon;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Platform services and CI runs read what people and agents read. A service may read everything
 * (the open calling model), and a CI run's composed release prelude resolves the latest {@code
 * qits} CLI from the daemon listing with its {@code qits:ci-run} credential before it downloads the
 * binary. Reads only: no write is widened.
 *
 * <p>Every identity is a {@link MachineTokens} bearer, so the {@code %test} dev user never answers.
 * A token holding none of the read roles is refused, so the grants cannot pass vacuously.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class MachineReadAccessTest {

  private static final String BASE = "/artifacts/api";

  private static final String CLI = "qits-platform-access-cli";

  private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

  @BeforeEach
  void ensureRepositories() {
    Map.of(
            "qits", "oci-images",
            "npm", "npm-packages",
            "maven", "maven-packages",
            "daemons", "daemon-binaries",
            "docs", "docs",
            "ci-screenshots", "ci-screenshots")
        .forEach(
            (repository, type) ->
                given()
                    .header("Authorization", "Bearer " + MachineTokens.forSystem())
                    .contentType(ContentType.JSON)
                    .body(Map.of("type", type))
                    .when()
                    .put(BASE + "/repositories/" + repository)
                    .then()
                    .statusCode(200));
  }

  private static RequestSpecification as(String reader) {
    String token =
        switch (reader) {
          case "ci-run" -> MachineTokens.forCiRun();
          case "system" -> MachineTokens.forSystem();
          default -> throw new IllegalArgumentException(reader);
        };
    return given().header("Authorization", "Bearer " + token);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ci-run", "system"})
  void readsTheDaemonListingTheReleasePreludeResolvesTheCliFrom(String reader) {
    // What CI publishes, a CI run then finds: the release publishes a CLI build, the next run's
    // prelude reads its latestVersion.
    String version = RUN + ("ci-run".equals(reader) ? "-1" : "-2");
    as("ci-run")
        .contentType("application/octet-stream")
        .body(TinyDaemon.binary(version, 256))
        .when()
        .put("/artifacts/daemons/" + CLI + "/" + version)
        .then()
        .statusCode(201);

    as(reader)
        .when()
        .get(BASE + "/repositories/daemons/daemons")
        .then()
        .statusCode(200)
        .body("daemons.name", hasItem(CLI))
        .body("daemons.find { it.name == '" + CLI + "' }.latestVersion", notNullValue());
    as(reader)
        .when()
        .get(BASE + "/repositories/daemons/daemons/" + CLI + "/versions")
        .then()
        .statusCode(200);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ci-run", "system"})
  void readsEveryOtherBrowseRoute(String reader) {
    as(reader).when().get(BASE + "/repositories").then().statusCode(200);
    as(reader).when().get(BASE + "/store/summary").then().statusCode(200);
    as(reader).when().get(BASE + "/gc/plan").then().statusCode(200);
    as(reader).when().get(BASE + "/gc/repositories").then().statusCode(200);
    as(reader).when().get(BASE + "/gc/repositories/qits/plan").then().statusCode(200);
    as(reader).when().get(BASE + "/repositories/qits/images").then().statusCode(200);
    as(reader).when().get(BASE + "/repositories/npm/packages").then().statusCode(200);
    as(reader).when().get(BASE + "/repositories/maven/maven-packages").then().statusCode(200);
    as(reader).when().get(BASE + "/repositories/docs/docs").then().statusCode(200);

    String id =
        as("ci-run")
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "machine-read-" + reader, 90, 60))
            .body(ArtifactsTestMedia.png(90, 60, "ci-run".equals(reader) ? 21 : 22))
            .when()
            .post(BASE + "/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    as(reader).when().get(BASE + "/repositories/ci-screenshots/blobs/" + id).then().statusCode(200);
    as(reader)
        .when()
        .get(BASE + "/repositories/ci-screenshots/blobs?meta.git.branch.name=main")
        .then()
        .statusCode(200);
  }

  @Test
  void aTokenWithNoReadRoleIsRefused() {
    // A workstation Git token: valid, addressed to the platform, and no role this API reads with.
    String none = MachineTokens.withRoles(Set.of("qits:git:external"), "qits-platform");
    given()
        .header("Authorization", "Bearer " + none)
        .when()
        .get(BASE + "/repositories/daemons/daemons")
        .then()
        .statusCode(403);
  }

  @Test
  void aCiRunStillWritesNothingOnTheJsonApi() {
    // The widening is reads only: a repository ensure and a sweep stay qits:system.
    as("ci-run")
        .contentType(ContentType.JSON)
        .body(Map.of("type", "docs"))
        .when()
        .put(BASE + "/repositories/ci-run-may-not-ensure")
        .then()
        .statusCode(403);
    as("ci-run").contentType(ContentType.JSON).when().post(BASE + "/gc/sweep").then().statusCode(403);
  }
}
