package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.MavenException;
import eu.wohlben.qits.artifacts.error.NpmException;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.registry.ContentHashLedger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The {@link ContentHashLedger} this service supplies, over the {@code content_hash} table — and by
 * existing, the bean that replaces the library's {@code @DefaultBean} no-op.
 *
 * <p><b>First write wins.</b> No row: insert. An equal value: nothing, so a retried pom PUT stays
 * green. A different value: {@code 409}, thrown as the calling registry's own exception so the route
 * answers it the way it answers every other refusal.
 *
 * <p>Its own transaction: the route calls it after {@code deploy}/{@code publish} has committed, from
 * a raw Vert.x handler that opens none.
 */
@ApplicationScoped
public class JpaContentHashLedger implements ContentHashLedger {

  @Inject ContentHashRepository hashes;

  /** Read by the content-hash read route; empty when the version carries none. */
  @ActivateRequestContext
  public Optional<String> find(String ecosystem, String repository, String name, String version) {
    return hashes.findOne(repository, ecosystem, name, version).map(row -> row.value);
  }

  @Override
  @ActivateRequestContext
  @Transactional
  public void record(String ecosystem, String repository, String name, String version, String value) {
    if (hashes.insertIfAbsent(
        repository, ecosystem, name, version, value, Instant.now().truncatedTo(ChronoUnit.MICROS))) {
      return;
    }
    String stored = hashes.findOne(repository, ecosystem, name, version).map(row -> row.value).orElse(null);
    if (value.equals(stored)) {
      return;
    }
    throw conflict(
        ecosystem,
        name
            + "@"
            + version
            + " already records content hash "
            + stored
            + "; this upload says "
            + value);
  }

  private static RuntimeException conflict(String ecosystem, String message) {
    return switch (ecosystem) {
      case "maven" -> new MavenException(409, message);
      case "npm" -> new NpmException(409, message);
      default -> new IllegalArgumentException("no such ecosystem '" + ecosystem + "'");
    };
  }
}
