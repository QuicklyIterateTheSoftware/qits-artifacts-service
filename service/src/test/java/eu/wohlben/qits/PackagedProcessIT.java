package eu.wohlben.qits;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.testdb.EmbeddedPg;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.daemon.DaemonClient;
import eu.wohlben.qits.daemon.TinyDaemon;
import eu.wohlben.qits.docs.TinyBundle;
import eu.wohlben.qits.maven.MavenClient;
import eu.wohlben.qits.maven.TinyArtifact;
import eu.wohlben.qits.npm.NpmClient;
import eu.wohlben.qits.npm.TinyPackage;
import eu.wohlben.qits.registry.OciClient;
import eu.wohlben.qits.registry.TinyImage;
import eu.wohlben.qits.stories.support.AccessLogSource;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Everything the {@code @QuarkusTest} suite cannot prove: that the <b>packaged process</b> serves
 * what the sources say it serves. Under {@code -Dnative} that process is {@code
 * service/target/qits-artifacts}, and this is the only place a GraalVM binary is exercised at all —
 * native-image resolves reflection, {@code ServiceLoader} and resource lookups at build time, so a
 * gap in any of them leaves the JVM suite green and fails only in the binary, at runtime.
 *
 * <p>Deliberately <b>one</b> class spanning both packages rather than one per context, because the
 * thing under test is the single process: {@code /} is Quinoa's static SPA, {@code
 * /artifacts/api/**} is JAX-RS, {@code /artifacts/q/**} is Quarkus' non-application root, and
 * {@code /artifacts/npm/**}, {@code /artifacts/maven/**}, {@code /artifacts/docs/**}, {@code
 * /artifacts/daemons/**} and {@code /v2/**} are independent raw-Vert.x route stacks — and what
 * needs proving is that all of them resolve in the same binary.
 * Splitting it by package would split the subject.
 *
 * <p>The SPA is here rather than in the {@code @QuarkusTest} suite because it <b>cannot</b> be
 * there: Quinoa logs "Quinoa is disabled by default in tests" and registers neither the static
 * resources nor the SPA re-route, so a unit test asserting any of this would pass against a process
 * that has no web UI in it at all. This is the only suite that sees the real thing.
 *
 * <p>The git smart-HTTP cases that used to live here went to qits-githost with the host, and JGit
 * — the reason this suite was written — went with them. What is left is still the only place the
 * binary is exercised at all.
 *
 * <p><b>The class is public only so {@link TargetDirState} can be named from another package.</b>
 * Every user story under {@code eu.wohlben.qits.stories.*} shares this profile — one launched
 * process for the whole IT phase — and a nested type is no more visible than the class around it.
 * The test methods themselves stay package-private.
 */
@QuarkusIntegrationTest
@TestProfile(PackagedProcessIT.TargetDirState.class)
public class PackagedProcessIT {

  /**
   * Gives the launched process a database, because the shipped config deliberately has none: the
   * {@code QITS_RESOURCE_DB_*} expressions are unresolvable outside a deployment, which is the
   * refuse-to-boot stance. So this is not a relocation of a default any more — it is the whole
   * datasource, and the binary would die at Flyway without it.
   *
   * <p><b>The database is the suite's own embedded postgres, and the url arrives through a system
   * property.</b> {@link EmbeddedPg} publishes the port it took into {@code
   * qits.test.embedded-pg.port}, which is what makes this safe: Quarkus instantiates a {@code
   * QuarkusTestProfile} in TWO classloaders, so the static field in a second copy of that class is
   * a second copy — the system property is the one thing they share, and it is what keeps this at
   * one postgres rather than one per classloader.
   *
   * <p><b>It also turns on the access log, and that is the story catalogue's network tap.</b> Every
   * class under {@code eu.wohlben.qits.stories} shares this profile and drives a real external tool
   * — npm, mvn, skopeo, curl — over a socket the test JVM is not on, so a RestAssured filter can see
   * nothing and the server's own record is the only observation there is. {@link
   * eu.wohlben.qits.stories.support.AccessLogSource} owns the pattern, the file name and the reading
   * of it; this profile only asks for it.
   *
   * <p>All of these are runtime config, so they reach the already-built artifact as {@code -D}
   * flags; nothing here re-augments. That is also why the datasource can move at all: url, username
   * and password are runtime keys, while {@code db-kind} is build-time and stays what the jar ships.
   * The access-log block is runtime too ({@code VertxHttpConfig}, not {@code
   * VertxHttpBuildTimeConfig}) — a build-time key here would be accepted and silently ignored.
   */
  public static class TargetDirState implements QuarkusTestProfile {

    static final Path ROOT = Path.of(System.getProperty("user.dir"), "target", "packaged-it");

    /** Its own database on the shared instance, so the unit suite's rows are not in the way. */
    private static final String DATABASE = "artifacts_it";

    @Override
    public Map<String, String> getConfigOverrides() {
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("quarkus.datasource.artifacts.jdbc.url", EmbeddedPg.url(DATABASE));
      overrides.put("quarkus.datasource.artifacts.username", EmbeddedPg.USER);
      overrides.put("quarkus.datasource.artifacts.password", EmbeddedPg.PASSWORD);
      overrides.put("quarkus.flyway.artifacts.clean-at-start", "true");
      // No qits-platform-deployments or qits-ci here either, and the shipped defaults name them by their qits-net
      // aliases — which on a build machine resolve to whatever the resolver feels like, or hang.
      // Closed ports make the refusal deterministic while still driving a real HttpClient inside
      // the binary.
      overrides.put("qits.artifacts.gc.pins.cd-base-url", "http://localhost:1/deployments/api");
      overrides.put("qits.artifacts.gc.pins.ci-base-url", "http://localhost:1/ci/api");
      overrides.put("qits.artifacts.gc.pins.maintenance-base-url", "http://localhost:1/maintenance/api");
      overrides.put("qits.artifacts.gc.pins.configuration-base-url", "http://localhost:1/configuration/api");
      overrides.put("qits.artifacts.gc.pins.workspaces-base-url", "http://localhost:1/workspaces/api");
      overrides.put("qits.artifacts.gc.pins.projects-base-url", "http://localhost:1/projects/api");
      // The mirror upstream override stays although nothing here mirrors. V14 took V7's three
      // prefilled upstream rows out, so this binary resolves no mirror namespace at all — but the
      // oci jar still carries the miss path, and a closed port is what keeps a row put back by
      // accident from dialling quay.io or Docker Hub out of a test.
      overrides.put("qits.artifacts.oci.mirror.endpoint-override", "http://localhost:1");
      // THE STORIES' NETWORK TAP. Every class under eu.wohlben.qits.stories shares this profile,
      // and their subject is a real external tool — npm, mvn, skopeo, curl — talking to this
      // process over a socket the test JVM is not on. Nothing in here can observe that, so the
      // observation is the server's own access log; AccessLogSource owns the file name, the
      // pattern and the reading of it, and registers the file as a NetworkCapture source. Every
      // key it returns is runtime config, which is what lets it reach an already-built artifact.
      overrides.putAll(AccessLogSource.configOverrides());
      return overrides;
    }
  }

  @TestHTTPResource("/")
  URL root;

  /** The string that identifies a response as the CLIENT's index.html rather than anything else. */
  private static final String BASE_HREF = "<base href=\"/\">";

  /** The daemon this suite publishes and browses — its own name, so no other case's rows are in it. */
  private static final String BROWSE_DAEMON = "packaged-it-browse-daemon";

  /** A scoped docs site, so the browse template's two spellings have something to resolve. */
  private static final String BROWSE_SITE = "@qits/packaged-it-browse-docs";

  /**
   * The gateway's asserted identity, played by this suite for the JSON API. Every read there is
   * {@code @RolesAllowed("qits:admin")}, the repository write {@code "qits:system"} and the blob
   * upload {@code "qits:ci-run"} (see {@link #asCiRun()}); a
   * {@code @QuarkusTest} passes them on the %test synthetic dev-user, but the PACKAGED process runs
   * {@code LaunchMode.NORMAL}, where {@code ForwardAuthMechanism} deliberately stays anonymous — so
   * this suite asserts the header pair qits-gateway would, believed by contract (the gateway strips
   * client-supplied copies; network trust is the deployed posture). The wire cases below
   * deliberately do NOT use this: tokenless on qits-net is their contract, and it is asserted by
   * their requests staying header-free.
   */
  private static io.restassured.specification.RequestSpecification asOperator() {
    return given()
        .header("X-Qits-User", "packaged-it")
        .header("X-Qits-Roles", "qits:admin,qits:system");
  }

  /** A CI run's asserted identity — the only one that publishes (USER RULING 2026-09-13). */
  private static io.restassured.specification.RequestSpecification asCiRun() {
    return given().header("X-Qits-User", "packaged-it-ci-run").header("X-Qits-Roles", "qits:ci-run");
  }

  @Test
  void theSpaIsServedAtTheRootOfThisServicesOwnHost() {
    // Quinoa builds src/main/webui (the qits-spa-artifacts submodule) during augmentation and the
    // files ship inside the artifact. The base href is the client's half of the same coupling:
    // Quinoa mounts the files at quarkus.quinoa.ui-root-path, but only the baseHref baked in by
    // `ng build` makes the browser ask for them there. Asserting both together is what makes a
    // one-sided move fail here instead of in a browser.
    //
    // Both are `/` now: this service has a host of its own (registry.<env>.<domain>) and the client
    // is what that host serves.
    given()
        .when()
        .get("/")
        .then()
        .statusCode(200)
        .contentType(containsString("text/html"))
        .body(containsString(BASE_HREF));
  }

  @Test
  void aDeepLinkFallsBackToTheSpaButTheOtherStacksAreNotSwallowed() {
    // quarkus.quinoa.enable-spa-routing puts a catch-all at the ui root near the end of the route
    // order: a client route has no file behind it and must reach index.html, or every reload and
    // every pasted link 404s. Both spellings the client routes are probed — its own, and the
    // platform's scoped one.
    given().when().get("/repositories/npm").then().statusCode(200).body(containsString(BASE_HREF));
    given()
        .when()
        .get("/qits/services/qits-artifacts/repositories/npm")
        .then()
        .statusCode(200)
        .body(containsString(BASE_HREF));

    // The other half, and the reason quarkus.quinoa.ignored-path-prefixes is spelled out rather
    // than left to Quinoa's derivation. The ui root is `/`, so EVERY machine path is inside the
    // catch-all now and only these two entries keep it out.
    //
    // /artifacts covers the four wire stacks that live under the segment as literals in their own
    // classes. Un-ignored, /artifacts/npm answers 200 text/html and an npm client parses a web page
    // as JSON.
    given().when().get("/artifacts/npm/npm/@qits%2fnothing").then().statusCode(404);

    // The npm case verbatim, one segment over: MavenRoutes spells /artifacts/maven as a literal, so
    // without the ignore a mistyped artifact path would answer 200 text/html and a maven client
    // would report a corrupt jar.
    given().when().get("/artifacts/maven/maven").then().statusCode(404);

    // The bare segment is the machine surface, not a door to the client: nothing serves it, and a
    // 404 is the answer rather than the page.
    given()
        .when()
        .get("/artifacts/")
        .then()
        .statusCode(404)
        .body(not(containsString(BASE_HREF)));

    // Setting the key REPLACED the derivation, so the two it used to supply are re-asserted here —
    // both by a live route and by a mistyped sibling that must not answer the page.
    asOperator().when().get("/artifacts/api/repositories").then().statusCode(200);
    given()
        .when()
        .get("/artifacts/api/nope")
        .then()
        .statusCode(404)
        .body(not(containsString(BASE_HREF)));
    given().when().get("/artifacts/q/health/ready").then().statusCode(200);
  }

  @Test
  void frameworkSurfaceIsServedUnderTheQSegment() {
    // quarkus.http.non-application-root-path, and swagger-ui only because always-include=true
    // survives into a packaged (non-dev) build — the setting exists for exactly this.
    given().when().get("/artifacts/q/openapi").then().statusCode(200);
    given().when().get("/artifacts/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  void theBlobStoreBootsItsOwnSchemaAndServesBytes() {
    // Reaching a repository row at all means Flyway migrated this process' own PostgreSQL schema,
    // the startup seed wrote its rows through Panache, and Jackson serialised them back. `qits`,
    // `npm`, `maven`, `daemons` and `docs` are in the set because a packaged process is the only
    // thing here that runs the seed for real: they are what let a fresh deployment take a `docker
    // push`, an `npm publish`, an `mvn deploy` and a docs publish with no manual ensure call.
    //
    // `npmjs` is NOT in the set and stopped being at the byte-plane split — it was a pull-through
    // cache and it went to qits-platform-mirror with the seeding line that made it.
    asOperator()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body(
            "repositories.name",
            hasItems(
                "ci-screenshots", "ci-videos", "qits", "npm", "maven", "daemons", "docs"));

    byte[] png = png(120, 80);
    String id =
        asCiRun()
            .contentType("image/png")
            .headers(screenshotHeaders("main", "packaged-it"))
            .body(png)
            .when()
            .post("/artifacts/api/repositories/ci-screenshots/blobs")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    byte[] served =
        asOperator()
            .when()
            .get("/artifacts/api/repositories/ci-screenshots/blobs/" + id)
            .then()
            .statusCode(200)
            .contentType("image/png")
            .extract()
            .asByteArray();
    assertArrayEquals(png, served, "the packaged process should serve back the exact bytes");
  }

  @Test
  void anUnknownRepositoryIsRejectedByTheRepositoryLifecycle() {
    // The exception mapper is JAX-RS machinery that native-image has to have kept: an unknown
    // repository must surface as 404, not as a 500 from a missing reflective constructor.
    asOperator()
        .when()
        .get("/artifacts/api/repositories/no-such-repo/blobs/deadbeef")
        .then()
        .statusCode(404);
  }

  @Test
  void theRegistryIsMountedAtTheHostRootNotUnderTheArtifactsSegment() {
    // /v2 is a literal in the route code — no config key moves it, and docker
    // and podman hardcode the prefix at the ROOT, so a drift to /artifacts/v2 would be invisible
    // everywhere except here.
    given()
        .when()
        .get("/v2/")
        .then()
        .statusCode(200)
        .header("Docker-Distribution-Api-Version", "registry/2.0");
    given().when().get("/artifacts/v2/").then().statusCode(404);

    // /v2 is INSIDE the ui root since the client moved to `/`, so it is the SPA catch-all it has to
    // be kept out of now rather than a path nothing could reach. A mistyped registry path must be a
    // 404 and never the page: served index.html a docker client gets 200 text/html with no
    // Docker-Distribution-Api-Version header and reports something that names neither cause nor fix.
    given()
        .when()
        .get("/v2/nope/manifests")
        .then()
        .statusCode(404)
        .body(not(containsString(BASE_HREF)));
  }

  @Test
  void anImageRoundTripsThroughTheBinary() {
    // The registry's whole path in one test, and the parts of it that only a binary can falsify:
    // a blob is served with HttpServerResponse.sendFile — through Vert.x' FileResolver and a Netty
    // file region, neither of which behaves the same in a native image as on the JVM — and the
    // upload arrives CHUNKED, which is the encoding docker uses and the one the global wire ceiling
    // does not gate.
    ensureOciRepository();

    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      TinyImage subject = TinyImage.of("packaged-it");
      client.push("qits/it", "latest", subject);

      TinyImage pulled = client.pull("qits/it", "latest");
      assertArrayEquals(subject.manifest(), pulled.manifest());
      assertArrayEquals(
          subject.layer().bytes(),
          pulled.layer().bytes(),
          "sendFile must return the exact bytes from the binary");
    }
  }

  @Test
  void anUnknownBlobAnswersTheSpecErrorEnvelopeRatherThanA500() {
    // The UploadResult lesson, restated for a raw Vert.x route: nothing in the build sees a type
    // that is only serialised inside such a handler, so a DTO there 500s in the binary while the
    // JVM suite stays green. Asserting the envelope's SHAPE is what catches that — a bare 404 would
    // not. (The registry builds this with JsonObject precisely so it cannot happen.)
    //
    // The repository has to exist for this to be a BLOB_UNKNOWN rather than a NAME_UNKNOWN; both
    // would prove the envelope, but a test that asserts one and accepts the other depending on
    // method order is not asserting anything.
    ensureOciRepository();
    given()
        .urlEncodingEnabled(false)
        .when()
        .get("/v2/qits/it/blobs/sha256:" + "0".repeat(64))
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("errors[0].code", equalTo("BLOB_UNKNOWN"));
  }

  @Test
  void anNpmPackageRoundTripsThroughTheBinary() {
    // The second raw-Vert.x route stack, proved to coexist with /v2 in one
    // binary — and the parts of it only a binary can falsify: a scoped name arrives percent-encoded
    // and is matched by a regex route, the packument is built as a Jackson tree with no bound type
    // anywhere (the dto/UploadResult lesson), and the tarball comes back through
    // HttpServerResponse.sendFile, which behaves differently under native-image than on the JVM.
    //
    // The seed has already written the `npm` row in a packaged process; this states its own
    // precondition anyway rather than resting on another test's subject.
    asOperator()
        .contentType("application/json")
        .body("{\"type\":\"npm-packages\"}")
        .when()
        .put("/artifacts/api/repositories/npm")
        .then()
        .statusCode(200);

    TinyPackage subject = TinyPackage.of("@qits/packaged-it", "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      assertEquals(
          201,
          npm.publish("npm", "@qits%2fpackaged-it", subject.publishDocument("latest")).statusCode());

      JsonNode packument = npm.packumentJson("npm", "@qits%2fpackaged-it");
      assertEquals("1.0.0", packument.path("dist-tags").path("latest").asText());
      assertArrayEquals(
          subject.tarball(),
          npm.tarball(NpmClient.tarballUrl(packument, "1.0.0")).body(),
          "sendFile must return the exact bytes from the binary");
    }
  }

  @Test
  void anUnknownNpmPackageAnswersNpmsErrorShapeRatherThanA500() {
    // The UploadResult lesson once more, on a third stack: the error envelope is built with a
    // JsonObject precisely so nothing has to be registered for reflection, and asserting its SHAPE
    // is what would catch a DTO creeping in — a bare 404 would not.
    given()
        .when()
        .get("/artifacts/npm/npm/no-such-package")
        .then()
        .statusCode(404)
        .contentType(containsString("json"))
        .body("error", containsString("no such package"));
  }

  @Test
  void aMavenReleaseDeploysAndResolvesThroughTheBinary() {
    // The fourth raw-Vert.x route stack, proved to coexist with /v2, /artifacts/npm and
    // /v2 and /artifacts/npm in one binary — and the parts only a binary can falsify: the deploy PUT
    // streams through VertxInputStream, the metadata document and the checksums are derived per
    // request, and the jar comes back through HttpServerResponse.sendFile, which behaves
    // differently under native-image than on the JVM.
    //
    // The seed has already written the `maven` row in a packaged process; this states its own
    // precondition anyway rather than resting on another test's subject.
    asOperator()
        .contentType("application/json")
        .body("{\"type\":\"maven-packages\"}")
        .when()
        .put("/artifacts/api/repositories/maven")
        .then()
        .statusCode(200);

    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      String ga = "eu/wohlben/qits/packaged-it";
      String base = ga + "/1.0.0/packaged-it-1.0.0";
      byte[] jar = TinyArtifact.jar("packaged-it release");
      assertEquals(201, maven.put("maven", base + ".jar", jar).statusCode());
      assertEquals(
          201,
          maven.put(
                  "maven", base + ".pom",
                  TinyArtifact.pom("eu.wohlben.qits", "packaged-it", "1.0.0"))
              .statusCode());
      // The deploy plugin's last steps: the checksum claim, verified; the client's metadata merge,
      // accepted and discarded.
      assertEquals(
          201,
          maven.put("maven", base + ".jar.sha1", TinyArtifact.hex(jar, "SHA-1").getBytes())
              .statusCode());
      assertEquals(
          201,
          maven.put("maven", ga + "/maven-metadata.xml", "<metadata/>".getBytes()).statusCode());

      assertArrayEquals(
          jar,
          maven.get("maven", base + ".jar").body(),
          "sendFile must return the exact bytes from the binary");

      HttpResponse<String> metadata = maven.getText("maven", ga + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(metadata.body().contains("<release>1.0.0</release>"), metadata.body());
      assertTrue(metadata.body().contains("<version>1.0.0</version>"), metadata.body());

      assertEquals(
          TinyArtifact.hex(jar, "SHA-256"),
          maven.getText("maven", base + ".jar.sha256").body(),
          "the checksum is derived from the stored bytes, in the binary");
    }
  }

  @Test
  void aMavenSnapshotDeploysAndItsVersionMetadataDerivesThroughTheBinary() {
    // The ⚖1 flow end to end in the packaged process: a timestamped deploy as ordinary files, and
    // the version-level document derived from the names — the whole of the server's snapshot
    // machinery.
    try (MavenClient maven = new MavenClient(URI.create(root.toString()))) {
      String dir = "eu/wohlben/qits/packaged-it-snap/1.0.1-SNAPSHOT";
      byte[] jar = TinyArtifact.jar("packaged-it snapshot");
      assertEquals(
          201,
          maven.put(
                  "maven",
                  dir + "/packaged-it-snap-1.0.1-20260802.123456-1.jar",
                  jar)
              .statusCode());

      HttpResponse<String> metadata = maven.getText("maven", dir + "/maven-metadata.xml");
      assertEquals(200, metadata.statusCode(), metadata.body());
      assertTrue(
          metadata.body().contains("<value>1.0.1-20260802.123456-1</value>"), metadata.body());
      assertTrue(metadata.body().contains("<buildNumber>1</buildNumber>"), metadata.body());
    }
  }

  @Test
  void theBrowseEndpointsSurviveTheCompileOverContentPushedThroughTheWire() {
    // Ten response shapes, every one of them a Jackson-serialised record — which is the
    // dto/UploadResult trap restated: a bound type reaches the native build only through the
    // provider chain that discovers it, and a gap there is a green `mvn verify` and a 500 in the
    // binary. Asserting the FIELDS is what catches it; a status code would not. The four newest
    // families carry an Instant, a nullable Instant and — DocsVersionSummary — a Map read off a
    // LAZY @ElementCollection, which is three more ways a native compile can lose a type.
    //
    // The subjects are pushed through the registry, the npm registry, the daemon wire and the docs
    // wire rather than written into the database, because the sizes are the point: they are read out
    // of the stored manifest document, off the blob rows and out of a real tar.gz, so a fixture that
    // skipped the wire would not prove the parse happens in the binary at all.
    ensureOciRepository();
    try (OciClient client = new OciClient(URI.create(root.toString()))) {
      client.push("qits/browse-it", "v1", TinyImage.of("browse-it"));
    }
    TinyPackage subject = TinyPackage.of("@qits/browse-it", "1.0.0");
    try (NpmClient npm = new NpmClient(URI.create(root.toString()))) {
      assertEquals(
          201, npm.publish("npm", "@qits%2fbrowse-it", subject.publishDocument("latest")).statusCode());
    }
    // Two daemon releases, header-free like every wire case here: tokenless on qits-net is the
    // contract. Different bytes, so the version listing's per-row size is a real reading.
    byte[] firstBinary = TinyDaemon.binary("browse-it-one", 2048);
    byte[] secondBinary = TinyDaemon.binary("browse-it-two", 4096);
    try (DaemonClient daemons = new DaemonClient(URI.create(root.toString()))) {
      assertEquals(201, daemons.put(BROWSE_DAEMON, "2026.828.1", firstBinary).statusCode());
      assertEquals(201, daemons.put(BROWSE_DAEMON, "2026.828.2", secondBinary).statusCode());
    }
    // Two docs versions of one scoped site. TinyBundle's font is byte-identical across bundles, so
    // the two versions really do share a blob — which is what makes the site's size a union in the
    // binary rather than only in the unit suite's fixture.
    publishDocsVersion("1.0.0", "browse-a");
    publishDocsVersion("1.0.1", "browse-b");

    asOperator()
        .when()
        .get("/artifacts/api/repositories")
        .then()
        .statusCode(200)
        .body("repositories.find { it.name == 'qits' }.type", equalTo("oci-images"))
        .body("repositories.find { it.name == 'qits' }.itemCount", greaterThanOrEqualTo(1))
        .body("repositories.find { it.name == 'qits' }.sizeBytes", greaterThan(0));

    asOperator()
        .when()
        .get("/artifacts/api/repositories/qits/images")
        .then()
        .statusCode(200)
        .body("images.find { it.name == 'browse-it' }.tagCount", equalTo(1))
        .body("images.find { it.name == 'browse-it' }.sizeBytes", greaterThan(0));

    asOperator()
        .when()
        .get("/artifacts/api/repositories/qits/images/browse-it/tags")
        .then()
        .statusCode(200)
        .body("tags.find { it.tag == 'v1' }.digest", containsString("sha256:"))
        .body("tags.find { it.tag == 'v1' }.sizeBytes", greaterThan(0))
        .body("tags.find { it.tag == 'v1' }.createdAt", notNullValue());

    // The percent-encoded scoped name, on the JAX-RS surface this time. The npm routes prove it for
    // a Vert.x regex route; a path template is different machinery and needs its own case.
    asOperator()
        .urlEncodingEnabled(false)
        .when()
        .get("/artifacts/api/repositories/npm/packages/@qits%2fbrowse-it/versions")
        .then()
        .statusCode(200)
        .body("versions[0].version", equalTo("1.0.0"))
        .body("versions[0].tarballSizeBytes", greaterThan(0))
        .body("versions[0].distTags", hasItems("latest"));

    asOperator()
        .when()
        .get("/artifacts/api/repositories/npm/packages")
        .then()
        .statusCode(200)
        .body("packages.find { it.name == '@qits/browse-it' }.latest", equalTo("1.0.0"));

    // The seven figures, from a process that has really walked its own blob directory.
    asOperator()
        .when()
        .get("/artifacts/api/store/summary")
        .then()
        .statusCode(200)
        .body("ociUnionBytes", greaterThan(0))
        .body("diskTotalBytes", greaterThan(0))
        .body("npmPublishedBytes", greaterThan(0))
        .body("ociPerImageSumBytes", greaterThanOrEqualTo(0))
        .body("orphanBytes", greaterThanOrEqualTo(0))
        .body("mavenPublishedBytes", greaterThanOrEqualTo(0))
        // Zero, not merely non-negative: this service registers no cache type at all, so the four
        // cache figures are structurally zero rather than merely empty.
        .body("npmProxyTarballBytes", equalTo(0))
        .body("npmProxyPackumentBytes", equalTo(0))
        .body("ociMirrorBytes", equalTo(0))
        .body("mavenProxyBytes", equalTo(0));

    // The daemon listing. The URL reads oddly — /repositories/daemons/daemons — because the wire
    // has no repository segment and the explorer's subject is a repository throughout.
    asOperator()
        .when()
        .get("/artifacts/api/repositories/daemons/daemons")
        .then()
        .statusCode(200)
        .body("daemons.find { it.name == '" + BROWSE_DAEMON + "' }.versionCount", equalTo(2))
        .body(
            "daemons.find { it.name == '" + BROWSE_DAEMON + "' }.latestVersion",
            equalTo("2026.828.2"))
        .body(
            "daemons.find { it.name == '" + BROWSE_DAEMON + "' }.latestPublishedAt",
            notNullValue())
        .body("daemons.find { it.name == '" + BROWSE_DAEMON + "' }.sizeBytes", greaterThan(0));

    // The digest asserted EXACTLY, not merely as a prefix: this is the string an operator pins, and
    // it has to be the same one the publish receipt and Docker-Content-Digest carried.
    asOperator()
        .when()
        .get("/artifacts/api/repositories/daemons/daemons/" + BROWSE_DAEMON + "/versions")
        .then()
        .statusCode(200)
        .body("versions", hasSize(2))
        .body("versions[0].version", equalTo("2026.828.2"))
        .body("versions[0].digest", equalTo("sha256:" + TinyDaemon.sha256(secondBinary)))
        .body("versions[0].sizeBytes", equalTo(secondBinary.length))
        .body("versions[0].publishedAt", notNullValue())
        // Nothing has downloaded it by version, so the column is null — the honest answer, and a
        // field a native compile can still drop even though it carries nothing.
        .body("versions[0].accessedAt", nullValue())
        .body("versions[1].version", equalTo("2026.828.1"))
        .body("versions[1].digest", equalTo("sha256:" + TinyDaemon.sha256(firstBinary)))
        .body("versions[1].sizeBytes", equalTo(firstBinary.length));

    int siteBytes =
        asOperator()
            .when()
            .get("/artifacts/api/repositories/docs/docs")
            .then()
            .statusCode(200)
            .body("sites.find { it.name == '" + BROWSE_SITE + "' }.versionCount", equalTo(2))
            .body("sites.find { it.name == '" + BROWSE_SITE + "' }.latestVersion", equalTo("1.0.1"))
            .body("sites.find { it.name == '" + BROWSE_SITE + "' }.latestPublishedAt", notNullValue())
            .body("sites.find { it.name == '" + BROWSE_SITE + "' }.sizeBytes", greaterThan(0))
            .extract()
            .path("sites.find { it.name == '" + BROWSE_SITE + "' }.sizeBytes");

    // Both spellings, on the JAX-RS surface. The docs WIRE accepts only the literal one — DocsPaths
    // has no percent-encoded separator — so this template is the only thing answering for the other.
    int newestVersionBytes = 0;
    int olderVersionBytes = 0;
    for (String spelling : new String[] {BROWSE_SITE.replace("/", "%2F"), BROWSE_SITE}) {
      var answered =
          asOperator()
              .urlEncodingEnabled(false)
              .when()
              .get("/artifacts/api/repositories/docs/docs/" + spelling + "/versions")
              .then()
              .statusCode(200)
              .body("versions", hasSize(2))
              .body("versions[0].version", equalTo("1.0.1"))
              .body("versions[0].fileCount", equalTo(3))
              .body("versions[0].sizeBytes", greaterThan(0))
              .body("versions[0].publishedAt", notNullValue())
              .body("versions[0].accessedAt", nullValue())
              // The LAZY @ElementCollection, read through the browse surface in the binary.
              .body("versions[0].metadata.'git.branch.name'", equalTo("main"))
              .body("versions[1].version", equalTo("1.0.0"))
              .extract();
      newestVersionBytes = answered.path("versions[0].sizeBytes");
      olderVersionBytes = answered.path("versions[1].sizeBytes");
    }
    assertTrue(
        siteBytes < newestVersionBytes + olderVersionBytes,
        "a site is the union of its versions: the font both bundles ship is counted once, and this"
            + " is the arithmetic measured in the binary rather than over a fixture");

    asOperator().when().get("/artifacts/api/repositories/no-such-repo/images").then().statusCode(404);
    asOperator().when().get("/artifacts/api/repositories/npm/images").then().statusCode(400);
    asOperator().when().get("/artifacts/api/repositories/no-such-repo/daemons").then().statusCode(404);
    asOperator().when().get("/artifacts/api/repositories/no-such-repo/docs").then().statusCode(404);
    asOperator().when().get("/artifacts/api/repositories/npm/daemons").then().statusCode(400);
    asOperator().when().get("/artifacts/api/repositories/npm/docs").then().statusCode(400);
  }

  @Test
  void theGcPlanSurvivesTheCompileAndReportsAnHonestEmptyState() {
    // Four more Jackson-serialised records, so the same trap as the browse shapes — and one thing a
    // JVM test cannot show: this process has really walked its own blob directory to decide what
    // nothing references. The store here holds blobs pushed by the cases above, so the row-less
    // figures are a real reading rather than a zero that would pass either way.
    //
    // Every one of the eight REGISTERED types is claimed. Eight, not ten: the three cache profiles
    // are vetoed out of bean discovery here, so a plan that reported on them would be reporting on
    // qits-platform-mirror's types. oci-images and daemon-binaries must name their
    // strategy — and, with none of the six pin peers answering, must report the refusal rather than
    // a plan. That
    // fail-closed path only exists in the binary if the JDK HttpClient survived the compile, so this
    // is the one assertion here that a JVM test cannot make on its behalf, and every type on an
    // engine takes the same path. The two CI stubs carry their captions.
    asOperator()
        .when()
        .get("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(true))
        .body("graceWindow", equalTo("PT6H"))
        .body("types", hasSize(8))
        .body("types.find { it.type == 'oci-images' }.strategy", equalTo("OciImageGcStrategy"))
        .body("types.find { it.type == 'oci-images' }.error", containsString("qits-platform-deployments"))
        .body("types.find { it.type == 'oci-images' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'npm-packages' }.strategy", equalTo("NpmPackagesGcStrategy"))
        .body("types.find { it.type == 'npm-packages' }.error", containsString("live pins"))
        .body("types.find { it.type == 'npm-packages' }.dead", hasSize(0))
        .body("types.find { it.type == 'npm-packages' }.reclaimableBytes", equalTo(0))
        .body(
            "types.find { it.type == 'daemon-binaries' }.strategy",
            equalTo("DaemonBinariesGcStrategy"))
        .body("types.find { it.type == 'daemon-binaries' }.error", containsString("qits-ci"))
        .body("types.find { it.type == 'daemon-binaries' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'maven-packages' }.strategy",
            equalTo("MavenPackagesGcStrategy"))
        .body("types.find { it.type == 'maven-packages' }.error", containsString("live pins"))
        .body("types.find { it.type == 'maven-packages' }.dead", hasSize(0))
        .body(
            "types.find { it.type == 'ci-screenshots' }.strategy",
            equalTo("CiScreenshotsGcStrategy"))
        // The stub answers with its rule-naming note at zero rows and a fail-closed refusal once
        // the upload case above has run — both honest, and which one depends on method order, so
        // the assertion accepts the pair rather than betting on the order.
        .body(
            "types.find { it.type == 'ci-screenshots' }.note"
                + " ?: types.find { it.type == 'ci-screenshots' }.error",
            containsString("branch"))
        .body("types.find { it.type == 'ci-videos' }.strategy", equalTo("CiVideosGcStrategy"))
        .body(
            "types.find { it.type == 'ci-videos' }.note"
                + " ?: types.find { it.type == 'ci-videos' }.error",
            containsString("byte"))
        .body("sweep.blobCount", equalTo(0))
        .body("sweep.blobIds", hasSize(0))
        .body("untouchable.blobCount", greaterThanOrEqualTo(0))
        .body("untouchable.reason", containsString("LOSES its last row"));

    // Reading and executing stay two different URLs, asserted against the binary. POST on the plan
    // is a READER now — the pins-in-request twin of the GET, and with no body it answers exactly
    // what the GET answers (this line was `405` before that twin existed) — so what distinguishes
    // it from the sweep is that it still plans rather than deletes.
    asOperator()
        .contentType("application/json")
        .when()
        .post("/artifacts/api/gc/plan")
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(true));

    // And the execute surface itself, in the binary: one more Jackson-serialised record family, and
    // the receipt of a sweep with no pin source to read. It ABORTS whole rather than degrading the
    // way the plan above does, which is the settlement's all-or-nothing rule — so every type
    // carries the abort and nothing is deleted.
    asOperator()
        .when()
        .post("/artifacts/api/gc/sweep")
        .then()
        .statusCode(200)
        .body("dryRun", equalTo(false))
        .body("graceWindow", equalTo("PT6H"))
        .body("types", hasSize(8))
        .body("aborted", containsString("qits-platform-deployments"))
        .body("types.find { it.type == 'oci-images' }.error", containsString("qits-platform-deployments"))
        .body("types.find { it.type == 'npm-packages' }.deleted", hasSize(0))
        .body("sweep.blobsUnlinked", equalTo(0))
        .body("sweep.unlinkedBlobIds", hasSize(0))
        // NOT the plan's "LOSES its last row": an aborted run never took a census, and claiming an
        // untouchable pool it never measured would be claiming something. GcPinsTest pins the same
        // sentence on the JVM side.
        .body("untouchable.reason", containsString("not computed"));
  }

  @org.junit.jupiter.api.Test
  void theDocsWireCarriesMetadataThroughTheCompile() {
    // The docs wire's V2 feature, proved against the BINARY: header parsing (the shared
    // ArtifactMetadataHeaders reading), the @ElementCollection write, the JsonObject metadata
    // member, and the ?meta.* filter — the whole chain a JVM-green build could still lose to a
    // native compile if anything reflective crept in. An unscoped site name on purpose, so this
    // asserts the feature rather than RestAssured's path encoding.
    byte[] bundle = TinyBundle.storybookLike("packaged-meta").toTarGz();
    given()
        .header("X-Artifacts-Meta-git.branch.name", "main")
        .header("X-Artifacts-Meta-git.commit.hash", "f".repeat(40))
        .body(bundle)
        .when()
        .put("/artifacts/docs/docs/packaged-it-userflows/-/" + "f".repeat(40))
        .then()
        .statusCode(201)
        .body("metadata.'git.branch.name'", equalTo("main"));

    given()
        .when()
        .get("/artifacts/docs/docs/packaged-it-userflows?meta.git.branch.name=main")
        .then()
        .statusCode(200)
        .body("versions", hasSize(1))
        .body("versions[0].version", equalTo("f".repeat(40)))
        .body("versions[0].metadata.'git.commit.hash'", equalTo("f".repeat(40)));

    given()
        .when()
        .get("/artifacts/docs/docs/packaged-it-userflows?meta.git.branch.name=elsewhere")
        .then()
        .statusCode(200)
        .body("versions", hasSize(0));
  }

  /**
   * One docs release through the WIRE, header-free apart from the metadata a publisher rides in
   * with — the tokenless-on-qits-net contract every wire case here keeps.
   */
  private void publishDocsVersion(String version, String salt) {
    given()
        .urlEncodingEnabled(false)
        .header("X-Artifacts-Meta-git.branch.name", "main")
        .header("X-Artifacts-Meta-git.commit.hash", "a".repeat(40))
        .body(TinyBundle.storybookLike(salt).toTarGz())
        .when()
        .put("/artifacts/docs/docs/" + BROWSE_SITE + "/-/" + version)
        .then()
        .statusCode(201);
  }

  private void ensureOciRepository() {
    asOperator()
        .contentType("application/json")
        .body("{\"type\":\"oci-images\"}")
        .when()
        .put("/artifacts/api/repositories/qits")
        .then()
        .statusCode(200);
  }

  /**
  /** A minimal but real PNG, so the sniffer and the IHDR resolution check see actual bytes. */
  private static byte[] png(int width, int height) {
    byte[] b = new byte[33];
    System.arraycopy(
        new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, b, 0, 8);
    b[11] = 13; // IHDR chunk length
    b[12] = 'I';
    b[13] = 'H';
    b[14] = 'D';
    b[15] = 'R';
    b[19] = (byte) width;
    b[23] = (byte) height;
    b[24] = 8; // bit depth
    b[25] = 6; // colour type
    return b;
  }

  private static Map<String, String> screenshotHeaders(String branch, String flow) {
    Map<String, String> h = new LinkedHashMap<>();
    h.put("X-Artifacts-Meta-git.branch.name", branch);
    h.put("X-Artifacts-Meta-git.commit.hash", "abc123");
    h.put("X-Artifacts-Meta-qits.userflow.name", flow);
    h.put("X-Artifacts-Meta-qits.userflow.hash", "flowhash");
    h.put("X-Artifacts-Meta-qits.display.name", "step 1");
    h.put("X-Artifacts-Meta-qits.diff.hash", "diffhash");
    h.put("X-Artifacts-Meta-media.resolution.width", "120");
    h.put("X-Artifacts-Meta-media.resolution.height", "80");
    return h;
  }
}
