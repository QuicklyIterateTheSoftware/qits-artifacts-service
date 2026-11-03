package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import eu.wohlben.qits.SuiteBearers;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RepositoryControllerTest {

  @Test
  void ensureIsIdempotentAndListed() {
    // First ensure creates it.
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/ci-screenshots")
        .then()
        .statusCode(200)
        .body("repository.name", is("ci-screenshots"))
        .body("repository.type", is("ci-screenshots"));

    // Re-ensuring is a no-op success (idempotent).
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/ci-screenshots")
        .then()
        .statusCode(200);

    given()
        .header("Authorization", SuiteBearers.operator())
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.name", hasItem("ci-screenshots"));
  }

  @Test
  void ensureRejectsATypeChange() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-videos"))
        .when()
        .put("/artifacts/api/repositories/clip-repo")
        .then()
        .statusCode(200);
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType(ContentType.JSON)
        .body(Map.of("type", "ci-screenshots"))
        .when()
        .put("/artifacts/api/repositories/clip-repo")
        .then()
        .statusCode(400);
  }
}
