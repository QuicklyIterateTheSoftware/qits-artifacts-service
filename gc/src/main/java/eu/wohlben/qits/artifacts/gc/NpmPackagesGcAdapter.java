package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.NpmRegistryCollection;
import eu.wohlben.qits.artifacts.control.NpmSemver;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.ContentHashRepository;
import eu.wohlben.qits.artifacts.persistence.NpmDistTagRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.control.BlobStore;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The platform's own published packages, as facts: what a coordinate is, what a release is, which of
 * two releases is newer, what a dist-tag holds, and how a row goes.
 *
 * <h2>What a release is here</h2>
 *
 * <p>A version with <b>no prerelease part</b>. Consumers pin ranges, so {@code ^2026.801.85149} has
 * to keep resolving — including to the pre-calver {@code 0.0.x} line.
 *
 * <h2>A release lives while something kept still needs it</h2>
 *
 * <p>Since 2026-10-03 (qits-740) a published release is collected again, and only when nothing the
 * keep-set holds reaches it. In order, so a receipt names the strongest reason a version has:
 *
 * <ol>
 *   <li><b>A manifest pin</b> — a manifest on some repository's main resolves to the version
 *       ({@link GcPins#pinsNpmCoordinate}). Through qits-maintenance (qits-740) that includes the
 *       lockfiles reached through a service's <b>frontend submodule gitlink</b>, which is what
 *       closes the hole 2026-09-05 fell through (below).
 *   <li><b>The closure</b> — the version is reached from the seeds by {@link NpmKeepClosure}: a
 *       {@code dependencies}, {@code peerDependencies} or {@code optionalDependencies} range of a
 *       reached version resolves to it, or the stored SBOM of one names it. The seeds are the
 *       manifest pins, the newest {@link OwnArtifactsStrategy#RELEASES_KEPT} releases of every
 *       package and every dist-tag target; the walk runs to a fixpoint, and the receipt names the
 *       version that reached it.
 *   <li><b>A dist-tag</b> names it (below).
 *   <li><b>The belt</b> — the newest {@link OwnArtifactsStrategy#RELEASES_KEPT} releases of every
 *       package, kept by the engine because nothing here answers for them first.
 * </ol>
 *
 * <p>Everything else — a release included — is condemned under the configured window ({@code P0D})
 * and then withheld by the sweep's six-hour blob grace, which is what still protects a version
 * published minutes ago whose consumer has not folded yet. If the closure cannot be completed — a
 * reached version's manifest or SBOM unreadable or unparseable, a dependency spec on a hosted
 * package this cannot resolve — {@link #pinnedBy} keeps <b>every</b> npm identity that run under
 * {@link #failClosed}, naming the version and the reason on every line.
 *
 * <h2>2026-09-05, and how each of its reasons is answered now</h2>
 *
 * <p>Releases used to be kept as the last two per package, with older ones surviving on
 * <em>use</em>: a lockfile install moves {@code npm_version.accessed_at}. On <b>2026-09-05</b> the
 * windows went to {@code P0D} — access decides nothing at all now — and the same evening the sweep
 * took {@code @qits/ui-components} down to three versions and {@code @qits/angular} to two. Fifteen
 * frontend lockfiles pinned one of the versions it took; the release runs of two services died on
 * {@code npm ci} with {@code E404}, and two more frontends could not cut a release at all. Releases
 * were then kept forever, and the argument was written here. Its reasons were sound against that
 * rule; what changed is that each now has a keep of its own rather than an age:
 *
 * <ul>
 *   <li><b>An install is not a fetch of this registry.</b> A tarball is downloaded once and served
 *       from {@code node_modules}, a warm cache and every baked build image thereafter, so access
 *       was never evidence. It is still not: nothing here keeps a version on access. What keeps one
 *       is being <em>named</em> — by a manifest pin, by a kept version's dependencies or SBOM, or by
 *       a dist-tag.
 *   <li><b>The pin sources could not see an npm pin.</b> A frontend's lockfile is not on the
 *       service's main — it is reached through a submodule gitlink that a release tag freezes, and
 *       fifteen services' gitlinks named frontend commits whose locks pinned five different
 *       versions of {@code @qits/angular}. qits-maintenance now follows that gitlink and reports the
 *       lockfile it finds as the service's own manifest pins (qits-740), so those versions arrive
 *       here as {@link GcPins#BY_MANIFEST} like any other.
 *   <li><b>Transitive needs.</b> A pinned version's own dependencies are kept with it through the
 *       closure, by range and by its SBOM's exact versions, so keeping a consumer's direct pin can
 *       never strand what that pin installs.
 *   <li><b>A collection used to be irreversible.</b> The tombstone once refused even an identical
 *       republish; it has since narrowed to refusing different bytes, so a mistaken sweep is
 *       answered by republishing the same tarball rather than by editing every consumer.
 * </ul>
 *
 * <p><b>Prereleases and non-semver versions</b> are not releases: they never occupy a belt slot and
 * age out on the window unless a pin, the closure or a dist-tag names them — the {@code
 * -main.g<sha>} a push publishes is npm's analogue of maven's timestamped snapshots, and {@code
 * @main} resolves through a dist-tag.
 *
 * <p>Newer is <b>semver precedence</b> ({@link NpmSemver}), not a row timestamp and not insertion
 * order: {@code 2026.801.85149} outranks {@code 2026.801.63140} whichever was published first, and a
 * republish-after-collection could not reorder the belt if it tried.
 *
 * <p>A version that does not parse as semver cannot be ordered and is therefore never a release. It
 * is not thereby condemned — it ages out on access like any other non-release coordinate, which is a
 * narrower answer than the old unmodelled-means-keep-forever and a wider one than deleting it for
 * being unrecognised.
 *
 * <h2>The dist-tag keep</h2>
 *
 * <p>{@link #pinnedBy} keeps anything a dist-tag currently names, and seeds the closure with it. It
 * is a pin in the exact sense the keep-class means: a live pointer something outside this rule
 * resolves through, and a packument whose {@code dist-tags} names a version its {@code versions}
 * does not list is a broken package to every npm client — which is also why {@code
 * NpmRegistryCollection.collect} refuses one.
 *
 * <h2>Scope, and the one mistake this type can make</h2>
 *
 * <p>{@code npm_version} holds hosted and proxied rows in one table, so the enumeration filters by
 * the <b>repository row's type</b>. Getting that wrong would put upstream's cached content under the
 * platform's own keep rules, or the platform's own packages under a cache's eviction; both
 * suites assert the scope from their own side.
 *
 * <p>Deletion goes through {@code NpmRegistryCollection.collect}, which writes the republish
 * tombstone <b>in the same transaction</b> and refuses a version a dist-tag still names. Both
 * guarantees are the mechanism's, so no path around them exists to forget.
 *
 * <p><b>This type needs no whole-or-nothing repair.</b> The half-collected version {@code
 * maven-packages} had to be fixed the same day cannot occur here: an npm identity is one row naming
 * one tarball, and {@code collect} removes that row and writes its tombstone inside a single
 * transaction. There is no per-file loop to leave half-applied — {@link #delete} iterates
 * <em>identities</em>, and one identity is one atomic call.
 */
@Singleton
public class NpmPackagesGcAdapter implements GcTypeAdapter {

  /** What every npm identity is kept under on a run whose closure could not be completed. */
  static String failClosed(String coordinate, String reason) {
    return "npm collects nothing this run: "
        + coordinate
        + ": "
        + reason
        + ". A partial closure is never a deletion, so every npm identity is kept";
  }

  /** The belt-and-braces keep, naming the tag so a reviewer can see which pointer saved a version. */
  static String keptByDistTag(String tag) {
    return "the " + tag + " dist-tag names this version";
  }

  @Inject ArtifactRepositoryRepository repositories;
  @Inject NpmVersionRepository versions;
  @Inject NpmDistTagRepository distTags;
  @Inject NpmRegistryCollection npm;
  @Inject ContentHashRepository contentHashes;
  @Inject SbomDocumentRepository sbomDocuments;

  /** How the closure reads an SBOM's bytes; read-only, and it moves no access timestamp. */
  @Inject BlobStore blobs;

  @Override
  public String type() {
    return NpmPackagesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!NpmPackagesProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (String packageName : versions.listPackageNames(repository.name)) {
        for (Object[] row : versions.listVersionRows(repository.name, packageName)) {
          String version = (String) row[0];
          candidates.add(
              new GcCandidate(
                  repository.name,
                  packageName + "@" + version,
                  // The belt counts per package of one repository: two packages must not spend each
                  // other's slots, and neither must two registries holding the same name.
                  repository.name + "/" + packageName,
                  NpmSemver.parse(version).filter(parsed -> !parsed.isPrerelease()).isPresent(),
                  latest((Instant) row[2], (Instant) row[3]),
                  Set.of((String) row[1])));
        }
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Every keep this type has, in the order the class javadoc gives: the manifest pin, the closure,
   * and the dist-tag. The release belt is the engine's and is asked after this answers null.
   *
   * <p>The dist-tags are read once per run over the packages the enumeration touched, rather than
   * per candidate: a plan judged against two readings of {@code npm_dist_tag} could condemn a
   * version the second reading had just tagged.
   *
   * <p><b>The dependency pin needs no translation</b>, which is what makes it safe: {@code
   * name@version} is what a lockfile resolves to and it is this adapter's identity verbatim, so the
   * lookup is an equality test on the string the enumeration already built. It is asked first
   * because a consumer still building against a version is a stronger thing to report than the
   * closure edge that would have kept it anyway.
   *
   * <p><b>The closure is computed once per plan</b>, here, over the enumeration the binder already
   * took and the pins the run already read, for the reason {@code MavenPackagesGcAdapter} gives. The
   * belt seeds are asked of {@link OwnArtifactsStrategy#lastReleasesPerGroup} rather than
   * re-derived, so the seeds and the belt the engine applies are one answer.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> tagged = new HashMap<>();
    Map<String, Map<String, String>> tagsByPackage = new HashMap<>();
    for (String group : groupsOf(candidates)) {
      // A repository name cannot contain a slash and a scoped package name can, so the FIRST one is
      // the boundary the group was built with.
      int slash = group.indexOf('/');
      String repository = group.substring(0, slash);
      String packageName = group.substring(slash + 1);
      for (NpmDistTag tag : distTags.listTags(repository, packageName)) {
        tagged.putIfAbsent(repository + "/" + packageName + "@" + tag.version, tag.tag);
        tagsByPackage
            .computeIfAbsent(packageName, name -> new HashMap<>())
            .putIfAbsent(tag.tag, tag.version);
      }
    }

    Set<String> hosted = new LinkedHashSet<>();
    Map<String, String> repositoryOf = new HashMap<>();
    Set<String> seeds = new LinkedHashSet<>();
    for (GcCandidate candidate : candidates) {
      hosted.add(candidate.identity());
      repositoryOf.putIfAbsent(candidate.identity(), candidate.repository());
      if (pins.pinsNpmCoordinate(candidate.identity()) != null
          || tagged.containsKey(candidate.group() + "@" + versionOf(candidate.identity()))) {
        seeds.add(candidate.identity());
      }
    }
    for (GcCandidate belted : OwnArtifactsStrategy.lastReleasesPerGroup(candidates, this)) {
      seeds.add(belted.identity());
    }

    NpmKeepClosure.Result closure =
        NpmKeepClosure.from(seeds, hosted, documents(repositoryOf, tagsByPackage));

    if (closure instanceof NpmKeepClosure.Incomplete incomplete) {
      String everything = failClosed(incomplete.coordinate(), incomplete.reason());
      Log.warnf("gc: %s", everything);
      return candidate -> {
        String byManifest = pins.pinsNpmCoordinate(candidate.identity());
        return byManifest != null ? byManifest : everything;
      };
    }

    Map<String, String> reached = ((NpmKeepClosure.Closed) closure).reached();
    return candidate -> {
      String byManifest = pins.pinsNpmCoordinate(candidate.identity());
      if (byManifest != null) {
        return byManifest;
      }
      String byClosure = reached.get(candidate.identity());
      if (byClosure != null) {
        return byClosure;
      }
      String tag = tagged.get(candidate.group() + "@" + versionOf(candidate.identity()));
      return tag == null ? null : keptByDistTag(tag);
    };
  }

  /**
   * The closure's view of this store: a version's manifest as it was published, its {@code npm}
   * SBOM in any {@code sboms} repository, and the dist-tags this run already read.
   *
   * <p>The manifest is the {@code npm_version.manifest_json} column — the version's {@code
   * package.json} as it arrived at publish, {@code dependencies} and all — read by a projection
   * query and only when the walk reaches the version, so a plan never drags every manifest through
   * the JVM. An SBOM is read from its blob, exactly as {@code MavenPackagesGcAdapter} reads one.
   */
  private NpmKeepClosure.Documents documents(
      Map<String, String> repositoryOf, Map<String, Map<String, String>> tagsByPackage) {
    Map<String, String> sboms = new HashMap<>();
    for (SbomDocument row : sbomDocuments.<SbomDocument>list("packageType = ?1", "npm")) {
      sboms.putIfAbsent(row.packageName + "@" + row.version, row.blobId);
    }
    return new NpmKeepClosure.Documents() {
      @Override
      public byte[] manifest(String coordinate) throws IOException {
        String repository = repositoryOf.get(coordinate);
        if (repository == null) {
          return null;
        }
        List<String> found =
            versions
                .getEntityManager()
                .createQuery(
                    "select v.manifestJson from NpmVersion v where v.repository = :repository"
                        + " and v.packageName = :packageName and v.version = :version",
                    String.class)
                .setParameter("repository", repository)
                .setParameter("packageName", NpmKeepClosure.packageOf(coordinate))
                .setParameter("version", NpmKeepClosure.versionOf(coordinate))
                .getResultList();
        if (found.isEmpty() || found.get(0) == null) {
          return null;
        }
        byte[] bytes = found.get(0).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > NpmKeepClosure.MAX_DOCUMENT_BYTES) {
          throw new IOException(
              "manifest is larger than " + NpmKeepClosure.MAX_DOCUMENT_BYTES + " bytes");
        }
        return bytes;
      }

      @Override
      public byte[] sbom(String coordinate) throws IOException {
        String blobId = sboms.get(coordinate);
        if (blobId == null) {
          return null;
        }
        try (InputStream bytes = blobs.open(blobId)) {
          byte[] read = bytes.readNBytes(NpmKeepClosure.MAX_DOCUMENT_BYTES + 1);
          if (read.length > NpmKeepClosure.MAX_DOCUMENT_BYTES) {
            throw new IOException(
                "blob " + blobId + " is larger than " + NpmKeepClosure.MAX_DOCUMENT_BYTES + " bytes");
          }
          return read;
        }
      }

      @Override
      public String distTag(String packageName, String tag) {
        return tagsByPackage.getOrDefault(packageName, Map.of()).get(tag);
      }
    };
  }

  /**
   * Oldest release first, by semver precedence; ties on the identity so a report is stable across
   * runs.
   *
   * <p>Only released candidates reach this comparator, and a release parses by definition — but it
   * stays total anyway: a version that cannot be ordered sorts below every version that can, so an
   * unorderable coordinate can never displace a real release off the belt.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> versionOf(candidate.identity()), BY_PRECEDENCE)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * Each dead version through {@code NpmRegistryCollection.collect}, one transaction per row so a
   * refusal takes down its own identity and nothing else.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      // A scoped package starts with '@', so the LAST '@' is the separator; a version has none.
      int at = dead.identity().lastIndexOf('@');
      String packageName = dead.identity().substring(0, at);
      String version = dead.identity().substring(at + 1);
      try {
        NpmVersion row = versions.findOne(dead.repository(), packageName, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such version row — the store moved since planning");
          continue;
        }
        if (grace.withinGrace(row.tarballBlobId)) {
          withheld.add(dead);
          continue;
        }
        // One transaction for the version, its tombstone and its content hash: collect() joins it,
        // and a content_hash row left behind would describe a version this store no longer serves.
        QuarkusTransaction.requiringNew()
            .run(
                () -> {
                  npm.collect(dead.repository(), packageName, version);
                  contentHashes.deleteOne(dead.repository(), "npm", packageName, version);
                });
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** Every identity group the enumeration touched, in encounter order. */
  private static List<String> groupsOf(List<GcCandidate> candidates) {
    Map<String, Boolean> groups = new LinkedHashMap<>();
    for (GcCandidate candidate : candidates) {
      groups.putIfAbsent(candidate.group(), true);
    }
    return List.copyOf(groups.keySet());
  }

  /** A scoped package starts with {@code @}, so the LAST one separates it from the version. */
  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf('@') + 1);
  }

  /** Publication counts as the first access, so a version published minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  /** Semver precedence, lowest first, with anything unparseable below everything that parses. */
  static final Comparator<String> BY_PRECEDENCE =
      (left, right) -> {
        Optional<NpmSemver> parsedLeft = NpmSemver.parse(left);
        Optional<NpmSemver> parsedRight = NpmSemver.parse(right);
        if (parsedLeft.isEmpty() || parsedRight.isEmpty()) {
          return parsedLeft.isPresent() == parsedRight.isPresent()
              ? left.compareTo(right)
              : (parsedLeft.isPresent() ? 1 : -1);
        }
        return parsedLeft.get().compareTo(parsedRight.get());
      };
}
