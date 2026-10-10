package eu.wohlben.qits.artifacts.contracts;

import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-artifacts' provider states</b> (qits-1149): each seeds what one consumer situation needs
 * and hands back its parameters. A copy of qits-events-service's class of the same name.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before recording each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 * Both call {@link #cleanUp()} afterwards: the database is shared by every test in the run.
 *
 * <p><b>The first states are the daemon version listing</b>, the read qits-maintenance-service
 * ({@code ArtifactPresence}) and the CLI's {@code install.sh} make. Add a state when a consumer's
 * pact asks for one.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_DAEMON_WITH_TWO_VERSIONS = "a daemon with two versions";
  public static final String A_DAEMON_NOTHING_PUBLISHED = "a daemon nothing has published";

  /** The daemon repository every deployment carries, and the one consumers name. */
  static final String DAEMONS = "daemons";

  /** A name no other suite uses, so the listing holds this state's rows and nothing else. */
  static final String DAEMON = "contract-daemon";

  /** What a state hands back: its parameters, keys sorted, and its unique tokens (none here). */
  public record Setup(Map<String, String> params, List<String> uniqueTokens) {}

  @Inject ArtifactRepositoryService repositories;
  @Inject DaemonBinaryRepository daemonBinaries;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();
  private final List<String> createdDaemons = Collections.synchronizedList(new ArrayList<>());

  public ProviderStates() {
    states.put(A_DAEMON_WITH_TWO_VERSIONS, this::aDaemonWithTwoVersions);
    states.put(A_DAEMON_NOTHING_PUBLISHED, this::aDaemonNothingPublished);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** Deletes every row a state created since the last clean-up. */
  public void cleanUp() {
    List<String> names;
    synchronized (createdDaemons) {
      names = List.copyOf(createdDaemons);
      createdDaemons.clear();
    }
    if (!names.isEmpty()) {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  names.forEach(
                      name ->
                          daemonBinaries.delete(
                              "repository = ?1 and name = ?2", DAEMONS, name)));
    }
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /**
   * Two versions of one daemon, the newer published a minute after the older. The listing reads
   * rows only, so no blob is stored: the digest is the sha256 of fixed bytes, the same every run.
   */
  private Setup aDaemonWithTwoVersions() {
    repositories.ensure(DAEMONS, DaemonBinariesProfile.KEY);
    Instant first = Instant.parse("2100-01-01T00:00:00Z");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              daemonBinaries.persist(row("2026.1.0", "contract-daemon 2026.1.0", first));
              daemonBinaries.persist(
                  row("2026.2.0", "contract-daemon 2026.2.0", first.plusSeconds(60)));
            });
    createdDaemons.add(DAEMON);
    return new Setup(params(), List.of());
  }

  /** The daemon repository exists; the daemon has no version in it. */
  private Setup aDaemonNothingPublished() {
    repositories.ensure(DAEMONS, DaemonBinariesProfile.KEY);
    return new Setup(params(), List.of());
  }

  private static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("daemon", DAEMON);
    params.put("repository", DAEMONS);
    return params;
  }

  private static DaemonBinary row(String version, String content, Instant publishedAt) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    DaemonBinary row = new DaemonBinary();
    row.repository = DAEMONS;
    row.name = DAEMON;
    row.version = version;
    row.blobId = sha256(bytes);
    row.sizeBytes = bytes.length;
    row.publishedAt = publishedAt;
    return row;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
