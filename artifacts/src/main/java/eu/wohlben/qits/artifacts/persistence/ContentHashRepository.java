package eu.wohlben.qits.artifacts.persistence;

import eu.wohlben.qits.artifacts.entity.ContentHash;
import eu.wohlben.qits.artifacts.entity.ContentHashId;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class ContentHashRepository implements PanacheRepositoryBase<ContentHash, ContentHashId> {

  public Optional<ContentHash> findOne(
      String repository, String ecosystem, String packageName, String version) {
    return findByIdOptional(new ContentHashId(repository, ecosystem, packageName, version));
  }

  /**
   * Inserts the row unless one already stands for the key, and says whether it did.
   *
   * <p>{@code on conflict do nothing} rather than find-then-persist: two retried uploads of the same
   * version racing each other must not turn into a duplicate-key 500 for the loser. Whichever row
   * stands afterwards is read back by the caller in the same transaction, which under read committed
   * sees the winner's committed row.
   */
  public boolean insertIfAbsent(
      String repository,
      String ecosystem,
      String packageName,
      String version,
      String value,
      Instant createdAt) {
    return getEntityManager()
            .createNativeQuery(
                "insert into content_hash (repository, ecosystem, package_name, version, value,"
                    + " created_at) values (?1, ?2, ?3, ?4, ?5, ?6) on conflict do nothing")
            .setParameter(1, repository)
            .setParameter(2, ecosystem)
            .setParameter(3, packageName)
            .setParameter(4, version)
            .setParameter(5, value)
            .setParameter(6, createdAt)
            .executeUpdate()
        == 1;
  }

  /** Removes the row for one version, if any — the GC adapters' half of collecting it. */
  public long deleteOne(String repository, String ecosystem, String packageName, String version) {
    return delete(
        "repository = ?1 and ecosystem = ?2 and packageName = ?3 and version = ?4",
        repository,
        ecosystem,
        packageName,
        version);
  }
}
