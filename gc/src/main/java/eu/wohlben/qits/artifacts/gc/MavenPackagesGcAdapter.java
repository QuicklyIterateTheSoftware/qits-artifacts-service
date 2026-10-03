package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.control.MavenVersionOrder;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <h2>A release lives while something kept still needs it</h2>
 *
 * <p>Since 2026-10-03 (qits-739) a published release is collected again, and only when nothing the
 * keep-set holds reaches it. In order, so a receipt names the strongest reason a coordinate has:
 *
 * <ol>
 *   <li><b>A manifest pin</b> — a repository's pom on main names the coordinate ({@link
 *       GcPins#pinsMavenCoordinate}).
 *   <li><b>The closure</b> — the coordinate is reached from the seeds by {@link MavenKeepClosure}:
 *       named by the stored SBOM of a reached coordinate, the parent pom or an imported BOM of one,
 *       or a non-test dependency in the pom of one that has no SBOM. The seeds are the manifest
 *       pins, the newest {@link OwnArtifactsStrategy#RELEASES_KEPT} releases of every {@code
 *       (groupId, artifactId)}, and the snapshot sets kept below; the walk runs to a fixpoint.
 *   <li><b>The belt</b> — the newest {@link OwnArtifactsStrategy#RELEASES_KEPT} releases of every
 *       {@code (groupId, artifactId)}, kept by the engine because nothing here answers for them
 *       first.
 *   <li>Rows this layout cannot read, and the newest deployable set of every snapshot line, as
 *       before (below).
 * </ol>
 *
 * <p>Everything else — a release included — is condemned under the configured window ({@code P0D})
 * and then withheld whole by the sweep's six-hour blob grace, which is what still protects a
 * version published minutes ago whose consumer has not folded yet.
 *
 * <h2>2026-09-05, and how each of its reasons is answered now</h2>
 *
 * <p>This type was once priced like the other own types: the last two releases per {@code (groupId,
 * artifactId)} kept by policy, everything older surviving only on access inside the configured
 * window. On <b>2026-09-05T01:58Z</b> that rule deleted 67 published {@code eu.wohlben.qits}
 * coordinates in one run — every one of them under "superseded and unaccessed for longer than P3D"
 * — and every gating build on the platform stopped resolving. Releases were then kept forever, and
 * the argument was written here so it would not be quietly re-derived. Its reasons were sound
 * against that rule; what changed is that each now has a keep of its own rather than an age:
 *
 * <ul>
 *   <li><b>Transitive dependencies.</b> The manifest pins name what a pom on main writes, and on
 *       the morning of the incident that was 13 coordinates against hundreds a build resolved —
 *       nothing named what those poms then pulled in. Every published release now carries a
 *       CycloneDX SBOM in this very store, and an SBOM is <em>transitive</em>: the closure keeps
 *       every hosted coordinate the document of a kept coordinate names, and then that
 *       coordinate's own document, to a fixpoint. {@code SbomGcAdapter} keeps a document while its
 *       artifact is stored, so the input to this rule lives exactly as long as it is needed.
 *   <li><b>Parent poms and imported BOMs, which no SBOM lists.</b> They are inputs of the pom, not
 *       dependencies of the artifact, and a resolve reads both files. The closure follows the
 *       stored {@code .pom}'s {@code <parent>} and its {@code import}-scoped managed dependencies for
 *       every reached coordinate. Published poms are flattened since qits-620, so this is mostly
 *       empty — but {@code qits-githost-events:2026.910.103045} names {@code
 *       qits-githost:2026.910.103045} as its parent, and that parent would otherwise go.
 *   <li><b>Unbumped consumers.</b> These are the manifest pins, read from every repository's main,
 *       and they are unchanged: a coordinate some pom on main still writes is kept, and everything
 *       it needs is kept with it through the closure.
 * </ul>
 *
 * <p><b>Branches are not a keep-class.</b> Owner ruling 2026-10-03: a branch rebases onto main, so
 * what a branch resolves is what main pins or something newer, and a branch hand-pinning an older
 * internal version is a branch to rebase rather than a keep this store owes it.
 *
 * <p><b>A coordinate with no SBOM is never a reason to delete what it uses.</b> Its pom's {@code
 * <dependencies>} — compile, runtime, provided and system, never test — stand in for the document.
 * A flattened pom carries exact versions; most of the store predates qits-620, though, so a version
 * is read from the pom's <em>effective</em> model the way maven reads it — properties and {@code
 * dependencyManagement} inherited from its stored parents and imported BOMs ({@link
 * MavenKeepClosure} says exactly how far that goes).
 *
 * <h2>Fail closed</h2>
 *
 * <p>The closure is a keep-set, so a partial one is a deletion of whatever the missing part would
 * have named. If any reached coordinate's pom or SBOM (or a pom it inherits from) cannot be read, if
 * an SBOM does not parse, if a reached coordinate has no pom at all, or if a version on a reference this store hosts
 * cannot be resolved (a {@code ${…}} no stored pom in its chain defines, a range, a dependency
 * nothing manages), {@link
 * #pinnedBy} keeps <b>every</b> maven identity that run under {@link #failClosed} — a rule string
 * naming the coordinate and the reason, on every line of the receipt, so a report says why maven
 * collected nothing rather than leaving an empty dead list to be read as a finding. The type still
 * plans, and the other types are untouched: the failure is this type's fact, and refusing the plan
 * instead would read as an engine error rather than as the deliberate keep it is.
 *
 * <p><b>A pom that was read and is not XML is the exception, and a dead end rather than a gap</b>
 * (orchestrator ruling, 2026-10-03). Maven itself cannot resolve such a coordinate, so nothing builds
 * through it and it has no dependencies anyone relies on — nothing is unknown. It stays kept by
 * whatever keeps it, the closure follows nothing from it, and its receipt line says so ({@link
 * MavenKeepClosure#NOT_XML}). {@code eu:probe:1}, a leftover publish probe whose pom is the byte
 * {@code x}, is why: as the only release of its artifact it is a belt seed on every run, and the
 * stricter reading kept every maven identity forever.
 *
 * <h2>npm follows the same shape</h2>
 *
 * <p>{@code NpmPackagesGcAdapter} collects releases the same way since qits-740, over {@code
 * NpmKeepClosure}: a manifest pin — including, via qits-maintenance, a lockfile reached through a
 * service's frontend submodule gitlink, the hole that kept npm uncollected until then — the closure
 * over dependency ranges and SBOMs, a dist-tag, or the newest two.
 *
 * <h2>Snapshots</h2>
 *
 * <p>Superseded timestamped snapshot sets age out as before. A snapshot is build output rather than
 * a published coordinate, and the set a resolver would actually be sent to is kept structurally —
 * <b>the newest deployable set of every snapshot version line is always kept</b> ({@link
 * #pinnedBy}), the newest timestamped set if the line has any, else the literal {@code -SNAPSHOT}
 * set. {@code maven-metadata.xml} is computed from the surviving rows at read time, so a resolver
 * asking for {@code 1.0.1-SNAPSHOT} is redirected to whatever is newest; deleting that one would
 * point the document at a file the store no longer has. Those sets seed the closure too, so what
 * they need is kept with them. What this deliberately does <b>not</b> do is keep a fixed number of
 * snapshot builds: {@code maven-repository-plan.md} §3.6 never priced it, so the window decides.
 *
 * <h2>Access</h2>
 *
 * <p>A coordinate's effective access is the <b>newest</b> {@code max(created_at, accessed_at)} over
 * its files. A pom read is a resolve of the version, so one warm file keeps the set — the opposite
 * choice would let a jar nothing has re-downloaded drag its own pom out from under it. The derived
 * documents move nothing: metadata and checksums are computed per request and are no row's bytes.
 * At {@code P0D} access keeps nothing by itself in any case; the keep-classes above are the policy.
 */
@Singleton
public class MavenPackagesGcAdapter implements GcTypeAdapter {

  /** The keep every snapshot version line gets, named so a report says which resolve it protects. */
  static final String KEPT_RESOLVABLE_SNAPSHOT =
      "the newest deployable set of this snapshot version — what the derived maven-metadata.xml"
          + " resolves to, so a resolver would 404 without it";

  /**
   * The keep a row this layout cannot parse gets, for the reason the class javadoc gives: a file
   * this adapter cannot name is a file it cannot promise is not half of a version.
   */
  static final String KEPT_UNREADABLE_PATH =
      "this path is not <group>/<artifact>/<version>/<file>, so this adapter cannot say which"
          + " coordinate it belongs to — and a file it cannot name is one it cannot collect without"
          + " risking half a version";

  /** What every maven identity is kept under on a run whose closure could not be completed. */
  static String failClosed(String coordinate, String reason) {
    return "maven collects nothing this run: the keep closure could not be completed at "
        + coordinate
        + " — "
        + reason
        + ". A partial closure is never a deletion, so every maven identity is kept";
  }

  @Inject ArtifactRepositoryRepository repositories;
  @Inject MavenArtifactRepository artifacts;
  @Inject MavenRegistryCollection maven;
  @Inject ContentHashRepository contentHashes;
  @Inject SbomDocumentRepository sbomDocuments;

  /**
   * How the closure reads a pom's or an SBOM's bytes: {@code BlobStore.open} is public and
   * read-only, so this needs no narrow door of its own. It also moves nothing — {@code accessed_at}
   * lives on the rows and is touched by the wire, so the collector reading a pom cannot warm its own
   * candidate.
   */
  @Inject BlobStore blobs;

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
   * Every keep this type has, in the order the class javadoc gives: the manifest pin, the closure,
   * rows this layout cannot parse, and the newest deployable set of every snapshot line. The release
   * belt is the engine's and is asked after this answers null.
   *
   * <p><b>The dependency pin needs no translation at all</b>, which is the property that makes it
   * safe: {@code groupId:artifactId:version} is what a pom writes and it is this adapter's identity
   * verbatim, so the lookup is an equality test on the string the enumeration already built. It is
   * asked first because it is a fact about somebody else's source — a reader who sees it wants to
   * know which repository still builds against this version.
   *
   * <p><b>The closure is computed once per plan</b>, here, over the enumeration the binder already
   * took and the pins the run already read. Per candidate it would re-walk the store for every
   * coordinate; against a second enumeration it would judge one run by two snapshots — which is why
   * this method takes the whole list in the first place. The release belt is asked of {@link
   * OwnArtifactsStrategy#lastReleasesPerGroup} rather than re-derived, so the seeds and the belt the
   * engine applies are one answer.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> newestPerLine = new HashMap<>();
    Set<String> hosted = new LinkedHashSet<>();
    for (GcCandidate candidate : candidates) {
      // An unparseable row is its own identity under its own path spelling, and `groupOf` gives it
      // the repository name as its group; it is no coordinate, so nothing can reach it.
      if (!unreadable(candidate)) {
        hosted.add(candidate.identity());
      }
      // A snapshot line is exactly a group whose version directory ends in -SNAPSHOT, which is the
      // layout's own rule rather than a second reading of it.
      if (!candidate.group().endsWith("-SNAPSHOT")) {
        continue;
      }
      newestPerLine.merge(
          candidate.group(),
          candidate.identity(),
          (held, other) -> BY_SNAPSHOT_RECENCY.compare(held, other) >= 0 ? held : other);
    }

    Set<String> seeds = new LinkedHashSet<>(newestPerLine.values());
    for (GcCandidate belted : OwnArtifactsStrategy.lastReleasesPerGroup(candidates, this)) {
      seeds.add(belted.identity());
    }
    for (String coordinate : hosted) {
      if (pins.pinsMavenCoordinate(coordinate) != null) {
        seeds.add(coordinate);
      }
    }

    MavenKeepClosure.Result closure =
        MavenKeepClosure.from(seeds, hosted, documents(candidates));

    if (closure instanceof MavenKeepClosure.Incomplete incomplete) {
      String everything = failClosed(incomplete.coordinate(), incomplete.reason());
      Log.warnf("gc: %s", everything);
      return candidate -> {
        String byManifest = pins.pinsMavenCoordinate(candidate.identity());
        if (byManifest != null) {
          return byManifest;
        }
        if (unreadable(candidate)) {
          return KEPT_UNREADABLE_PATH;
        }
        if (candidate.identity().equals(newestPerLine.get(candidate.group()))) {
          return KEPT_RESOLVABLE_SNAPSHOT;
        }
        return everything;
      };
    }

    MavenKeepClosure.Closed closed = (MavenKeepClosure.Closed) closure;
    Map<String, String> reached = closed.reached();
    Set<String> beltIdentities = new HashSet<>();
    for (GcCandidate belted : OwnArtifactsStrategy.lastReleasesPerGroup(candidates, this)) {
      beltIdentities.add(belted.identity());
    }
    GcPinned keeps =
        candidate -> {
          String byManifest = pins.pinsMavenCoordinate(candidate.identity());
          if (byManifest != null) {
            return byManifest;
          }
          String byClosure = reached.get(candidate.identity());
          if (byClosure != null) {
            return byClosure;
          }
          if (unreadable(candidate)) {
            return KEPT_UNREADABLE_PATH;
          }
          return candidate.identity().equals(newestPerLine.get(candidate.group()))
              ? KEPT_RESOLVABLE_SNAPSHOT
              : null;
        };
    if (closed.deadEnds().isEmpty()) {
      return keeps;
    }
    // A dead end is always kept by something — it was reached, or it is a seed — and its line
    // carries the note. A belt seed is spelled with the belt's own sentence, because the engine
    // would only have said that much and the note has to ride on the line that keeps it.
    return candidate -> {
      String rule = keeps.pinnedBy(candidate);
      if (!closed.deadEnds().contains(candidate.identity())) {
        return rule;
      }
      if (rule == null && beltIdentities.contains(candidate.identity())) {
        rule = OwnArtifactsStrategy.KEPT_RELEASE;
      }
      return rule == null ? null : rule + "; " + MavenKeepClosure.NOT_XML;
    };
  }

  /** Whether a candidate is a row this layout could not parse — see {@link #groupOf}. */
  private static boolean unreadable(GcCandidate candidate) {
    return candidate.group().equals(candidate.repository());
  }

  /**
   * The closure's view of this store: each coordinate's {@code .pom} and its SBOM, located once per
   * plan and read only when the walk reaches the coordinate.
   *
   * <p>The pom is the file a client would resolve for the coordinate — {@code
   * <artifactId>-<version>.pom} in its version directory, the timestamped spelling for a
   * timestamped snapshot — read from the rows rather than composed, because {@link #identityOf} is
   * already the one place that translation is written. The SBOM is the {@code maven} document whose
   * {@code packageName} is {@code groupId:artifactId} and whose version is the coordinate's, in any
   * {@code sboms} repository.
   */
  private MavenKeepClosure.Documents documents(List<GcCandidate> candidates) {
    Map<String, String> poms = new HashMap<>();
    Set<String> names = new LinkedHashSet<>();
    for (GcCandidate candidate : candidates) {
      names.add(candidate.repository());
    }
    for (String repository : names) {
      for (MavenArtifact row :
          artifacts.<MavenArtifact>list("repository = ?1 and path like ?2", repository, "%.pom")) {
        MavenLayout.ArtifactPath parsed = MavenLayout.parse(row.path);
        if (parsed == null) {
          continue;
        }
        String identity = identityOf(parsed, row.path);
        if (parsed.file().equals(parsed.artifactId() + "-" + versionOf(identity) + ".pom")) {
          poms.putIfAbsent(identity, row.blobId);
        }
      }
    }
    Map<String, String> sboms = new HashMap<>();
    for (SbomDocument row : sbomDocuments.<SbomDocument>list("packageType = ?1", "maven")) {
      sboms.putIfAbsent(row.packageName + ":" + row.version, row.blobId);
    }
    return new MavenKeepClosure.Documents() {
      @Override
      public byte[] pom(String coordinate) throws IOException {
        return read(poms.get(coordinate));
      }

      @Override
      public byte[] sbom(String coordinate) throws IOException {
        return read(sboms.get(coordinate));
      }
    };
  }

  /** One blob's bytes, null for no blob; a blob that cannot be read whole is a throw. */
  private byte[] read(String blobId) throws IOException {
    if (blobId == null) {
      return null;
    }
    try (InputStream bytes = blobs.open(blobId)) {
      byte[] read = bytes.readNBytes(MavenKeepClosure.MAX_DOCUMENT_BYTES + 1);
      if (read.length > MavenKeepClosure.MAX_DOCUMENT_BYTES) {
        throw new IOException(
            "blob " + blobId + " is larger than " + MavenKeepClosure.MAX_DOCUMENT_BYTES + " bytes");
      }
      return read;
    }
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
