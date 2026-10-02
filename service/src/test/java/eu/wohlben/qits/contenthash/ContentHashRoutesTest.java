package eu.wohlben.qits.contenthash;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.registry.ContentHashLedger;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The content-hash ledger end to end through the real routes: the library's upload routes record
 * through {@code JpaContentHashLedger}, and {@code ContentHashRoutes} reads it back.
 *
 * <p>Every case names a package of its own, so no case depends on what another left in the shared
 * hosted {@code maven} and {@code npm} repositories.
 */
@QuarkusTest
class ContentHashRoutesTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  private static final String GROUP = "eu.wohlben.qits";
  private static final String GROUP_PATH = "eu/wohlben/qits";

  private static final String HASH_A = "v1:sha256:" + "a".repeat(64);
  private static final String HASH_B = "v1:sha256:" + "b".repeat(64);

  @TestHTTPResource("/")
  URL root;

  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  /** The two hosted repositories this surface reads, ensured as SbomRegistryTest ensures its own. */
  @BeforeEach
  void ensureRepositories() {
    for (String[] repository : new String[][] {{"maven", "maven-packages"}, {"npm", "npm-packages"}}) {
      given()
          .contentType("application/json")
          .body("{\"type\":\"" + repository[1] + "\"}")
          .when()
          .put("/artifacts/api/repositories/" + repository[0])
          .then()
          .statusCode(200);
    }
  }

  @AfterEach
  void close() {
    http.close();
  }

  // --- maven ------------------------------------------------------------------------------------

  @Test
  void aMavenPomPutWithTheHeaderIsReadBackAsTheNewestVersionsHash() {
    String artifact = artifact();
    String version = "2026.1001.154547";

    assertEquals(201, deployJar(artifact, version, null).statusCode());
    HttpResponse<String> pom = deployPom(artifact, version, HASH_A);
    assertEquals(201, pom.statusCode(), pom.body());

    JsonObject newest = readOk("maven", GROUP + ":" + artifact, "newest");
    assertEquals("maven", newest.getString("ecosystem"));
    assertEquals(GROUP + ":" + artifact, newest.getString("name"));
    assertEquals(version, newest.getString("version"));
    assertEquals(HASH_A, newest.getString("contentHash"));

    assertEquals(HASH_A, readOk("maven", GROUP + ":" + artifact, version).getString("contentHash"));
  }

  @Test
  void aMavenVersionDeployedWithoutTheHeaderExistsWithANullHash() {
    String artifact = artifact();
    deployJar(artifact, "1.0.0", null);
    assertEquals(201, deployPom(artifact, "1.0.0", null).statusCode());

    JsonObject read = readOk("maven", GROUP + ":" + artifact, "1.0.0");
    assertTrue(read.containsKey("contentHash"), "the key is there, its value null");
    assertEquals(null, read.getValue("contentHash"));
  }

  @Test
  void aMavenVersionWithOnlyItsJarDoesNotExistYet() {
    // The pom is the version's "published" marker; a jar alone is a deploy in flight.
    String artifact = artifact();
    deployJar(artifact, "1.0.0", HASH_A);

    assertEquals(404, read("maven", GROUP + ":" + artifact, "1.0.0").statusCode());
    assertEquals(404, read("maven", GROUP + ":" + artifact, "newest").statusCode());
  }

  @Test
  void aMavenRePutOfTheSameValueIsGreenAndADifferentValueIs409() {
    String artifact = artifact();
    assertEquals(201, deployPom(artifact, "1.0.0", HASH_A).statusCode());
    assertEquals(201, deployPom(artifact, "1.0.0", HASH_A).statusCode(), "a retry stays green");

    HttpResponse<String> conflicting = deployPom(artifact, "1.0.0", HASH_B);
    assertEquals(409, conflicting.statusCode(), conflicting.body());
    assertTrue(conflicting.body().contains(HASH_A), conflicting.body());
    assertTrue(conflicting.body().contains(HASH_B), conflicting.body());

    assertEquals(HASH_A, readOk("maven", GROUP + ":" + artifact, "1.0.0").getString("contentHash"));
  }

  @Test
  void theNewestMavenVersionIsByVersionOrderNotByUploadOrderAndSkipsSnapshots() {
    String artifact = artifact();
    deployPom(artifact, "2026.930.1", HASH_A);
    deployPom(artifact, "2026.1001.1", HASH_B);
    // A replay of an older version uploads last.
    deployPom(artifact, "2026.929.5", "v1:sha256:" + "c".repeat(64));
    // A snapshot is never a release the CLI compares against.
    put(
        "/artifacts/maven/maven/"
            + GROUP_PATH + "/" + artifact + "/2026.1002.1-SNAPSHOT/"
            + artifact + "-2026.1002.1-20261002.101010-1.pom",
        TinyArtifact.pom(GROUP, artifact, "2026.1002.1-SNAPSHOT"),
        null);

    JsonObject newest = readOk("maven", GROUP + ":" + artifact, "newest");
    assertEquals("2026.1001.1", newest.getString("version"));
    assertEquals(HASH_B, newest.getString("contentHash"));
  }

  @Test
  void aDeeperGroupSharingThePrefixIsNotAVersionOfTheShallowerArtifact() {
    String artifact = artifact();
    // eu.wohlben.qits.<artifact>:inner lives under eu/wohlben/qits/<artifact>/inner/1.0.0/…
    put(
        "/artifacts/maven/maven/" + GROUP_PATH + "/" + artifact + "/inner/1.0.0/inner-1.0.0.pom",
        TinyArtifact.pom(GROUP + "." + artifact, "inner", "1.0.0"),
        null);

    assertEquals(404, read("maven", GROUP + ":" + artifact, "newest").statusCode());
  }

  // --- npm --------------------------------------------------------------------------------------

  @Test
  void anNpmPublishWithTheHeaderIsReadBackAndOneWithoutReadsNull() {
    String name = npmName();
    assertEquals(201, publish(name, "1.0.0", "latest", HASH_A).statusCode());
    assertEquals(201, publish(name, "1.1.0", "latest", null).statusCode());

    JsonObject first = readOk("npm", name, "1.0.0");
    assertEquals("npm", first.getString("ecosystem"));
    assertEquals(name, first.getString("name"));
    assertEquals(HASH_A, first.getString("contentHash"));

    JsonObject newest = readOk("npm", name, "newest");
    assertEquals("1.1.0", newest.getString("version"));
    assertEquals(null, newest.getValue("contentHash"));
  }

  @Test
  void theNewestNpmVersionIsByPrecedenceNotByTheLatestDistTagReplayIncluded() {
    String name = npmName();
    publish(name, "2026.930.1", "latest", HASH_A);
    // A higher version under a tag of its own: latest stays behind on 2026.930.1.
    assertEquals(201, publish(name, "2026.1001.1", "next", HASH_B).statusCode());
    // The replay: an older version, published last, under a tag of its own.
    HttpResponse<String> replay = publish(name, "2026.929.5", "replay", null);
    assertEquals(201, replay.statusCode(), replay.body());

    HttpResponse<String> packument =
        send(
            HttpRequest.newBuilder(
                    URI.create(root + "artifacts/npm/npm/" + name.replace("/", "%2f")))
                .GET());
    assertEquals(
        "2026.930.1",
        new JsonObject(packument.body()).getJsonObject("dist-tags").getString("latest"),
        "the precondition: latest and the highest version disagree");

    JsonObject newest = readOk("npm", name, "newest");
    assertEquals("2026.1001.1", newest.getString("version"));
    assertEquals(HASH_B, newest.getString("contentHash"));
  }

  // --- refusals ---------------------------------------------------------------------------------

  @Test
  void anUnknownNameAVersionThatDoesNotExistAndAnUnknownEcosystemAreAll404() {
    assertEquals(404, read("maven", GROUP + ":never-published-" + unique(), "newest").statusCode());
    assertEquals(404, read("npm", "@qits/never-published-" + unique(), "newest").statusCode());
    assertEquals(404, read("npm", "@qits/never-published-" + unique(), "1.0.0").statusCode());
    assertEquals(404, read("maven", "not-a-coordinate", "newest").statusCode());

    HttpResponse<String> ecosystem = read("docker", "qits/qits-artifacts", "newest");
    assertEquals(404, ecosystem.statusCode());
    assertTrue(
        ecosystem.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"),
        "the plain 404, never the SPA's HTML");

    String name = npmName();
    publish(name, "1.0.0", "latest", HASH_A);
    assertEquals(404, read("npm", name, "1.0.1").statusCode());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private HttpResponse<String> deployJar(String artifact, String version, String hash) {
    return put(
        "/artifacts/maven/maven/" + GROUP_PATH + "/" + artifact + "/" + version + "/"
            + artifact + "-" + version + ".jar",
        TinyArtifact.jar(artifact + version),
        hash);
  }

  private HttpResponse<String> deployPom(String artifact, String version, String hash) {
    return put(
        "/artifacts/maven/maven/" + GROUP_PATH + "/" + artifact + "/" + version + "/"
            + artifact + "-" + version + ".pom",
        TinyArtifact.pom(GROUP, artifact, version),
        hash);
  }

  private HttpResponse<String> publish(String name, String version, String tag, String hash) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(
                URI.create(root + "artifacts/npm/npm/" + name.replace("/", "%2f")))
            .header("Content-Type", "application/json")
            .PUT(
                HttpRequest.BodyPublishers.ofByteArray(
                    TinyPackage.of(name, version).publishDocument(tag)));
    if (hash != null) {
      builder.header(ContentHashLedger.HEADER, hash);
    }
    return send(builder);
  }

  private HttpResponse<String> put(String path, byte[] body, String hash) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(root + path.substring(1)))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body));
    if (hash != null) {
      builder.header(ContentHashLedger.HEADER, hash);
    }
    return send(builder);
  }

  private HttpResponse<String> read(String ecosystem, String name, String version) {
    return send(
        HttpRequest.newBuilder(
                URI.create(
                    root + "artifacts/content-hashes/" + ecosystem + "/" + name + "/-/" + version))
            .GET());
  }

  private JsonObject readOk(String ecosystem, String name, String version) {
    HttpResponse<String> response = read(ecosystem, name, version);
    assertEquals(200, response.statusCode(), response.body());
    return new JsonObject(response.body());
  }

  private HttpResponse<String> send(HttpRequest.Builder builder) {
    try {
      return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String artifact() {
    return "content-hash-" + unique();
  }

  private static String npmName() {
    return "@qits/content-hash-" + unique();
  }

  /** Unique across runs too: an npm version once published can never be published again. */
  private static String unique() {
    return Long.toString(System.currentTimeMillis(), 36) + "-" + UNIQUE.incrementAndGet();
  }
}
