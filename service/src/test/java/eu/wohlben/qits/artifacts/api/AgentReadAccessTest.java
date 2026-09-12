package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An agent reads what it reads today, under its own role.
 *
 * <p>Agents will stop inheriting their owner's roles and hold {@code qits:agent} instead. Every
 * read on this API accepts that role; no write does.
 *
 * <p>The identity is the forward-auth pair. With {@code X-Qits-User} present, the roles are exactly
 * {@code X-Qits-Roles}, so the {@code %test} dev user's roles do not leak in. Seeding sends no
 * header and so runs as that dev user.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String BASE = "/artifacts/api";

  @Inject ArtifactRepositoryService repositories;

  @BeforeEach
  void seed() {
    repositories.ensure("agent-images", OciImagesProfile.KEY);
    repositories.ensure("agent-npm", NpmPackagesProfile.KEY);
    repositories.ensure("agent-maven", MavenPackagesProfile.KEY);
    repositories.ensure("agent-daemons", DaemonBinariesProfile.KEY);
    repositories.ensure("agent-docs", DocsProfile.KEY);
  }

  private static RequestSpecification asAgent() {
    return given()
        .header("X-Qits-User", "dyn-workspace-agent-reads")
        .header("X-Qits-Roles", "qits:agent");
  }

  @Test
  void anAgentReadsTheRepositoryListing() {
    asAgent().when().get(BASE + "/repositories").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheStoreSummary() {
    asAgent().when().get(BASE + "/store/summary").then().statusCode(200);
  }

  @Test
  void anAgentReadsTheGcPlans() {
    asAgent().when().get(BASE + "/gc/plan").then().statusCode(200);
    asAgent().when().get(BASE + "/gc/repositories").then().statusCode(200);
    asAgent().when().get(BASE + "/gc/repositories/agent-images/plan").then().statusCode(200);
  }

  @Test
  void anAgentBrowsesImages() {
    asAgent().when().get(BASE + "/repositories/agent-images/images").then().statusCode(200);
  }

  @Test
  void anAgentBrowsesNpmPackages() {
    asAgent().when().get(BASE + "/repositories/agent-npm/packages").then().statusCode(200);
  }

  @Test
  void anAgentBrowsesMavenPackages() {
    asAgent().when().get(BASE + "/repositories/agent-maven/maven-packages").then().statusCode(200);
  }

  @Test
  void anAgentBrowsesDaemons() {
    asAgent().when().get(BASE + "/repositories/agent-daemons/daemons").then().statusCode(200);
  }

  @Test
  void anAgentBrowsesDocs() {
    asAgent().when().get(BASE + "/repositories/agent-docs/docs").then().statusCode(200);
  }

  @Test
  void anAgentReadsBlobs() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put(BASE + "/repositories/agent-blobs")
        .then()
        .statusCode(200);
    String id =
        given()
            .contentType("image/png")
            .headers(ArtifactsTestMedia.screenshotHeaders("main", "agent-reads", 100, 50))
            .body(ArtifactsTestMedia.png(100, 50, 23))
            .when()
            .post(BASE + "/repositories/agent-blobs/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    asAgent().when().get(BASE + "/repositories/agent-blobs/blobs").then().statusCode(200);
    asAgent()
        .when()
        .get(BASE + "/repositories/agent-blobs/blobs/" + id)
        .then()
        .statusCode(200)
        .contentType("image/png");
  }

  @Test
  void anAgentCannotWrite() {
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put(BASE + "/repositories/agent-refused")
        .then()
        .statusCode(403);
    asAgent()
        .contentType("image/png")
        .headers(ArtifactsTestMedia.screenshotHeaders("main", "agent-refused", 100, 50))
        .body(ArtifactsTestMedia.png(100, 50, 24))
        .when()
        .post(BASE + "/repositories/agent-images/blobs")
        .then()
        .statusCode(403);
    asAgent().when().post(BASE + "/gc/sweep").then().statusCode(403);
    asAgent().when().post(BASE + "/gc/repositories/agent-images/sweep").then().statusCode(403);
    // A read behind a write verb. It keeps its roles: agents send the GET.
    asAgent().when().post(BASE + "/gc/plan").then().statusCode(403);
  }
}
