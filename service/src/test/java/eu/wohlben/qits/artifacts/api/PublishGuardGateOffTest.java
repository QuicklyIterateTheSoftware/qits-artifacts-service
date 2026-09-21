package eu.wohlben.qits.artifacts.api;

import static eu.wohlben.qits.artifacts.api.PublishWires.assertAccepted;
import static io.restassured.RestAssured.given;

import eu.wohlben.qits.artifacts.api.PublishWires.Wire;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * With the machine-token gate off, an anonymous publish still lands on <b>every</b> surface —
 * including the four {@link PublishGuard} now refuses it on. That is the other axis of the flip,
 * and it is load-bearing rather than tidy: {@code quarkus.oidc.tenant-enabled} follows the same
 * {@code qits.auth.machine.required} key ("there is no third state", in
 * {@code application.properties}), so with the gate off there is no tenant that could validate the
 * credential a refusal would be demanding. A refusal there would make the store unusable rather
 * than stricter, which is why the anonymous branch sits under {@code machineAuth.enforced()} and
 * never beside it.
 *
 * <p><b>No {@code @TestProfile}</b>, deliberately: the gate is off in the shipped configuration, so
 * this posture IS the default application every other profile-free suite already started, and a
 * profile here would be a whole extra Quarkus app for a configuration nobody overrode (AGENTS.md's
 * test-profile budget rule). {@code PublishGuardTest} is the gate-on twin.
 */
@QuarkusTest
class PublishGuardGateOffTest {

  @TestHTTPResource("/")
  URL root;

  private PublishWires wires;

  @BeforeEach
  void ensureRepositories() {
    wires = new PublishWires(root);
    PublishWires.roots()
        .forEach(
            (repository, type) ->
                given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("type", type))
                    .when()
                    .put("/artifacts/api/repositories/" + repository)
                    .then()
                    .statusCode(200));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void anAnonymousPublishLandsOnEverySurface(Wire wire) {
    assertAccepted(wires.publish(wire));
  }

  @ParameterizedTest
  @EnumSource(Wire.class)
  void aCeremonialNonJwtBearerLandsOnEverySurfaceToo(Wire wire) {
    // npm's _authToken, and the stray Authorization a pipeline reuses across several curl calls:
    // with the gate off nothing is judged, so nothing is refused for carrying one.
    assertAccepted(wires.publish(wire, "Authorization", "Bearer qits-ci"));
  }
}
