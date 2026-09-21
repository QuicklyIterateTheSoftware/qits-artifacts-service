package eu.wohlben.qits.npm;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * npm's ceremonial publish token is no identity: it never reaches OIDC, and the publish lands. CI's
 * {@code .npmrc} carries {@code _authToken=qits-ci}, so this is the anonymous half of the CI-only
 * publish rule ({@code artifacts/api/PublishGuardTest} proves the other half).
 *
 * <p><b>This suite deliberately did NOT invert when its siblings did.</b> {@code
 * registry/RegistryOpenPushTest}, {@code daemon/DaemonOpenPublishTest} and {@code
 * sbom/SbomOpenPublishTest} flipped to "an anonymous publish is refused" on 2026-09-20, and {@code
 * /artifacts/maven} followed on 2026-09-21 (pinned in {@code artifacts/api/PublishGuardTest}
 * rather than in a suite of its own), because every publisher left on those four presents the run's
 * credential — maven out of the {@code <server id="qits">} entry qits-ci's {@code BOOTSTRAP} step
 * writes into {@code /tmp/qits-deploy-settings.xml} and appends to {@code MAVEN_ARGS} as {@code
 * -gs}, which reaches every publisher at once and needed no archetype edit. {@code /artifacts/npm}
 * has no such seam: its remaining publisher is the <b>{@code npm-library} archetype in the
 * qits-qits wrapper</b>, whose credentialing change cannot reach CI until that wrapper is released
 * at workspace resolution — and {@code docs} ({@code java-service}) is in the same position.
 * Refusing either before that release would stop every release on the estate. So this file is the
 * pin on a surface that is open <em>on purpose</em>: it inverts when the wrapper ships, and not a
 * day earlier. {@code PublishGuard.SURFACES} is where the one word changes.
 */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class NpmOpenPublishTest {

  @TestHTTPResource("/") URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.forSystem())
        .body(Map.of("type", "npm-packages"))
        .when()
        .put("/artifacts/api/repositories/npm")
        .then()
        .statusCode(200);
  }

  @Test
  void dummyNpmTokenDoesNotTriggerOidcOnTheRawRegistryRoute() {
    TinyPackage subject = TinyPackage.of("@qits/proactive-auth-regression", "1.0.0");
    try (NpmClient npm =
        new NpmClient(URI.create(root.toString())).header("Authorization", "Bearer qits-ci")) {
      assertEquals(
          201,
          npm.publish("npm", "@qits%2fproactive-auth-regression", subject.publishDocument("latest"))
              .statusCode());
    }
  }
}
