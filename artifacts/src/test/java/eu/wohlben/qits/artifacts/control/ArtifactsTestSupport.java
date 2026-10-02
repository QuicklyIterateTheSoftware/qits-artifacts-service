package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.control.BlobDiskIndex;
import eu.wohlben.qits.blobstore.persistence.ArtifactRecordRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.DocsFileRepository;
import eu.wohlben.qits.artifacts.persistence.DocsSiteRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmProxyPackumentRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionTombstoneRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciMirrorUpstreamRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/** Wipes the blobs and every table before each test so every case starts empty. */
abstract class ArtifactsTestSupport {

  @Inject ArtifactRecordRepository records;

  @Inject ArtifactRepositoryRepository repositories;

  @Inject OciManifestRepository ociManifests;

  @Inject OciTagRepository ociTags;

  @Inject NpmVersionRepository npmVersions;

  @Inject NpmDistTagRepository npmDistTags;

  @Inject NpmVersionTombstoneRepository npmVersionTombstones;

  @Inject NpmProxyPackumentRepository npmProxyPackuments;

  @Inject MavenArtifactRepository mavenArtifacts;

  @Inject DaemonBinaryRepository daemonBinaries;

  @Inject DocsSiteRepository docsSites;

  @Inject DocsFileRepository docsFiles;

  @Inject SbomDocumentRepository sbomDocuments;
  @Inject eu.wohlben.qits.artifacts.persistence.ContentHashRepository contentHashes;

  @Inject OciMirrorUpstreamRepository mirrorUpstreams;

  @Inject BlobDiskIndex diskIndex;

  /**
   * The blob tables live in the same database as everything else, so this is the SAME datasource the
   * Panache repositories above use. Reached as JDBC rather than through an entity because the blob
   * store has none — it speaks plain SQL, and so does anything that has to wipe or age its rows.
   */
  @Inject
  @DataSource("artifacts")
  AgroalDataSource blobs;

  @BeforeEach
  void reset() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // The protocol tables first: every one of them carries a foreign key to
              // artifact_repository.
              ociTags.deleteAll();
              ociManifests.deleteAll();
              npmDistTags.deleteAll();
              npmVersions.deleteAll();
              npmVersionTombstones.deleteAll();
              npmProxyPackuments.deleteAll();
              mavenArtifacts.deleteAll();
              daemonBinaries.deleteAll();
              // Only the site rows: docs_file and docs_site_metadata carry `on delete cascade` to
              // this table (V1 and V2), which is where the "a version is the unit of eviction" rule
              // is enforced — deleting the files here would be a second way to unmake a site, and
              // the schema exists so there is only one.
              docsSites.deleteAll();
              // The SBOM rows have no cascade to ride, unlike docs': fk_sbom_document_repository
              // points straight at artifact_repository, so they go before the repositories do.
              sbomDocuments.deleteAll();
              // content_hash too: fk_content_hash_repository points at artifact_repository.
              contentHashes.deleteAll();
              records.deleteAll();
              // The mirror upstreams too: their slug is a foreign key into artifact_repository, so
              // the pairing that makes a namespace resolvable is also what makes the wipe ordered.
              mirrorUpstreams.deleteAll();
              repositories.deleteAll();
            });
    // blob first, then blob_content: the identity row is what points at the content, and removing
    // the content cascades to every chunk. Neither is tied to the entity tables by a foreign key —
    // blobs address the world by string metadata — so the order inside the pair is the whole rule.
    execute("delete from blob");
    execute("delete from blob_content");
  }

  /**
   * Ages a stored blob past the sweep's grace window.
   *
   * <p>The window is measured from {@code stored_at}, and a test's blobs are always seconds old — so
   * without this every GC case would assert on what was withheld rather than on the reconciliation.
   * Backdating is the honest way round: it exercises the same clock comparison production runs,
   * instead of configuring the window away.
   */
  void backdate(String blobId, Duration age) {
    update(
        "update blob set stored_at = ? where id = ?",
        statement -> {
          statement.setObject(1, Instant.now().minus(age).atOffset(ZoneOffset.UTC));
          statement.setString(2, blobId);
        });
  }

  /** How many staging areas exist — the assertion that replaces counting temp files. */
  long stagingCount() {
    return count("select count(*) from blob_content where state = 'STAGING'");
  }

  long count(String sql) {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  void execute(String sql) {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  /** Fills in a prepared statement's parameters, the way JDBC makes you. */
  interface Binding {
    void bind(PreparedStatement statement) throws SQLException;
  }

  void update(String sql, Binding binding) {
    try (Connection connection = blobs.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binding.bind(statement);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  /** The full required-key set for a ci-screenshots upload of the given dimensions. */
  static Map<String, String> screenshotMeta(String branch, String flow, int width, int height) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "step 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.width", Integer.toString(width));
    m.put("media.resolution.height", Integer.toString(height));
    return m;
  }

  /** The full required-key set for a ci-videos upload. */
  static Map<String, String> videoMeta(String branch, String flow) {
    Map<String, String> m = new HashMap<>();
    m.put("git.branch.name", branch);
    m.put("git.commit.hash", "abc123");
    m.put("qits.userflow.name", flow);
    m.put("qits.userflow.hash", "flowhash");
    m.put("qits.display.name", "clip 1");
    m.put("qits.diff.hash", "diffhash");
    m.put("media.resolution.length", "12");
    return m;
  }
}
