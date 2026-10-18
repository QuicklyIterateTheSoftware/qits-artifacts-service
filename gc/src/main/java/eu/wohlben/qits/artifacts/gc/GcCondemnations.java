package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcCondemnedIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcCondemnedListing;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The collector's condemnations: which dead identities a sweep has marked, when, and by which run
 * (qits-1172, table {@code gc_condemned}).
 *
 * <h2>Why a sweep condemns instead of deleting</h2>
 *
 * <p>With every own type at {@code P0D}, retention IS the keep-set, and the only clock left was the
 * blob grace — six hours measured from when the <b>bytes</b> were written. So a version published a
 * week ago that one run's keep-set happened to leave out (a pin source answering an incomplete list,
 * a manifest scan that missed a repository) was deleted on that run, row and blob, with nothing to
 * restore it from. The abort rule catches a source that cannot answer; nothing caught a source that
 * answered <em>wrong</em>.
 *
 * <p>So a dead identity is condemned first: one row here, nothing else touched, every read answering
 * as before. A later run purges it — today's delete path, unchanged — only once the condemnation is
 * older than {@code qits.artifacts.gc.condemn-grace-period} and that run's keep-set still leaves it
 * dead. A wrong answer has to persist for a whole grace, through every run inside it, to cost
 * anything; a right answer arriving at any point in between lifts the condemnation by itself.
 *
 * <h2>The two clocks are different questions</h2>
 *
 * <p>The blob grace is race safety for bytes that landed before their pin; it still gates the purge
 * and is not replaced. The condemn grace is the time a <em>mistake</em> stays reversible, measured
 * from when the collector first decided — which is the clock the blob grace could never be, because
 * an old artifact's bytes are always old.
 *
 * <p><b>Plain JDBC on the shared datasource, and no entity.</b> The persistence unit's packages are
 * the {@code artifacts} jar's to name, and that jar does not know a collector exists; a mapped entity
 * here would mean it naming a gc package. The table is the lineage's (V5) because there is one
 * lineage per datasource; the code that reads it is only this class.
 *
 * <p>Every method is one statement or one short batch on its own connection, in no transaction of
 * its own: a sweep that dies half-way through condemning leaves some condemnations written and some
 * not, and the next run reconciles exactly that — it condemns what is missing and clears what is
 * stale. A condemnation is a delay, never a deletion, so a partial write of one cannot lose anything.
 */
@ApplicationScoped
public class GcCondemnations {

  @Inject
  @DataSource("artifacts")
  AgroalDataSource database;

  /** How long a condemnation must stand before a sweep may purge the identity. */
  @ConfigProperty(name = "qits.artifacts.gc.condemn-grace-period")
  Duration gracePeriod;

  /** One stored condemnation, in the stored type key. */
  public record Condemnation(
      String type, String repository, String identity, Instant condemnedAt, String runId) {

    public GcStrategy.IdentityKey key() {
      return new GcStrategy.IdentityKey(repository, identity);
    }

    /** The wire form, with the purge instant this deployment's grace gives it. */
    public GcCondemnedIdentity toWire(Duration grace) {
      return new GcCondemnedIdentity(
          RepositoryTypeProfile.wireNameOf(type),
          repository,
          identity,
          condemnedAt,
          runId,
          condemnedAt.plus(grace));
    }
  }

  public Duration gracePeriod() {
    return gracePeriod;
  }

  /**
   * One type's standing condemnations, optionally within one repository.
   *
   * @param repository null for the whole type; a name for a scoped run, which must never see — and
   *     therefore never clear — another repository's condemnations
   */
  public Map<GcStrategy.IdentityKey, Condemnation> of(String type, String repository) {
    String sql =
        "select type, repository, identity, condemned_at, run_id from gc_condemned where type = ?"
            + (repository == null ? "" : " and repository = ?")
            + " order by repository, identity";
    Map<GcStrategy.IdentityKey, Condemnation> standing = new LinkedHashMap<>();
    query(
        sql,
        statement -> {
          statement.setString(1, type);
          if (repository != null) {
            statement.setString(2, repository);
          }
        },
        row -> standing.put(row.key(), row));
    return standing;
  }

  /** {@code GET /gc/condemned}'s answer: every standing condemnation, with its purge instant. */
  public GcCondemnedListing listing() {
    return new GcCondemnedListing(
        GcPlanner.iso(gracePeriod),
        all().stream().map(condemnation -> condemnation.toWire(gracePeriod)).toList());
  }

  /** Every standing condemnation, every type, ordered by type, repository and identity. */
  public List<Condemnation> all() {
    List<Condemnation> all = new ArrayList<>();
    query(
        "select type, repository, identity, condemned_at, run_id from gc_condemned"
            + " order by type, repository, identity",
        statement -> {},
        all::add);
    return all;
  }

  /**
   * Condemns identities that carry no condemnation yet. An identity already condemned keeps its
   * original instant — re-stamping it on every run would mean nothing is ever purged.
   */
  public void condemn(
      String type, Collection<GcStrategy.IdentityKey> keys, Instant at, String runId) {
    batch(
        "insert into gc_condemned (type, repository, identity, condemned_at, run_id)"
            + " values (?, ?, ?, ?, ?) on conflict do nothing",
        keys,
        (statement, key) -> {
          statement.setString(1, type);
          statement.setString(2, key.repository());
          statement.setString(3, key.identity());
          statement.setObject(4, at.atOffset(ZoneOffset.UTC));
          statement.setString(5, runId);
        });
  }

  /** Lifts condemnations — the keep-set named them again, or the purge took them. */
  public void clear(String type, Collection<GcStrategy.IdentityKey> keys) {
    batch(
        "delete from gc_condemned where type = ? and repository = ? and identity = ?",
        keys,
        (statement, key) -> {
          statement.setString(1, type);
          statement.setString(2, key.repository());
          statement.setString(3, key.identity());
        });
  }

  /**
   * The restore door's half: removes and returns every condemnation of one identity of one type.
   *
   * <p>With no repository the identity is lifted in <b>every</b> repository of the type that
   * condemned it. That widening is deliberate and only safe in this direction: a restore can never
   * delete anything, so lifting one condemnation too many costs one more grace period of disk, while
   * a restore that silently missed the repository somebody meant would cost the artifact.
   */
  public List<Condemnation> restore(String type, String identity, String repository) {
    String sql =
        "delete from gc_condemned where type = ? and identity = ?"
            + (repository == null ? "" : " and repository = ?")
            + " returning type, repository, identity, condemned_at, run_id";
    List<Condemnation> restored = new ArrayList<>();
    query(
        sql,
        statement -> {
          statement.setString(1, type);
          statement.setString(2, identity);
          if (repository != null) {
            statement.setString(3, repository);
          }
        },
        restored::add);
    restored.sort(
        java.util.Comparator.comparing(Condemnation::repository).thenComparing(Condemnation::identity));
    return restored;
  }

  private interface Binding {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private interface KeyBinding {
    void bind(PreparedStatement statement, GcStrategy.IdentityKey key) throws SQLException;
  }

  private interface RowSink {
    void accept(Condemnation row);
  }

  private void query(String sql, Binding binding, RowSink sink) {
    try (Connection connection = database.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      binding.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          sink.accept(
              new Condemnation(
                  rows.getString(1),
                  rows.getString(2),
                  rows.getString(3),
                  rows.getObject(4, OffsetDateTime.class).toInstant(),
                  rows.getString(5)));
        }
      }
    } catch (SQLException failed) {
      throw new IllegalStateException("gc_condemned: " + failed.getMessage(), failed);
    }
  }

  private void batch(String sql, Collection<GcStrategy.IdentityKey> keys, KeyBinding binding) {
    if (keys.isEmpty()) {
      return;
    }
    try (Connection connection = database.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (GcStrategy.IdentityKey key : keys) {
        binding.bind(statement, key);
        statement.addBatch();
      }
      statement.executeBatch();
    } catch (SQLException failed) {
      throw new IllegalStateException("gc_condemned: " + failed.getMessage(), failed);
    }
  }
}
