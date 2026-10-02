package eu.wohlben.qits.artifacts.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.testdb.EmbeddedPg;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The lineage itself, run from empty — the one thing a {@code @QuarkusTest} cannot show.
 *
 * <p>Every suite in this module starts by wiping the tables, so what the chain <em>leaves</em> there
 * is invisible to all of them: a row would be gone before the first assertion either way, so a
 * prefill that came back — or one that never went — would look exactly like a passing build. This
 * test owns a database of its own on the embedded postgres, runs Flyway over the real migration
 * directory, and reads what is there.
 *
 * <p>It is also where V1's four shape decisions are pinned, because a fresh baseline is the one
 * place they could quietly be undone: no git-host tables, the four cache tables present and empty,
 * no mirror prefill, and a type check listing the types this service registers — eight since V3
 * added {@code SBOMS} — rather than the ten the retired H2 chain accepted. See the header of {@code
 * db/artifacts/postgresql/V1__init.sql} for the argument behind each.
 *
 * <p><b>Real postgres, not H2.</b> The lineage is a PostgreSQL lineage now — {@code timestamptz},
 * {@code bytea}, a partial index and a regex check — so running it anywhere else would prove
 * something about a schema this service never has.
 */
class MigrationLineageTest {

  /** Its own database on the shared instance, so no {@code @QuarkusTest} here can see this schema. */
  private static final String DATABASE = "artifacts_lineage_test";

  private PGSimpleDataSource dataSource;

  /**
   * A clean database per test, by {@code clean} then {@code migrate} rather than a new database each
   * time: dropping a postgres database needs every connection to it closed, and the pool behind the
   * suite around this one is not this class's to manage. The result is the same — every method sees
   * exactly what the chain builds from empty.
   */
  @BeforeEach
  void migrate() {
    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(EmbeddedPg.url(DATABASE));
    dataSource.setUser(EmbeddedPg.USER);
    dataSource.setPassword(EmbeddedPg.PASSWORD);
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/artifacts/postgresql")
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/artifacts/postgresql")
        .load()
        .migrate();
  }

  /**
   * The eight keys the lineage declares, spelled out.
   *
   * <p>It used to be {@code RepositoryType.values()}, which made "the constraint lists every type" a
   * property rather than a list. There is no enum any more — types are registered as profile beans —
   * and a list read from the CLASSPATH would prove the wrong thing: the constraint is a fact about
   * the DATABASE.
   *
   * <p><b>Eight, not the H2 chain's ten.</b> That chain was carried through the byte-plane split
   * byte for byte and so kept accepting {@code NPM_PROXY}, {@code MAVEN_PROXY} and {@code
   * OCI_MIRROR} long after nothing here could serve one. A fresh baseline is where the set the code
   * enforces and the set the database accepts become one set. V1 listed seven; V3 re-enumerated the
   * whole check — dropped by name, re-added naming every key — to add {@code SBOMS}, which is the
   * widening rule this list exists to keep honest.
   */
  private static final List<String> DECLARED_TYPES =
      List.of(
          "CI_SCREENSHOTS",
          "CI_VIDEOS",
          "OCI_IMAGES",
          "NPM_PACKAGES",
          "MAVEN_PACKAGES",
          "DAEMON_BINARIES",
          "DOCS",
          "SBOMS");

  /** The three cache keys V1 deliberately stopped accepting. */
  private static final List<String> RETIRED_CACHE_TYPES =
      List.of("NPM_PROXY", "MAVEN_PROXY", "OCI_MIRROR");

  @Test
  void theTypeCheckAcceptsEveryKeyTheLineageDeclares() throws SQLException {
    for (String type : DECLARED_TYPES) {
      insertRepository("probe-" + type.toLowerCase(Locale.ROOT), type);
    }
    assertEquals(
        DECLARED_TYPES.size(),
        count("select count(*) from artifact_repository where name like 'probe-%'"));
  }

  @Test
  void aTypeTheLineageDoesNotDeclareIsRefusedByTheConstraint() {
    // The other half: the constraint is a constraint, not documentation. Without it a typo in a
    // deployment's provisioning script would mint a repository of a type no code can read.
    SQLException refused =
        assertThrows(SQLException.class, () -> insertRepository("maven-someday", "MAVEN_ARTIFACTS"));
    assertTrue(
        refused.getMessage().toUpperCase(Locale.ROOT).contains("CK_ARTIFACT_REPOSITORY_TYPE"),
        refused.getMessage());
  }

  @Test
  void theThreeCacheTypesAreRefusedTooBecauseNothingHereServesThem() {
    // Shape decision 4, as the assertion that would fail if somebody "restored" the H2 chain's list.
    // The caches went to qits-platform-mirror; a repository row of one of these types here is dead
    // data of exactly the kind the retired chain's last migration was written to remove.
    for (String type : RETIRED_CACHE_TYPES) {
      SQLException refused =
          assertThrows(
              SQLException.class,
              () -> insertRepository("cache-" + type.toLowerCase(Locale.ROOT), type),
              type);
      assertTrue(
          refused.getMessage().toUpperCase(Locale.ROOT).contains("CK_ARTIFACT_REPOSITORY_TYPE"),
          refused.getMessage());
    }
  }

  @Test
  void theGitHostsThreeTablesAreNotCreatedAtAll() throws SQLException {
    // Shape decision 1. `git_pack`, `git_pack_file` and `git_repository_protection` existed in the
    // H2 chain only because applied history cannot be rewritten — qits-githost owns that data in a
    // database of its own, and no code in this tree reads or writes them. A fresh baseline is the
    // one place they could go, and this is what stops them coming back by copy-paste.
    for (String table : List.of("git_pack", "git_pack_file", "git_repository_protection")) {
      assertEquals(0, tableCount(table), table + " belongs to qits-githost, not to this schema");
    }
  }

  @Test
  void theFourCacheTablesExistAndAreEmpty() throws SQLException {
    // Shape decision 2, and the half that looks like an oversight until it 500s. The cache
    // REPOSITORIES are gone, but their repositories-in-the-DAO-sense are live beans that ride in on
    // the qits-registries jars — excluding a profile does not unregister a DAO.
    // `OciRegistryService.resolveForPull` reads the upstream table on every pull whose first segment
    // names no repository, and `collectTag` deletes a freshness row for HOSTED tags too. A missing
    // table turns an unknown-image pull into a 500 where a client needs a 404 NAME_UNKNOWN.
    for (String table :
        List.of(
            "oci_mirror_upstream",
            "oci_mirror_tag_check",
            "npm_proxy_packument",
            "maven_proxy_metadata")) {
      assertEquals(1, tableCount(table), table + " is still read by a live bean on this classpath");
      assertEquals(0, count("select count(*) from " + table), table + " ships empty");
    }
  }

  @Test
  void thereIsNoMirrorPrefillAndNoUpstreamRow() throws SQLException {
    // Shape decision 3. The retired chain's V7 prefilled `hub`, `quay` and `redhat` and their
    // upstreams, and its V14 took them out again — because a standing row is what kept the mirror
    // path reachable here after the code left. A fresh baseline never writes them. This is the
    // assertion that has to be read from a migrated database rather than from a suite: every
    // @QuarkusTest here wipes the tables, so a prefill that came back would be invisible to all of
    // them.
    Map<String, String> slugsByDomain = new LinkedHashMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("select domain, slug from oci_mirror_upstream order by slug")) {
      while (rows.next()) {
        slugsByDomain.put(rows.getString(1), rows.getString(2));
      }
    }
    assertEquals(Map.of(), slugsByDomain, "the upstream rows went with the code that read them");
    assertEquals(0, count("select count(*) from artifact_repository"), "nothing is prefilled");
  }

  @Test
  void theBlobTablesAreHereBecauseTheStoreIsInThisDatabaseNow() throws SQLException {
    // The blob store's three tables, copied verbatim from qits-blobstore's own
    // db/blobstore-tables.sql into V1. They are the half of this schema that used to be a directory,
    // so a lineage that built every metadata table and none of these would leave a service that
    // boots and then cannot store a byte.
    for (String table : List.of("blob", "blob_content", "blob_chunk")) {
      assertEquals(1, tableCount(table), table);
      assertEquals(0, count("select count(*) from " + table), table + " starts empty");
    }

    // The content address is checked at the table as well as in code — the store's path-traversal
    // defence, restated where nothing can route around it.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into blob_content (content_id, state, started_at) values"
              + " ('00000000-0000-0000-0000-000000000001', 'PROMOTED', now())");
    }
    SQLException refused =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into blob (id, content_id, size_bytes, chunk_size, stored_at) values"
                        + " ('../etc/passwd', '00000000-0000-0000-0000-000000000001', 1, 1, now())"));
    assertTrue(refused.getMessage().toUpperCase(Locale.ROOT).contains("BLOB"), refused.getMessage());
  }

  @Test
  void theMavenTableIsThereKeyedByRepositoryAndPath() throws SQLException {
    // One table, exercised the way the lineage pins everything else: insert a repository and a
    // deployed file under it, and prove the foreign key does its half of the job.
    insertRepository("maven", "MAVEN_PACKAGES");
    execute(
        "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at) values"
            + " ('maven', 'eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar',"
            + " '" + "0".repeat(64) + "', 47940, current_timestamp)");
    assertEquals(1, count("select count(*) from maven_artifact where repository = 'maven'"));

    SQLException refused =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at)"
                        + " values ('no-such-repo', 'x/y.jar', '" + "1".repeat(64)
                        + "', 1, current_timestamp)"));
    assertTrue(
        refused.getMessage().toUpperCase(Locale.ROOT).contains("FK_MAVEN_ARTIFACT_REPOSITORY"),
        refused.getMessage());
  }

  @Test
  void theDaemonTableIsThereKeyedByRepositoryNameAndVersion() throws SQLException {
    insertRepository("daemons", "DAEMON_BINARIES");
    execute(
        "insert into daemon_binary (repository, name, version, blob_id, size_bytes, published_at)"
            + " values ('daemons', 'qits-ci-daemon', '2026.801.120000',"
            + " '" + "c".repeat(64) + "', 43123792, current_timestamp)");
    assertEquals(1, count("select count(*) from daemon_binary where repository = 'daemons'"));

    // The uniqueness that makes a republish answerable at all: without it there is no "409, this
    // version exists", which is the whole reason this type beat an artifact_record row.
    SQLException duplicate =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into daemon_binary (repository, name, version, blob_id, size_bytes,"
                        + " published_at) values ('daemons', 'qits-ci-daemon', '2026.801.120000',"
                        + " '" + "d".repeat(64) + "', 1, current_timestamp)"));
    assertTrue(
        duplicate.getMessage().toUpperCase(Locale.ROOT).contains("DAEMON_BINARY_PKEY"),
        duplicate.getMessage());

    SQLException refused =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into daemon_binary (repository, name, version, blob_id, size_bytes,"
                        + " published_at) values ('no-such-repo', 'x', '1', '" + "1".repeat(64)
                        + "', 1, current_timestamp)"));
    assertTrue(
        refused.getMessage().toUpperCase(Locale.ROOT).contains("FK_DAEMON_BINARY_REPOSITORY"),
        refused.getMessage());
  }

  @Test
  void theSbomTableIsThereKeyedByTheReleaseIdentity() throws SQLException {
    // V3's table, exercised the way the daemon's is: the primary key IS the SoftwareRelease
    // identity, so a second document for the same (packageType, packageName, version) is refused
    // rather than shadowing the first — which is what makes "first write wins" answerable at all.
    insertRepository("sboms", "SBOMS");
    execute(
        "insert into sbom_document (repository, package_type, package_name, version, blob_id,"
            + " size_bytes, spec_version, created_at) values ('sboms', 'maven',"
            + " 'eu.wohlben.qits:qits-eventstream', '2026.801.30', '" + "a".repeat(64)
            + "', 4096, '1.5', current_timestamp)");
    assertEquals(1, count("select count(*) from sbom_document where repository = 'sboms'"));

    SQLException duplicate =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into sbom_document (repository, package_type, package_name, version,"
                        + " blob_id, size_bytes, spec_version, created_at) values ('sboms', 'maven',"
                        + " 'eu.wohlben.qits:qits-eventstream', '2026.801.30', '" + "b".repeat(64)
                        + "', 1, '1.5', current_timestamp)"));
    assertTrue(
        duplicate.getMessage().toUpperCase(Locale.ROOT).contains("SBOM_DOCUMENT_PKEY"),
        duplicate.getMessage());

    SQLException refused =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into sbom_document (repository, package_type, package_name, version,"
                        + " blob_id, size_bytes, spec_version, created_at) values ('no-such-repo',"
                        + " 'npm', 'x', '1', '" + "c".repeat(64)
                        + "', 1, '1.5', current_timestamp)"));
    assertTrue(
        refused.getMessage().toUpperCase(Locale.ROOT).contains("FK_SBOM_DOCUMENT_REPOSITORY"),
        refused.getMessage());

    // The four declared package types, enforced one layer under the route's own 400: a document
    // filed under a type no consumer of SoftwareRelease can ask for is unreachable data.
    SQLException wrongType =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into sbom_document (repository, package_type, package_name, version,"
                        + " blob_id, size_bytes, spec_version, created_at) values ('sboms', 'gem',"
                        + " 'rails', '7.0.0', '" + "d".repeat(64)
                        + "', 1, '1.5', current_timestamp)"));
    assertTrue(
        wrongType.getMessage().toUpperCase(Locale.ROOT).contains("CK_SBOM_DOCUMENT_PACKAGE_TYPE"),
        wrongType.getMessage());
  }

  @Test
  void theContentHashTableIsThereKeyedByTheVersionAndOwnedByARepository() throws SQLException {
    // V4's table (epic qits-620). The primary key is the version, so a second value for the same
    // version is refused at the database as well as by the ledger's first-write-wins rule; the
    // ecosystem is one of the two the ledger records; and the row belongs to a repository.
    insertRepository("maven", "MAVEN_PACKAGES");
    execute(
        "insert into content_hash (repository, ecosystem, package_name, version, value, created_at)"
            + " values ('maven', 'maven', 'eu.wohlben.qits:qits-foo', '2026.1001.1', 'v1:sha256:"
            + "a".repeat(64) + "', current_timestamp)");
    assertEquals(1, count("select count(*) from content_hash where repository = 'maven'"));

    SQLException duplicate =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into content_hash (repository, ecosystem, package_name, version, value,"
                        + " created_at) values ('maven', 'maven', 'eu.wohlben.qits:qits-foo',"
                        + " '2026.1001.1', 'v1:sha256:" + "b".repeat(64) + "', current_timestamp)"));
    assertTrue(
        duplicate.getMessage().toUpperCase(Locale.ROOT).contains("CONTENT_HASH_PKEY"),
        duplicate.getMessage());

    SQLException wrongEcosystem =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into content_hash (repository, ecosystem, package_name, version, value,"
                        + " created_at) values ('maven', 'docker', 'qits/qits-foo', '1',"
                        + " 'v1:sha256:aa', current_timestamp)"));
    assertTrue(
        wrongEcosystem.getMessage().toUpperCase(Locale.ROOT).contains("CK_CONTENT_HASH_ECOSYSTEM"),
        wrongEcosystem.getMessage());

    SQLException orphan =
        assertThrows(
            SQLException.class,
            () ->
                execute(
                    "insert into content_hash (repository, ecosystem, package_name, version, value,"
                        + " created_at) values ('no-such-repo', 'npm', '@qits/foo', '1.0.0',"
                        + " 'v1:sha256:aa', current_timestamp)"));
    assertTrue(
        orphan.getMessage().toUpperCase(Locale.ROOT).contains("FK_CONTENT_HASH_REPOSITORY"),
        orphan.getMessage());
  }

  @Test
  void aDocsVersionIsTheUnitOfEvictionBecauseItsFilesCascade() throws SQLException {
    // The one foreign key in this schema that is load-bearing rather than hygienic: deleting a
    // docs_site row must take its files with it, or a sweep could leave a version that lists itself
    // and 404s its own stylesheet.
    insertRepository("docs", "DOCS");
    execute(
        "insert into docs_site (repository, name, version, file_count, total_bytes, published_at)"
            + " values ('docs', '@qits/ui-components', '1.0.0', 2, 300, current_timestamp)");
    execute(
        "insert into docs_file (repository, name, version, path, blob_id, size_bytes, media_type)"
            + " values ('docs', '@qits/ui-components', '1.0.0', 'index.html', '" + "a".repeat(64)
            + "', 100, 'text/html')");
    execute(
        "insert into docs_file (repository, name, version, path, blob_id, size_bytes, media_type)"
            + " values ('docs', '@qits/ui-components', '1.0.0', 'main.css', '" + "b".repeat(64)
            + "', 200, 'text/css')");
    assertEquals(2, count("select count(*) from docs_file"));

    execute("delete from docs_site");
    assertEquals(0, count("select count(*) from docs_file"), "the cascade is the eviction unit");
  }

  @Test
  void docsVersionMetadataCascadesWithItsVersionBecauseV2SaysSo() throws SQLException {
    // V2's one table, and its one property: metadata is a fact ABOUT a version, keyed by the same
    // three columns, and fk_docs_site_metadata_site's cascade means a collected version cannot
    // leave orphaned metadata describing a bundle that no longer lists itself.
    insertRepository("docs", "DOCS");
    execute(
        "insert into docs_site (repository, name, version, file_count, total_bytes, published_at)"
            + " values ('docs', '@userflows/qits-githost', '" + "c".repeat(40)
            + "', 1, 100, current_timestamp)");
    execute(
        "insert into docs_site_metadata (repository, name, version, meta_key, meta_value)"
            + " values ('docs', '@userflows/qits-githost', '" + "c".repeat(40)
            + "', 'git.branch.name', 'main')");
    assertEquals(1, count("select count(*) from docs_site_metadata"));

    execute("delete from docs_site");
    assertEquals(
        0,
        count("select count(*) from docs_site_metadata"),
        "fk_docs_site_metadata_site must cascade with the version");
  }

  @Test
  void theProtocolTablesCarryANullableAccessedAtWithNoBackfill() throws SQLException {
    // The inserts name no accessed_at, so a NOT NULL column or a default would fail here — and null
    // is the state a sweep has to be able to read as "never accessed", which stamping
    // created_at/published_at into the column would have destroyed silently.
    insertRepository("npm", "NPM_PACKAGES");
    insertRepository("maven", "MAVEN_PACKAGES");
    insertRepository("daemons", "DAEMON_BINARIES");
    insertRepository("sboms", "SBOMS");
    execute(
        "insert into npm_version (repository, package_name, version, tarball_blob_id,"
            + " manifest_json, created_at) values ('npm', '@qits/ui', '1.0.0', '"
            + "a".repeat(64) + "', '{}', current_timestamp)");
    execute(
        "insert into maven_artifact (repository, path, blob_id, size_bytes, created_at) values"
            + " ('maven', 'eu/wohlben/qits/lib/1.0.0/lib-1.0.0.jar', '" + "b".repeat(64)
            + "', 1, current_timestamp)");
    execute(
        "insert into daemon_binary (repository, name, version, blob_id, size_bytes, published_at)"
            + " values ('daemons', 'qits-ci-daemon', '2026.801.120000', '" + "c".repeat(64)
            + "', 1, current_timestamp)");
    execute(
        "insert into sbom_document (repository, package_type, package_name, version, blob_id,"
            + " size_bytes, spec_version, created_at) values ('sboms', 'npm', '@qits/ui',"
            + " '2026.801.30', '" + "d".repeat(64) + "', 1, '1.5', current_timestamp)");
    assertEquals(1, count("select count(*) from npm_version where accessed_at is null"));
    assertEquals(1, count("select count(*) from maven_artifact where accessed_at is null"));
    assertEquals(1, count("select count(*) from daemon_binary where accessed_at is null"));
    assertEquals(1, count("select count(*) from sbom_document where accessed_at is null"));
  }

  @Test
  void theLineageEmbedsNoLivePlatformDigest() throws SQLException {
    // Adopting the ELF blobs already on a live volume is an OPS action, never a migration. A
    // migration cannot verify a digest against the running store, and a lineage carrying one would
    // replay it onto every fresh platform that has no such bytes.
    assertEquals(0, count("select count(*) from daemon_binary"));
    assertEquals(0, count("select count(*) from blob"));
  }

  private void insertRepository(String name, String type) throws SQLException {
    execute(
        "insert into artifact_repository (name, type, created_at) values ('"
            + name
            + "', '"
            + type
            + "', current_timestamp)");
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private long count(String query) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** 1 if the table is in this database's public schema, 0 if it is not. */
  private long tableCount(String table) throws SQLException {
    return count(
        "select count(*) from information_schema.tables where table_schema = 'public'"
            + " and table_name = '" + table + "'");
  }
}
