package eu.wohlben.qits.artifacts.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.gc.GcCondemnations;
import eu.wohlben.qits.artifacts.gc.GcStrategy;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two doors of the two-phase sweep (qits-1172) on the wire: the list of what is condemned, and
 * the restore that lifts one condemnation.
 *
 * <p>A sweep cannot condemn anything in this suite — the pin peers are closed ports, so every run
 * aborts whole — so the condemnations are written straight through {@link GcCondemnations}, the
 * one class that writes them in production too. Each case uses identities of its own, because the
 * service suite has no table reset and other suites share the store.
 *
 * <p>The identities are the hard spellings on purpose: an SBOM identity carries {@code /} and
 * {@code @} in one value ({@code npm/@qits/x@2026.1.0}) and a maven one carries {@code :} ({@code
 * g:a:v}), which are the cases the restore route's greedy last segment exists for.
 */
@QuarkusTest
class GcCondemnedControllerTest {

  private static final String BASE = "/artifacts/api/gc";
  private static final String SBOMS = "gc-condemned-sboms";
  private static final String SBOMS_TWIN = "gc-condemned-sboms-twin";
  private static final String MAVEN = "gc-condemned-maven";

  @Inject ArtifactRepositoryService repositories;
  @Inject GcCondemnations condemnations;

  @BeforeEach
  void seedRepositories() {
    repositories.ensure(SBOMS, SbomProfile.KEY);
    repositories.ensure(SBOMS_TWIN, SbomProfile.KEY);
    repositories.ensure(MAVEN, MavenPackagesProfile.KEY);
  }

  @Test
  void theListingNamesEveryCondemnationWithItsRunAndTheInstantItMayBePurged() {
    String identity = "npm/@qits/listed-" + unique() + "@2026.1.0";
    Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
    condemn(SbomProfile.KEY, SBOMS, identity, at, "run-listed");

    JsonPath listing =
        given().when().get(BASE + "/condemned").then().statusCode(200).extract().jsonPath();

    String entry = "condemned.find { it.identity == '" + identity + "' }";
    org.junit.jupiter.api.Assertions.assertEquals("PT6H", listing.getString("condemnGracePeriod"));
    org.junit.jupiter.api.Assertions.assertEquals("sboms", listing.getString(entry + ".type"));
    org.junit.jupiter.api.Assertions.assertEquals(SBOMS, listing.getString(entry + ".repository"));
    org.junit.jupiter.api.Assertions.assertEquals("run-listed", listing.getString(entry + ".runId"));
    org.junit.jupiter.api.Assertions.assertEquals(
        at, Instant.parse(listing.getString(entry + ".condemnedAt")));
    org.junit.jupiter.api.Assertions.assertEquals(
        at.plus(java.time.Duration.ofHours(6)), Instant.parse(listing.getString(entry + ".purgeAt")));

    restoreDirectly(SbomProfile.KEY, identity);
  }

  @Test
  void aRestoreLiftsTheCondemnationAndSaysThatOnlyAPinKeepsIt() {
    String identity = "npm/@qits/restored-" + unique() + "@2026.1.0";
    condemn(SbomProfile.KEY, SBOMS, identity, Instant.now(), "run-restored");

    // The slashes raw: the route's last variable is greedy up to the literal /restore.
    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/sboms/" + identity + "/restore")
        .then()
        .statusCode(200)
        .body("restored", hasSize(1))
        .body("restored[0].type", is("sboms"))
        .body("restored[0].repository", is(SBOMS))
        .body("restored[0].identity", is(identity))
        .body("restored[0].runId", is("run-restored"))
        .body("restored[0].purgeAt", notNullValue())
        .body("note", containsString("buys time, not retention"));

    given()
        .when()
        .get(BASE + "/condemned")
        .then()
        .statusCode(200)
        .body("condemned.findAll { it.identity == '" + identity + "' }", hasSize(0));

    // Nothing left to lift is a 404, not a silent 200: the caller restoring the wrong spelling must
    // hear that nothing was restored.
    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/sboms/" + identity + "/restore")
        .then()
        .statusCode(404);
  }

  @Test
  void anIdentityMayArrivePercentEncodedAndARepositoryNarrowsTheRestoreToOne() {
    String identity = "npm/@qits/encoded-" + unique() + "@2026.1.0";
    condemn(SbomProfile.KEY, SBOMS, identity, Instant.now(), "run-a");
    condemn(SbomProfile.KEY, SBOMS_TWIN, identity, Instant.now(), "run-b");

    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(
            BASE
                + "/condemned/sboms/"
                + identity.replace("/", "%2F").replace("@", "%40")
                + "/restore?repository="
                + SBOMS_TWIN)
        .then()
        .statusCode(200)
        .body("restored", hasSize(1))
        .body("restored[0].repository", is(SBOMS_TWIN))
        .body("restored[0].identity", is(identity));

    List<GcCondemnations.Condemnation> left = condemnationsOf(SbomProfile.KEY, identity);
    org.junit.jupiter.api.Assertions.assertEquals(1, left.size(), left.toString());
    org.junit.jupiter.api.Assertions.assertEquals(SBOMS, left.get(0).repository());

    // Without ?repository= every repository's condemnation of it is lifted — the one direction a
    // missing parameter may widen a GC call, because a restore deletes nothing.
    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/sboms/" + identity + "/restore")
        .then()
        .statusCode(200)
        .body("restored[0].repository", is(SBOMS));
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of(), condemnationsOf(SbomProfile.KEY, identity));
  }

  @Test
  void aColonedCoordinateIsRestoredRawAndAnUnknownTypeOrIdentityIsA404() {
    String coordinate = "eu.wohlben.qits:condemned-" + unique() + ":1.0.0";
    condemn(MavenPackagesProfile.KEY, MAVEN, coordinate, Instant.now(), "run-maven");

    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/maven-packages/" + coordinate + "/restore")
        .then()
        .statusCode(200)
        .body("restored[0].type", is("maven-packages"))
        .body("restored[0].identity", is(coordinate));
    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/maven-packages/eu.wohlben.qits:never-" + unique() + ":1/restore")
        .then()
        .statusCode(404);
    asSystem()
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/no-such-type/" + coordinate + "/restore")
        .then()
        .statusCode(404);
  }

  @Test
  void theListingIsAReadAndTheRestoreIsQitsSystemsAlone() {
    // The same guard as POST /gc/sweep: a restore changes what the next sweep may delete. The
    // listing reads like the plan does, so the people whose artifact is on it can see it.
    String identity = "npm/@qits/guarded-" + unique() + "@2026.1.0";
    condemn(SbomProfile.KEY, SBOMS, identity, Instant.now(), "run-guarded");

    as("qits:agent").when().get(BASE + "/condemned").then().statusCode(200);
    as("qits:ci-run").when().get(BASE + "/condemned").then().statusCode(200);
    for (String refused : List.of("qits:agent", "qits:ci-run", "qits:admin")) {
      as(refused)
          .urlEncodingEnabled(false)
          .when()
          .post(BASE + "/condemned/sboms/" + identity + "/restore")
          .then()
          .statusCode(403);
    }
    org.junit.jupiter.api.Assertions.assertEquals(
        1, condemnationsOf(SbomProfile.KEY, identity).size(), "a refused restore lifts nothing");

    as("qits:system")
        .urlEncodingEnabled(false)
        .when()
        .post(BASE + "/condemned/sboms/" + identity + "/restore")
        .then()
        .statusCode(200);
    // And reading and executing stay two different verbs here as everywhere under /gc.
    given()
        .urlEncodingEnabled(false)
        .when()
        .get(BASE + "/condemned/sboms/" + identity + "/restore")
        .then()
        .statusCode(405);
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** The forward-auth pair: with a user header present, the roles are exactly these. */
  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "gc-condemned-" + role).header("X-Qits-Roles", role);
  }

  private static RequestSpecification asSystem() {
    return as("qits:system");
  }

  private void condemn(String type, String repository, String identity, Instant at, String runId) {
    condemnations.condemn(
        type, List.of(new GcStrategy.IdentityKey(repository, identity)), at, runId);
  }

  private void restoreDirectly(String type, String identity) {
    condemnations.restore(type, identity, null);
  }

  private List<GcCondemnations.Condemnation> condemnationsOf(String type, String identity) {
    return condemnations.of(type, null).values().stream()
        .filter(condemnation -> condemnation.identity().equals(identity))
        .toList();
  }

  private static String unique() {
    return UUID.randomUUID().toString();
  }
}
