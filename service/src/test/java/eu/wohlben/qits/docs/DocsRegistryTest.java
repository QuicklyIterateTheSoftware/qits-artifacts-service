package eu.wohlben.qits.docs;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.SuiteBearers;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The docs wire, end to end.
 *
 * <p>Absolute paths throughout, deliberately: {@code /artifacts/docs} is a literal in {@code
 * DocsPaths} and no config key moves it, so — exactly like {@code GitHostTest}, {@code
 * RegistryTest}, {@code NpmRegistryTest}, {@code MavenRegistryTest} and {@code DaemonRegistryTest} —
 * this suite is the only thing that would notice it drifting.
 *
 * <p>Every case names its own version. The service module's suite has no table reset between tests
 * and versions here are immutable, so a shared coordinate would make these order-dependent in the
 * one way this registry is specifically designed to refuse. Content is salted per case for a second
 * reason the mirror suites document: nothing wipes the blob directory between runs, and blobs dedupe
 * globally, so reusing an earlier run's bytes turns a store write into a store hit.
 */
@QuarkusTest
class DocsRegistryTest {

  private static final AtomicInteger UNIQUE = new AtomicInteger();

  @Inject DocsFileRepository files;

  @TestHTTPResource("/")
  URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .header("Authorization", SuiteBearers.platform())
        .contentType("application/json")
        .body("{\"type\":\"docs\"}")
        .when()
        .put("/artifacts/api/repositories/docs")
        .then()
        .statusCode(200);
  }

  // --- the round trip ---------------------------------------------------------------------------

  @Test
  void aBundleIsPublishedAsOneRequestAndServedFileByFile() {
    String salt = salt();
    String site = "@qits/ui-" + salt;
    byte[] bundle = TinyBundle.storybookLike(salt).toTarGz();

    try (DocsClient client = client()) {
      HttpResponse<String> published = client.publish(site, "1.0.0", bundle);
      assertEquals(201, published.statusCode(), published.body());
      JsonObject receipt = new JsonObject(published.body());
      assertEquals(site, receipt.getString("name"));
      assertEquals(3, receipt.getInteger("fileCount"));
      assertNotNull(receipt.getString("publishedAt"));

      // Every file of the bundle is separately addressable, at the path it had in the archive.
      HttpResponse<byte[]> index = client.file(site, "1.0.0", "index.html");
      assertEquals(200, index.statusCode());
      assertTrue(new String(index.body(), StandardCharsets.UTF_8).contains(salt));

      HttpResponse<byte[]> nested =
          client.file(site, "1.0.0", "sb-common-assets/nunito-sans-bold.woff2");
      assertEquals(200, nested.statusCode());
      assertArrayEquals(TinyBundle.sharedFont(), nested.body());
    }
  }

  @Test
  void theDotSlashPrefixARealTarCarriesIsNormalisedAway() {
    // `tar -czf sb.tgz -C storybook-static .` names every entry ./index.html, and that is the
    // spelling the release pipeline will use. Stored verbatim it would make every URL /./index.html.
    String salt = salt();
    String site = "ui-" + salt;
    byte[] bundle =
        TinyBundle.storybookLike(salt).dotSlashPrefixed().withDirectoryEntries().toTarGz();

    try (DocsClient client = client()) {
      assertEquals(201, client.publish(site, "1.0.0", bundle).statusCode());
      assertEquals(200, client.file(site, "1.0.0", "index.html").statusCode());
      // The directory entries are skipped rather than stored: a row for one would be a path that
      // serves nothing, and it would inflate fileCount into a lie.
      assertEquals(3, new JsonObject(client.version(site, "1.0.0").body()).getInteger("fileCount"));
    }
  }

  @Test
  void aNestedSiteNameRoundTrips() {
    // The namespacing the /-/ separator exists for: a name that is neither an npm scope nor one
    // segment. If the grammar ever gets this wrong the failure is a 404 on publish.
    String salt = salt();
    String site = "someproject/somelib-" + salt;
    try (DocsClient client = client()) {
      assertEquals(
          201, client.publish(site, "2026.807.0", TinyBundle.storybookLike(salt).toTarGz()).statusCode());
      assertEquals(200, client.file(site, "2026.807.0", "index.html").statusCode());
    }
  }

  @Test
  void aChunkedPublishIsAcceptedToo() {
    // No Content-Length — what curl --upload-file sends, and the shape the global wire ceiling does
    // not gate. The route's own cap is the only bound, so this path has to work.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      assertEquals(
          201,
          client
              .publishStreaming(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz())
              .statusCode());
      assertEquals(200, client.file(site, "1.0.0", "index.html").statusCode());
    }
  }

  // --- what makes a version the unit -------------------------------------------------------------

  @Test
  void twoVersionsSharingAnAssetShareOneBlob() {
    // THE claim the exploded-into-blobs design is made on. The font is byte-identical across both
    // bundles, so the second publish must store no new bytes for it — asserted on the blob id rather
    // than on a size, because a size that happened to match would prove nothing.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      client.publish(
          site,
          "1.0.1",
          TinyBundle.storybookLike(salt)
              .file("index.html", "<!doctype html><title>changed " + salt + "</title>")
              .toTarGz());

      String fontOne = blobOf(site, "1.0.0", "sb-common-assets/nunito-sans-bold.woff2");
      String fontTwo = blobOf(site, "1.0.1", "sb-common-assets/nunito-sans-bold.woff2");
      assertEquals(fontOne, fontTwo, "an unchanged asset must dedupe across versions");

      // And the file that did change must not, or the dedupe would be hiding a stale read.
      assertNotEquals(blobOf(site, "1.0.0", "index.html"), blobOf(site, "1.0.1", "index.html"));
    }
  }

  @Test
  void aVersionIsImmutable() {
    String salt = salt();
    String site = "ui-" + salt;
    byte[] bundle = TinyBundle.storybookLike(salt).toTarGz();
    try (DocsClient client = client()) {
      assertEquals(201, client.publish(site, "1.0.0", bundle).statusCode());
      HttpResponse<String> again = client.publish(site, "1.0.0", bundle);
      // 409 even for identical bytes: a second publish at one version means the version was reused
      // or the release ran twice, and both are worth saying loudly.
      assertEquals(409, again.statusCode());
      assertTrue(again.body().contains("immutable"), again.body());
    }
  }

  @Test
  void anEmptyBundleIsRefusedRatherThanPublishedAsAnEmptySite() {
    String site = "ui-" + salt();
    try (DocsClient client = client()) {
      assertEquals(400, client.publish(site, "1.0.0", new byte[0]).statusCode());
      // A well-formed archive with nothing in it is the same answer: a version that 404s its own
      // index is a failed build being published, not a publishable thing.
      assertEquals(400, client.publish(site, "1.0.1", new TinyBundle().toTarGz()).statusCode());
    }
  }

  @Test
  void aTraversingEntryNameIsRefused() {
    // There is nowhere for it to land — bytes go to BlobStore under their own digest and the name is
    // only ever a database string — but a stored path that climbs would be concatenated into URLs by
    // qits-docs, so it is refused rather than merely harmless.
    String site = "ui-" + salt();
    try (DocsClient client = client()) {
      HttpResponse<String> escaped =
          client.publish(site, "1.0.0", TinyBundle.singleEntry("../escaped.html", "x"));
      assertEquals(400, escaped.statusCode());
      assertTrue(escaped.body().contains("traversing"), escaped.body());

      HttpResponse<String> absolute =
          client.publish(site, "1.0.1", TinyBundle.singleEntry("/etc/passwd", "x"));
      assertEquals(400, absolute.statusCode());
      assertTrue(absolute.body().contains("absolute"), absolute.body());
    }
  }

  @Test
  void somethingThatIsNotATarGzIsAFourHundred() {
    String site = "ui-" + salt();
    try (DocsClient client = client()) {
      HttpResponse<String> refused =
          client.publish(site, "1.0.0", "not an archive".getBytes(StandardCharsets.UTF_8));
      assertEquals(400, refused.statusCode());
      assertTrue(refused.body().contains("readable .tar.gz"), refused.body());
    }
  }

  // --- serving ----------------------------------------------------------------------------------

  @Test
  void aFileIsServedWithTheContentTypeItsExtensionImplies() {
    // Resolved from the extension, not sniffed: MediaTypeSniffer has no woff2 entry and would 400 on
    // exactly the files a static site is made of.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());

      assertEquals(
          "text/html; charset=utf-8", contentType(client.file(site, "1.0.0", "index.html")));
      assertEquals(
          "text/javascript; charset=utf-8",
          contentType(client.file(site, "1.0.0", "assets/iframe-" + salt + ".js")));
      assertEquals(
          "font/woff2",
          contentType(client.file(site, "1.0.0", "sb-common-assets/nunito-sans-bold.woff2")));
    }
  }

  @Test
  void aFileCarriesItsBlobDigestAsAnEtagAndAnImmutableCacheHeader() {
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      HttpResponse<byte[]> index = client.file(site, "1.0.0", "index.html");
      assertEquals(
          "\"" + blobOf(site, "1.0.0", "index.html") + "\"",
          index.headers().firstValue("etag").orElse(null));
      assertTrue(
          index.headers().firstValue("cache-control").orElse("").contains("immutable"),
          "a version-addressed URL never means something else");
    }
  }

  @Test
  void headAnswersTheSameLengthAsGet() {
    // Vert.x does not derive HEAD from GET, so every GET route needs its twin or a client that
    // probes before downloading sees a 404.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      HttpResponse<byte[]> body = client.file(site, "1.0.0", "index.html");
      HttpResponse<Void> head = client.headFile(site, "1.0.0", "index.html");
      assertEquals(200, head.statusCode());
      assertEquals(
          body.headers().firstValue("content-length"), head.headers().firstValue("content-length"));
    }
  }

  @Test
  void anUnknownFileIsAPlainTextFourHundredAndFourNeverTheSpasHtml() {
    // The consumer is assembling a website, so an HTML body under a missing asset is the one thing a
    // browser would render in place of the page asked for. Quinoa's catch-all is what would do it,
    // and quarkus.quinoa.ignored-path-prefixes naming /docs is what stops it — though under
    // @QuarkusTest Quinoa is disabled entirely, so PackagedProcessIT is where that half is proved.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      HttpResponse<byte[]> missing = client.file(site, "1.0.0", "nope.css");
      assertEquals(404, missing.statusCode());
      assertTrue(
          contentType(missing).startsWith("text/plain"),
          "a 404 here must not be HTML: " + contentType(missing));
    }
  }

  @Test
  void theVersionListIsWhatMakesADocsViewAbleToBeStateless() {
    // qits-docs resolves `latest` from this list rather than holding a pointer, so newest-first is a
    // contract rather than a convenience.
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "2026.806.1", TinyBundle.storybookLike(salt).toTarGz());
      client.publish(site, "2026.807.1", TinyBundle.storybookLike(salt + "b").toTarGz());

      JsonObject listed = new JsonObject(client.versions(site).body());
      assertEquals(site, listed.getString("name"));
      JsonArray versions = listed.getJsonArray("versions");
      assertEquals(2, versions.size());
      assertEquals("2026.807.1", versions.getJsonObject(0).getString("version"), "newest first");
      assertEquals("2026.806.1", versions.getJsonObject(1).getString("version"));
    }
  }

  @Test
  void anUnknownSiteAndAnUnknownVersionAreBothFourHundredAndFour() {
    try (DocsClient client = client()) {
      assertEquals(404, client.versions("ui-" + salt()).statusCode());
      assertEquals(404, client.version("ui-" + salt(), "1.0.0").statusCode());
    }
  }

  @Test
  void deleteIsNotAVerbAPublisherHolds() {
    // The store is append-only and retiring a version is the GC strategy's job. 405 rather than 404,
    // which would read as "unknown site".
    String salt = salt();
    String site = "ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      assertEquals(405, client.delete(site, "1.0.0").statusCode());
    }
  }

  @Test
  void theBaseItselfIsAPlainTextFourHundredAndFour() {
    try (DocsClient client = client()) {
      // The base names no repository, so there is nothing to list.
      assertEquals(404, client.getAbsolute("/artifacts/docs").statusCode());
    }
  }

  @Test
  void theRepositoryItselfIsTheCatalog() {
    // One site per name with its version count and its newest — flat and ungrouped, because a scope
    // lives in the name and deciding that @qits/ui-components belongs under @qits is a reading
    // choice this service does not get to make for every reader.
    String salt = salt();
    String site = "@qits/ui-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "2026.806.1", TinyBundle.storybookLike(salt).toTarGz());
      client.publish(site, "2026.807.1", TinyBundle.storybookLike(salt + "b").toTarGz());

      JsonArray listed =
          new JsonObject(client.getAbsolute("/artifacts/docs/docs").body()).getJsonArray("sites");
      JsonObject mine = null;
      for (int i = 0; i < listed.size(); i++) {
        if (site.equals(listed.getJsonObject(i).getString("name"))) {
          mine = listed.getJsonObject(i);
        }
      }
      assertNotNull(mine, "the catalog must list a site that has just been published");
      assertEquals(2, mine.getInteger("versionCount"));
      assertEquals("2026.807.1", mine.getString("latestVersion"), "newest, not first");
    }
  }

  @Test
  void anEmptyCatalogIsAnEmptyListAndNotAFourHundredAndFour() {
    // "Nothing is published yet" is a fact a catalog page has to be able to render. This suite has
    // published plenty, so the assertion is on the shape rather than on emptiness.
    try (DocsClient client = client()) {
      var response = client.getAbsolute("/artifacts/docs/docs");
      assertEquals(200, response.statusCode());
      assertNotNull(new JsonObject(response.body()).getJsonArray("sites"));
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  @Test
  void theVersionDocumentListsItsPathsAndTheVersionsListStaysPathFree() {
    String salt = salt();
    String site = "@userflows/paths-" + salt;
    try (DocsClient client = client()) {
      client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());

      JsonArray files = new JsonObject(client.version(site, "1.0.0").body()).getJsonArray("files");
      assertEquals(3, files.size(), "every path of the bundle, sorted");
      assertTrue(files.contains("index.html"), files.toString());

      JsonObject listed =
          new JsonObject(client.versions(site).body()).getJsonArray("versions").getJsonObject(0);
      assertTrue(listed.getJsonArray("files") == null, "the list stays path-free");
    }
  }

  // --- per-version metadata ---------------------------------------------------------------------

  @Test
  void publishedMetadataRidesTheReceiptAndTheVersionDocument() {
    String salt = salt();
    String site = "@userflows/meta-" + salt;
    java.util.Map<String, String> metadata =
        java.util.Map.of(
            "git.branch.name", "main",
            "git.commit.hash", "a".repeat(40),
            "git.repository.name", "qits-githost");

    try (DocsClient client = client()) {
      HttpResponse<String> published =
          client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz(), metadata);
      assertEquals(201, published.statusCode(), published.body());
      JsonObject receiptMeta = new JsonObject(published.body()).getJsonObject("metadata");
      assertEquals("main", receiptMeta.getString("git.branch.name"));

      JsonObject versionMeta =
          new JsonObject(client.version(site, "1.0.0").body()).getJsonObject("metadata");
      assertEquals("main", versionMeta.getString("git.branch.name"));
      assertEquals("a".repeat(40), versionMeta.getString("git.commit.hash"));
      assertEquals("qits-githost", versionMeta.getString("git.repository.name"));
    }
  }

  @Test
  void aVersionWithoutMetadataKeepsItsExactWireShape() {
    String salt = salt();
    String site = "@userflows/bare-" + salt;
    try (DocsClient client = client()) {
      HttpResponse<String> published =
          client.publish(site, "1.0.0", TinyBundle.storybookLike(salt).toTarGz());
      assertEquals(201, published.statusCode(), published.body());
      // No member at all, not an empty object: older readers never see a key they don't know.
      assertTrue(new JsonObject(published.body()).getJsonObject("metadata") == null);
      assertTrue(
          new JsonObject(client.version(site, "1.0.0").body()).getJsonObject("metadata") == null);
    }
  }

  @Test
  void serverOwnedKeysAreDroppedSilentlyAndTheCapsRefuseLoudly() {
    String salt = salt();
    String site = "@userflows/caps-" + salt;
    try (DocsClient client = client()) {
      HttpResponse<String> published =
          client.publish(
              site,
              "1.0.0",
              TinyBundle.storybookLike(salt).toTarGz(),
              java.util.Map.of("mediatype", "text/evil", "created-at", "1970-01-01T00:00:00Z"));
      assertEquals(201, published.statusCode(), published.body());
      assertTrue(new JsonObject(published.body()).getJsonObject("metadata") == null);

      java.util.Map<String, String> tooMany = new java.util.LinkedHashMap<>();
      for (int i = 0; i <= 32; i++) {
        tooMany.put("key-" + i, "v");
      }
      assertEquals(
          400,
          client.publish(site, "1.0.1", TinyBundle.storybookLike(salt + "b").toTarGz(), tooMany)
              .statusCode());
      assertEquals(
          400,
          client
              .publish(
                  site,
                  "1.0.2",
                  TinyBundle.storybookLike(salt + "c").toTarGz(),
                  java.util.Map.of("k", "v".repeat(4001)))
              .statusCode());
    }
  }

  @Test
  void theVersionsListFiltersByMetadataAndAnEmptyMatchIsAnAnswer() {
    String salt = salt();
    String site = "@userflows/branches-" + salt;
    try (DocsClient client = client()) {
      client.publish(
          site,
          "a".repeat(40),
          TinyBundle.storybookLike(salt + "1").toTarGz(),
          java.util.Map.of("git.branch.name", "main"));
      client.publish(
          site,
          "b".repeat(40),
          TinyBundle.storybookLike(salt + "2").toTarGz(),
          java.util.Map.of("git.branch.name", "feature/x"));
      client.publish(
          site,
          "c".repeat(40),
          TinyBundle.storybookLike(salt + "3").toTarGz(),
          java.util.Map.of("git.branch.name", "main"));

      // Filtered to one branch, newest first — the first element IS that branch's latest.
      JsonArray main =
          new JsonObject(client.versions(site, "meta.git.branch.name=main").body())
              .getJsonArray("versions");
      assertEquals(2, main.size());
      assertEquals("c".repeat(40), main.getJsonObject(0).getString("version"));
      assertEquals("a".repeat(40), main.getJsonObject(1).getString("version"));

      // A known site filtered to nothing is an ANSWER, not an error.
      HttpResponse<String> none = client.versions(site, "meta.git.branch.name=gone");
      assertEquals(200, none.statusCode(), none.body());
      assertEquals(0, new JsonObject(none.body()).getJsonArray("versions").size());

      // An unknown site stays a 404, filter or no filter.
      assertEquals(
          404, client.versions("@userflows/no-such-" + salt, "meta.git.branch.name=main")
              .statusCode());
    }
  }

  @Test
  void aRepublishWithDifferentMetadataIsRefusedAndTheStoredMetadataStands() {
    String salt = salt();
    String site = "@userflows/immutable-" + salt;
    try (DocsClient client = client()) {
      client.publish(
          site,
          "1.0.0",
          TinyBundle.storybookLike(salt).toTarGz(),
          java.util.Map.of("git.branch.name", "main"));
      HttpResponse<String> republished =
          client.publish(
              site,
              "1.0.0",
              TinyBundle.storybookLike(salt).toTarGz(),
              java.util.Map.of("git.branch.name", "rewritten"));
      assertEquals(409, republished.statusCode());
      assertEquals(
          "main",
          new JsonObject(client.version(site, "1.0.0").body())
              .getJsonObject("metadata")
              .getString("git.branch.name"));
    }
  }

  private DocsClient client() {
    return new DocsClient(URI.create(root.toString()));
  }

  private static String salt() {
    return "t" + UNIQUE.incrementAndGet() + "x" + System.nanoTime();
  }

  private static String contentType(HttpResponse<?> response) {
    return response.headers().firstValue("content-type").orElse("");
  }

  private String blobOf(String site, String version, String path) {
    return files
        .findOne("docs", site, version, path)
        .orElseThrow(() -> new AssertionError("no row for " + path + " in " + site + "@" + version))
        .blobId;
  }
}
