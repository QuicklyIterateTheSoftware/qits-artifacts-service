package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.control.MavenVersionOrder;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The platform's own maven repository, as facts — and the one type whose identity is <b>not</b> the
 * row.
 *
 * <h2>A coordinate is the identity, not a path</h2>
 *
 * <p>A maven version is a <em>set</em> of files: a jar, its pom, sometimes sources beside them. Half
 * a version is not a smaller version, it is a broken resolve — a jar whose pom is gone fails every
 * build that reaches it — and the settled rule counts <b>versions</b> ("the last 2 release versions
 * per artifact"), which an engine counting paths could not express at all. So one identity here is
 * one resolvable coordinate, spelled the way a client spells it, and every file under it lives or
 * dies together:
 *
 * <ul>
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.0} — a release version directory. A
 *       <b>release</b>, so the belt counts it.
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3} — one timestamped snapshot
 *       deploy, exactly the coordinate the derived version-level metadata resolves
 *       {@code 1.0.1-SNAPSHOT} to. Not a release.
 *   <li>{@code eu.wohlben.qits:qits-eventstream:1.0.1-SNAPSHOT} — the literal-filename snapshot, the
 *       one mutable path class ({@code uniqueVersion=false}). Not a release.
 * </ul>
 *
 * <p>A path the layout cannot parse is its own identity under its own path spelling. Nothing can
 * deploy one — the wire refuses an unparseable path at the door — so this is the honest answer for
 * a row that predates a rule rather than a case that happens.
 *
 * <h2>A published release is never collected. Not by age, not by a belt.</h2>
 *
 * <p>This type used to be priced like the other three own types: the last two releases per {@code
 * (groupId, artifactId)} kept by policy, everything older surviving only on access inside the
 * configured window. On <b>2026-09-05T01:58Z</b> that rule deleted 67 published {@code
 * eu.wohlben.qits} coordinates in one run — every one of them under "superseded and unaccessed for
 * longer than P3D" — and every gating build on the platform stopped resolving. The rule is
 * therefore <b>withdrawn</b> rather than retuned, and the argument is written here so it is not
 * quietly re-derived the next time this store looks large:
 *
 * <ul>
 *   <li><b>For a library, access was never consumption.</b> Every other own type is consumed by
 *       being fetched: an image is pulled to run, a daemon binary is downloaded to launch. A jar is
 *       fetched <em>once</em> and then answered out of a hundred local {@code ~/.m2} caches and
 *       every build image baked since. A version can be what a thousand builds compile against and
 *       still show no read here for a month. Age on a maven row measures cache warmth, not need,
 *       and no window length makes it measure need.
 *   <li><b>A pin cannot be the floor under a deletion this size.</b> The short window was made
 *       defensible by {@code MaintenanceDependencyPins} naming what main's manifests reference.
 *       That source is right about what it claims and much narrower than the set a build resolves:
 *       on the morning of the incident it named 13 maven coordinates — one current version per
 *       artifact, from main — while the store held hundreds that branches, parent poms, transitive
 *       ranges and every unbumped consumer still resolve. A keep-set assembled from direct
 *       references on one branch is a floor under nothing, and a pin source that is one service's
 *       reachability away from empty is a floor that disappears exactly when it is load-bearing.
 *   <li><b>The disk this buys is not the disk that is full.</b> Measured the morning of the
 *       incident: 29.4 GB of store, of which oci-images 28.8 GB, docs 114.7 MB and sboms 134.3 MB.
 *       The whole hosted maven repository does not appear in that summary, because against images
 *       it rounds to nothing — the 67 coordinates freed a few megabytes. Disk pressure here is the
 *       image store's problem and the proxy caches', and those are collected by rules of their own.
 *       Trading a platform-wide build outage for a rounding error is not a trade this type may make.
 *   <li><b>The registry is the platform's artifact of record.</b> Nothing else holds these bytes. A
 *       released coordinate is immutable and re-publishing one is impossible by construction, so a
 *       collection here is not reclaimable space, it is the loss of a build input — and the pom that
 *       names it is on someone's main branch whether or not this service can see the branch.
 * </ul>
 *
 * <p><b>What is kept, then:</b> every release version, whatever its age and whatever its position
 * in the version order; and every row whose path {@link MavenLayout} cannot read, because a file
 * this adapter cannot name is a file it cannot promise is not half of something.
 *
 * <p><b>What still ages out</b> is the one class of content this store genuinely regenerates:
 * superseded timestamped snapshot sets. A snapshot is build output rather than a published
 * coordinate, nothing's main pom pins one, and the set a resolver would actually be sent to is kept
 * structurally — <b>the newest deployable set of every snapshot version line is always kept</b>
 * ({@link #pinnedBy}), the newest timestamped set if the line has any, else the literal {@code
 * -SNAPSHOT} set. {@code maven-metadata.xml} is computed from the surviving rows at read time, so a
 * resolver asking for {@code 1.0.1-SNAPSHOT} is redirected to whatever is newest; deleting that one
 * would point the document at a file the store no longer has, which is the single failure this type
 * must not produce. Older timestamped sets are ordinary candidates and age out at the configured
 * window. And a pin still outranks all of it: a coordinate some manifest names is kept under the
 * pin's own rule whether it is a release or a snapshot, so the invariant — <b>anything a main pom
 * pins survives</b> — holds through both doors rather than through one.
 *
 * <p>What this deliberately does <b>not</b> do is keep a fixed number of snapshot builds. The plan
 * that named this type's cleanup ({@code maven-repository-plan.md} §3.6) never priced deletion, so
 * the conservative reading is taken: the window decides for snapshots, and the only structural keep
 * among them is the one a resolver would break without.
 *
 * <p>{@link #byAge()} and {@link OwnArtifactsStrategy#RELEASES_KEPT} therefore no longer decide
 * anything for this type — every release is kept before the belt is consulted. The comparator stays
 * because the engine's contract asks for one and because a total order over maven versions is the
 * honest answer to that question; it is simply no longer the thing standing between a published jar
 * and a delete.
 *
 * <h2>Access</h2>
 *
 * <p>A coordinate's effective access is the <b>newest</b> {@code max(created_at, accessed_at)} over
 * its files. A pom read is a resolve of the version, so one warm file keeps the set — the opposite
 * choice would let a jar nothing has re-downloaded drag its own pom out from under it. The derived
 * documents move nothing: metadata and checksums are computed per request and are no row's bytes.
 */
@Singleton
public class MavenPackagesGcAdapter implements GcTypeAdapter {

  /** The keep every snapshot version line gets, named so a report says which resolve it protects. */
  static final String KEPT_RESOLVABLE_SNAPSHOT =
      "the newest deployable set of this snapshot version — what the derived maven-metadata.xml"
          + " resolves to, so a resolver would 404 without it";

  /**
   * The keep every published release gets — the rule the 2026-09-05 outage bought, said in full on
   * every line it saves so a reviewer never has to go looking for why nothing maven died.
   */
  static final String KEPT_HOSTED_RELEASE =
      "a published release of this platform's own maven repository — hosted releases are never"
          + " collected, at any age and at any depth in the version order. This store is the"
          + " artifact of record for coordinates that live on someone's main branch, an access"
          + " timestamp measures cache warmth rather than need, and the disk it would free rounds"
          + " to nothing beside the image store";

  /**
   * The keep a row this layout cannot parse gets, for the reason the class javadoc gives: a file
   * this adapter cannot name is a file it cannot promise is not half of a version.
   */
  static final String KEPT_UNREADABLE_PATH =
      "this path is not <group>/<artifact>/<version>/<file>, so this adapter cannot say which"
          + " coordinate it belongs to — and a file it cannot name is one it cannot collect without"
          + " risking half a version";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenRegistryCollection maven;
  @Inject ContentHashRepository contentHashes;

  @Override
  public String type() {
    return MavenPackagesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!MavenPackagesProfile.KEY.equals(repository.type)) {
        continue;
      }
      units(repository.name).values().forEach(unit -> candidates.add(unit.candidate()));
    }
    return List.copyOf(candidates);
  }

  /**
   * Every keep this type has, in the order a report reads best: the coordinates a repository's pom
   * still names, then every published release, then rows this layout cannot parse, then the newest
   * deployable set of every snapshot version line.
   *
   * <p><b>The dependency pin needs no translation at all</b>, which is the property that makes it
   * safe: {@code groupId:artifactId:version} is what a pom writes and it is this adapter's identity
   * verbatim, so the lookup is an equality test on the string the enumeration already built. It is
   * asked first because it is a fact about somebody else's source — a reader who sees it wants to
   * know which repository still builds against this version, not that it would have been kept for
   * being a release anyway.
   *
   * <p><b>The release keep is expressed here rather than in the engine</b>, and that is the seam
   * working as designed: what a release <em>is</em> has always been this adapter's fact, and so is
   * what one is worth. {@link OwnArtifactsStrategy} still counts to two for the three types that
   * want a belt; this type answers before it is asked, so no release ever reaches the belt or the
   * access window. Nothing about the engine changes, and nothing about the other three types does.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> newestPerLine = new HashMap<>();
    for (GcCandidate candidate : candidates) {
      // A snapshot line is exactly a group whose version directory ends in -SNAPSHOT, which is the
      // layout's own rule rather than a second reading of it. Releases and the unparseable rows
      // have no metadata redirect to protect.
      if (!candidate.group().endsWith("-SNAPSHOT")) {
        continue;
      }
      newestPerLine.merge(
          candidate.group(),
          candidate.identity(),
          (held, other) -> BY_SNAPSHOT_RECENCY.compare(held, other) >= 0 ? held : other);
    }
    return candidate -> {
      String byManifest = pins.pinsMavenCoordinate(candidate.identity());
      if (byManifest != null) {
        return byManifest;
      }
      if (candidate.released()) {
        return KEPT_HOSTED_RELEASE;
      }
      // An unparseable row is its own identity under its own path spelling, and `groupOf` gives it
      // the repository name as its group — which is how it is recognised again here without a
      // second reading of the layout.
      if (candidate.group().equals(candidate.repository())) {
        return KEPT_UNREADABLE_PATH;
      }
      return candidate.identity().equals(newestPerLine.get(candidate.group()))
          ? KEPT_RESOLVABLE_SNAPSHOT
          : null;
    };
  }

  /**
   * Oldest release first, by maven's own version order; ties on the identity so a report is stable
   * across runs.
   *
   * <p>{@link MavenVersionOrder} is total by construction — what it cannot read sorts last within
   * its own class and the document still serves — which is the property this comparator needs too.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), MavenVersionOrder.INSTANCE)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * One coordinate at a time, every file of it, through {@code MavenRegistryCollection}.
   *
   * <p>The unit is re-derived from the rows rather than carried on the plan, for the reason every
   * {@code apply} here re-reads the store: a plan is applied moments after it was computed, and a
   * coordinate that gained a file in between must be removed whole or not at all.
   *
   * <p><b>The grace window gates the whole set.</b> One young file withholds the coordinate — its
   * rows all stay, and the next run past the window takes rows and files together. Deleting the
   * mature rows and keeping the young one would leave exactly the half-version this type's identity
   * model exists to prevent.
   *
   * <p><b>And the removal itself is one transaction per coordinate</b>, which is the half of "lives
   * or dies together" the model claimed and the code did not have. {@code
   * MavenRegistryService.collect} is {@code @Transactional} <em>per file</em>, so this loop used to
   * commit path by path: a throw on the second file — a concurrent deploy or collection moving the
   * store between planning and applying is the documented way it happens — left the first file
   * deleted and the rest alive. The paths sort lexically, {@code .jar} before {@code .pom}, so the
   * shape that failure leaves behind is precisely the one that breaks every resolve: a version whose
   * pom answers 200 and whose jar answers 404. Wrapping the coordinate makes the failure a no-op,
   * reported as an error against an identity that is still whole and re-planned next run.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    Map<String, Map<String, Unit>> byRepository = new HashMap<>();
    for (GcIdentity dead : plan.dead()) {
      Unit unit =
          byRepository
              .computeIfAbsent(dead.repository(), this::units)
              .get(dead.identity());
      if (unit == null) {
        errors.add(dead.identity() + ": no such coordinate — the store moved since planning");
        continue;
      }
      if (unit.blobs().stream().anyMatch(grace::withinGrace)) {
        withheld.add(dead);
        continue;
      }
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  for (String path : unit.paths()) {
                    maven.collect(dead.repository(), path);
                  }
                  // The coordinate's content hash goes with its files, in the same transaction:
                  // a row left behind would describe a version this store no longer serves.
                  forgetContentHash(dead);
                });
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(
            dead.identity()
                + ": "
                + failed.getMessage()
                + " — the coordinate was left whole, every file of it still a row");
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One repository's rows, folded into coordinates. Keyed by identity, in path order. */
  /**
   * Deletes the {@code content_hash} row of one collected coordinate, keyed the way the ledger
   * records it: {@code groupId:artifactId} and the version. An identity with no version colon is an
   * unreadable path, which is never condemned, and has no row to delete.
   */
  private void forgetContentHash(GcIdentity dead) {
    int colon = dead.identity().lastIndexOf(':');
    if (colon < 0) {
      return;
    }
    contentHashes.deleteOne(
        dead.repository(),
        "maven",
        dead.identity().substring(0, colon),
        dead.identity().substring(colon + 1));
  }

  private Map<String, Unit> units(String repository) {
    Map<String, Unit> units = new LinkedHashMap<>();
    for (MavenArtifact row : artifacts.<MavenArtifact>list("repository = ?1", repository)) {
      MavenLayout.ArtifactPath parsed = MavenLayout.parse(row.path);
      String identity = identityOf(parsed, row.path);
      units
          .computeIfAbsent(identity, key -> new Unit(repository, key, groupOf(repository, parsed)))
          .add(row);
    }
    return units;
  }

  /**
   * The coordinate a client would name this file's set by, or the raw path when the layout cannot
   * read it.
   */
  private static String identityOf(MavenLayout.ArtifactPath parsed, String path) {
    if (parsed == null) {
      return path;
    }
    String coordinate = parsed.groupId() + ":" + parsed.artifactId() + ":";
    MavenLayout.SnapshotFileName timestamped =
        MavenLayout.parseTimestampedSnapshot(parsed.artifactId(), parsed.version(), parsed.file());
    if (timestamped == null) {
      return coordinate + parsed.version();
    }
    return coordinate
        + timestamped.value(
            parsed.version().substring(0, parsed.version().length() - "-SNAPSHOT".length()));
  }

  /**
   * The group the belt counts within: the artifact for a release, the version line for a snapshot.
   *
   * <p>Two different questions wearing one field, and both are the settled ones: "the last 2 release
   * versions per artifact" needs the artifact, and "the newest deployable set of this snapshot
   * version" needs the version line. A release group can never collide with a snapshot group because
   * a version directory ending in {@code -SNAPSHOT} is what separates them.
   */
  private static String groupOf(String repository, MavenLayout.ArtifactPath parsed) {
    if (parsed == null) {
      return repository;
    }
    String artifact = repository + "/" + parsed.groupId() + ":" + parsed.artifactId();
    return MavenLayout.isSnapshotVersion(parsed.version())
        ? artifact + ":" + parsed.version()
        : artifact;
  }

  /** The version half of a {@code group:artifact:version} identity; a raw path answers whole. */
  private static String versionOf(String identity) {
    int colon = identity.lastIndexOf(':');
    return colon < 0 ? identity : identity.substring(colon + 1);
  }

  /**
   * Which of two snapshot coordinates a resolver would be sent to, newest last.
   *
   * <p>A literal {@code -SNAPSHOT} coordinate ranks below every timestamped one, so it is the newest
   * deployable set only on a line with no timestamped deploys at all — which is exactly when a
   * client resolves it by name. Timestamped coordinates order by their {@code yyyyMMdd.HHmmss}
   * stamp, which is lexical by construction, and then by <b>build number as a number</b>: two
   * deploys inside one second are the only case where that differs from a string compare, and
   * getting it wrong there would point the metadata at the earlier of the two.
   */
  static final Comparator<String> BY_SNAPSHOT_RECENCY =
      Comparator.<String, Boolean>comparing(identity -> !identity.endsWith("-SNAPSHOT"))
          .thenComparing(identity -> stampOf(versionOf(identity)))
          .thenComparingLong(identity -> buildNumberOf(versionOf(identity)));

  /** The {@code yyyyMMdd.HHmmss} stamp of a timestamped version, or the version itself. */
  private static String stampOf(String version) {
    int dash = version.lastIndexOf('-');
    return dash < 0 ? version : version.substring(0, dash);
  }

  /** The build number of a timestamped version, or zero when there is none to read. */
  private static long buildNumberOf(String version) {
    int dash = version.lastIndexOf('-');
    if (dash < 0) {
      return 0L;
    }
    try {
      return Long.parseLong(version.substring(dash + 1));
    } catch (NumberFormatException notANumber) {
      return 0L;
    }
  }

  /** One coordinate's rows, accumulated as the enumeration walks them. */
  private static final class Unit {

    private final String repository;
    private final String identity;
    private final String group;
    private final Set<String> paths = new TreeSet<>();
    private final Set<String> blobs = new TreeSet<>();
    private boolean released;
    private Instant lastAccessAt;

    private Unit(String repository, String identity, String group) {
      this.repository = repository;
      this.identity = identity;
      this.group = group;
    }

    private void add(MavenArtifact row) {
      paths.add(row.path);
      blobs.add(row.blobId);
      MavenLayout.ArtifactPath parsed = MavenLayout.parse(row.path);
      released = parsed != null && !MavenLayout.isSnapshotVersion(parsed.version());
      Instant access =
          row.accessedAt == null || row.accessedAt.isBefore(row.createdAt)
              ? row.createdAt
              : row.accessedAt;
      // The NEWEST access across the set: a pom read is a resolve of the version, and one warm file
      // keeps the coordinate whole rather than letting a cold sibling drag it out.
      if (lastAccessAt == null || lastAccessAt.isBefore(access)) {
        lastAccessAt = access;
      }
    }

    private Set<String> paths() {
      return paths;
    }

    private Set<String> blobs() {
      return blobs;
    }

    private GcCandidate candidate() {
      return new GcCandidate(repository, identity, group, released, lastAccessAt, blobs);
    }
  }
}
