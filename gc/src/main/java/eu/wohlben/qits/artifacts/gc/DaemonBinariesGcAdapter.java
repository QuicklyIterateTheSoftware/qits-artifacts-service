package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.DaemonRegistryCollection;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The platform's own daemon executables, as facts: one row is one identity, every row is a release,
 * and qits-ci names the two it would launch.
 *
 * <h2>Every row is a release, and that is not a shortcut</h2>
 *
 * <p>There is no prerelease coordinate on this type. A {@code daemon_binary} row is written in the
 * same transaction as a publish, publishes come from the release pipeline, and versions are
 * immutable — a re-publish is {@code 409}. So {@link GcCandidate#released()} is true for every row,
 * and the belt is exactly the settlement's sentence: the last two versions of every daemon live
 * whatever their age, and the rest ages out once nothing has launched it for P90D.
 *
 * <p>Adopted rows are the one shape that is not a calver version: the ops adoption of the row-less
 * ELF blobs carries the <b>digest hex itself</b> as the version, so the value {@code
 * QITS_CI_DAEMON_VERSION} already held doubles as a coordinate. {@link #BY_VERSION} ranks those
 * below every calver version rather than guessing at their order — they are the legacy bottom rung
 * by construction, and a 64-hex string compared as a number against a calver would rank the oldest
 * thing here as the newest.
 *
 * <h2>The pins are qits-ci's ladder, both rungs — and a carrier coordinate, for every other daemon</h2>
 *
 * <p>{@code GET /ci/api/daemon} answers with the version a run would launch and the fallback beneath
 * it, and both are kept under qits-ci's own sentence. The <b>digest</b> half of the same aggregate
 * is the binder's floor rather than this adapter's business: a pin has been a sha256 digest since
 * the daemon shipped, so a pinned digest keeps whatever row names those bytes even when the version
 * strings do not match.
 *
 * <p><b>The ladder speaks for one daemon, and that left every other one unspoken for.</b> This type
 * runs at {@code P0D} with a belt of two, so a daemon binary somebody <b>pinned</b> three releases
 * ago is deleted on the run that finds it — and the pin that named it is not in this store at all,
 * it is a line in a pom. That is not hypothetical: {@code qits-platform-access-cli} is pinned by
 * qits-ci's pom and by qits-workspace-oci, qits-ci's ladder is about {@code qits-ci-daemon} and says
 * nothing about it, and the versions rotted until release pipelines 404'd fetching them.
 *
 * <p>So a second source answers here: qits-platform-maintenance's {@code daemon} pins, a daemon
 * binary carried by a maven or npm coordinate a manifest on main references, joined on {@link #AT}
 * exactly as this adapter spells its identities. <b>The ladder wins where both apply</b> — not
 * because either keep is stronger (a kept identity is kept, and the two answers never disagree about
 * whether) but because a receipt states one reason per identity, and the strongest claim available
 * is the right one to state: "a runner would launch this" is a fact about a process that exists,
 * where "a pom names a coordinate that shipped this" is a fact about source. Two keep-sets and two
 * sentences, for the reason the launch pins are two: a report that could not say which source saved
 * a version could not be reviewed.
 *
 * <h2>Effective access, and the read this type deliberately cannot see</h2>
 *
 * <p>{@code max(published_at, accessed_at)} — V11's column, moved by the version-addressed GET only.
 * The digest-addressed {@code /v2} blob route has no twin of it and must not grow one: it resolves
 * an OCI repository and a globally deduplicated digest, so the request carries no daemon identity.
 * What keeps a digest-fetched daemon alive is the pin, which is why the pin is checked first.
 *
 * <p>Deletion runs through {@code DaemonRegistryCollection}, one row per identity, with <b>no
 * tombstone</b> — the reasoning is on {@code DaemonRegistryService.collect}, and it is a decision
 * rather than an omission.
 */
@Singleton
public class DaemonBinariesGcAdapter implements GcTypeAdapter {

  /** The separator between a daemon's name and its version, as the download route spells it. */
  static final String AT = "@";

  /** The adopted rows' version shape: the blob's own digest hex (⚖5 of the identity plan). */
  static final Pattern ADOPTED_DIGEST = Pattern.compile("[0-9a-f]{64}");

  /** A calver release version: {@code <year>.<month><day>.<time>}. */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  @Inject ArtifactRepositoryRepository repositories;
  @Inject DaemonBinaryRepository binaries;
  @Inject DaemonRegistryCollection daemons;

  @Override
  public String type() {
    return DaemonBinariesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!DaemonBinariesProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (DaemonBinary row : binaries.<DaemonBinary>list("repository = ?1", repository.name)) {
        candidates.add(
            new GcCandidate(
                row.repository,
                row.name + AT + row.version,
                // The belt counts per daemon of one repository: two daemons publishing on the same
                // cadence must not spend each other's slots.
                row.repository + "/" + row.name,
                true,
                latest(row.publishedAt, row.accessedAt),
                Set.of(row.blobId)));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * qits-ci's ladder, both rungs, under qits-ci's own sentence — then a carrier coordinate under
   * maintenance's.
   *
   * <p>The identity goes to the second source <b>verbatim</b>: {@code GcPinSources} folds a {@code
   * daemon} pin with this class's own {@link #AT}, so the lookup is an equality test and never a
   * translation. The ladder is asked first, for the reason the class javadoc argues.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    return candidate -> {
      int at = candidate.identity().indexOf(AT);
      String byLadder =
          pins.pinsDaemonVersion(
              candidate.identity().substring(0, at), candidate.identity().substring(at + 1));
      return byLadder != null ? byLadder : pins.pinsCarriedDaemon(candidate.identity());
    };
  }

  /** Oldest first by version; ties on the identity so a report is stable across runs. */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), BY_VERSION)
        .thenComparing(GcCandidate::identity);
  }

  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      int at = dead.identity().indexOf(AT);
      String name = dead.identity().substring(0, at);
      String version = dead.identity().substring(at + 1);
      try {
        DaemonBinary row = binaries.findOne(dead.repository(), name, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such daemon row — the store moved since planning");
          continue;
        }
        if (grace.withinGrace(row.blobId)) {
          withheld.add(dead);
          continue;
        }
        daemons.collect(dead.repository(), name, version);
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** A daemon's name cannot contain an {@code @}; the first one separates it from the version. */
  private static String versionOf(String identity) {
    return identity.substring(identity.indexOf(AT) + 1);
  }

  /** Publication counts as the first access, so a version published minutes ago reads as young. */
  private static Instant latest(Instant published, Instant accessed) {
    return accessed == null || accessed.isBefore(published) ? published : accessed;
  }

  private static final int CALVER_RANK = 2;

  /**
   * Version order, oldest first: an adopted digest-hex version below every calver version, calver
   * versions by their three numeric parts, and anything else below both with a lexical tie-break so
   * the order stays total.
   */
  static final Comparator<String> BY_VERSION =
      (left, right) -> {
        int ranked = Integer.compare(rank(left), rank(right));
        if (ranked != 0) {
          return ranked;
        }
        if (rank(left) != CALVER_RANK) {
          return left.compareTo(right);
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int part = 0; part < leftParts.length; part++) {
          int compared =
              Long.compare(Long.parseLong(leftParts[part]), Long.parseLong(rightParts[part]));
          if (compared != 0) {
            return compared;
          }
        }
        return 0;
      };

  /** Adopted below unmodelled below calver — the ladder the platform actually published on. */
  private static int rank(String version) {
    if (ADOPTED_DIGEST.matcher(version).matches()) {
      return 0;
    }
    return CALVER.matcher(version).matches() ? CALVER_RANK : 1;
  }
}
