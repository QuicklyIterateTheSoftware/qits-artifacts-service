package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.DaemonBinariesProfile;
import eu.wohlben.qits.artifacts.control.MavenLayout;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.control.SbomRegistryCollection;
import eu.wohlben.qits.artifacts.entity.DaemonBinary;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.entity.SbomDocument;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.persistence.DaemonBinaryRepository;
import eu.wohlben.qits.artifacts.persistence.MavenArtifactRepository;
import eu.wohlben.qits.artifacts.persistence.NpmVersionRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import eu.wohlben.qits.artifacts.persistence.SbomDocumentRepository;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Published software bills of materials, as facts: one stored <b>document</b> is one identity, its
 * coordinate is the {@code SoftwareRelease} coordinate, and one blob carries it.
 *
 * <h2>A document is the identity, and it is the release's own name</h2>
 *
 * <p>{@link #enumerate} emits one candidate per {@code sbom_document} row, spelled {@code
 * packageType/packageName@version} — the three values the release event travels, in the order the
 * wire spells them at {@code /artifacts/sboms/{packageType}/{packageName}/-/{version}}. A report
 * line is therefore looked up without translating anything.
 *
 * <p>The version comes <b>last</b> for one reason: a package name contains {@code @} (the scoped
 * npm spelling {@code @qits/ui-components}), {@code /} ({@code qits/qits-artifacts}) and {@code :}
 * ({@code eu.wohlben.qits:qits-eventstream}), so no leading delimiter is safe — but a version cannot
 * contain an {@code @} at all: {@code SbomPaths}' {@code VERSION} charset is
 * {@code [A-Za-z0-9][A-Za-z0-9._+-]*}. So the LAST {@code @} separates them, always, and {@link
 * #versionOf} is the only place that is spelled.
 *
 * <p>A candidate's blob set is the one blob the row names. Documents dedupe globally like every
 * other byte here — two identical SBOMs are one blob — and the retained-set union is what keeps the
 * shared bytes when only one of the two rows dies.
 *
 * <h2>A release is a calver row, and a sha row is not one</h2>
 *
 * <p>{@link GcCandidate#released()} answers {@code CALVER.matches(version)}, the same per-type
 * distinction the docs type draws. The release pipelines publish calver versions and their last step
 * is the SBOM PUT, so a calver document is the bill of materials of a real release. Anything else —
 * a per-commit sha, a local experiment, a prerelease spelling — is a working artifact: it never
 * occupies a belt slot and lives exactly as long as the access window keeps it, from {@code
 * max(created_at, accessed_at)}, so a freshly published document is always young.
 *
 * <h2>A release's document lives as long as the release does</h2>
 *
 * <p>{@link #pinnedBy} keeps a calver document while the artifact it describes is still present in
 * this store — the maven coordinate, the npm version, the image tag, the daemon version — under
 * {@link #KEPT_ARTIFACT_PRESENT}. The maven and npm adapters each keep whatever their closure
 * reaches — a closure each reads partly out of these very documents, so a kept jar's or tarball's
 * SBOM is an input to the rule as well as a statement about the artifact. Without this rule
 * the belt and a {@code P0D} window left jars whose bill of materials answered
 * {@code 404}: {@code eu.wohlben.qits:qits-service-mock:2026.917.65806} was published with one on
 * 2026-09-17 and had lost it a few releases later. An SBOM is a statement about an artifact; one
 * that outlives its subject and one that dies before it are both wrong, and the second was
 * happening.
 *
 * <p><b>This replaced "nothing pins an SBOM", whose premise was false twice.</b> It held that
 * qits-maintenance re-reads every live artifact's document on its scan cadence, so "still tracked"
 * would reach this engine as access. Maintenance reads a document exactly <b>once</b>, at ingest;
 * and the window is {@code P0D}, so no amount of access would have kept anything anyway. The fact
 * the old paragraph wanted to arrive as a timestamp is answered here directly, from the rows of the
 * stores themselves.
 *
 * <p>Presence is read once per run, one query per distinct described package rather than one per
 * document, so a plan is judged against a single reading of each sibling store. A document whose
 * artifact is gone — collected by its own type, or never stored here — keeps today's belt and
 * window behaviour unchanged, and a non-calver document is never asked about.
 *
 * <p>The <b>digest</b> floor still applies, as it does to every own type: {@code OwnGcStrategy}
 * checks pinned blob digests under whatever this adapter answers, and a document's bytes are an
 * ordinary deduplicated blob that something else may pin.
 *
 * <h2>Effective access</h2>
 *
 * <p>{@code max(created_at, accessed_at)} — V3's column, moved by a GET and coalesced hourly by
 * {@code SbomAccessTracker}, so a scan that reads a hundred documents writes a hundred rows once an
 * hour rather than on every request. Publication counts as the first access, which is what makes a
 * document published minutes ago read as young rather than as never-read.
 */
@Singleton
public class SbomGcAdapter implements GcTypeAdapter {

  /** The separator between a document's coordinate and its version, as the identity spells it. */
  static final String AT = "@";

  /** A calver release version: {@code <year>.<month><day>.<time>}. */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  /**
   * The keep a release's document gets while the artifact it describes is still here, said in full
   * so a report line explains itself without the class javadoc.
   */
  static final String KEPT_ARTIFACT_PRESENT =
      "the artifact this bill of materials describes is still in this store — a document lives as"
          + " long as its release does, so a consumer of a kept jar, tarball, image or binary can"
          + " always read what is in it";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject SbomDocumentRepository documents;
  @Inject SbomRegistryCollection sboms;
  @Inject MavenArtifactRepository mavenArtifacts;
  @Inject NpmVersionRepository npmVersions;
  @Inject OciTagRepository ociTags;
  @Inject DaemonBinaryRepository daemonBinaries;

  @Override
  public String type() {
    return SbomProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!SbomProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (SbomDocument row : documents.<SbomDocument>list("repository = ?1", repository.name)) {
        candidates.add(
            new GcCandidate(
                row.repository,
                row.packageType + "/" + row.packageName + AT + row.version,
                // The belt counts per PACKAGE, not per repository: every release of every artifact
                // on the platform lands in the one `sboms` root, so a group of "the repository"
                // would let a busy package spend a quiet one's slots within days.
                row.repository + "/" + row.packageType + "/" + row.packageName,
                // A calver row is a release; anything else is a working artifact that lives on the
                // access window alone. See the class javadoc.
                CALVER.matcher(row.version).matches(),
                latest(row.createdAt, row.accessedAt),
                Set.of(row.blobId)));
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Every calver document whose artifact is still present in its own store.
   *
   * <p>The released candidates are grouped by {@code packageType/packageName} first, so each
   * sibling store is asked once per described package — every version of it in one read — rather
   * than once per document. A package type this store does not hold answers nothing and keeps
   * nothing.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, Set<String>> wanted = new TreeMap<>();
    for (GcCandidate candidate : candidates) {
      if (!candidate.released()) {
        continue;
      }
      String identity = candidate.identity();
      String described = packageTypeOf(identity) + "/" + packageNameOf(identity);
      wanted.computeIfAbsent(described, key -> new HashSet<>()).add(versionOf(identity));
    }
    Set<String> present = new HashSet<>();
    List<ArtifactRepository> stores = repositories.listAll();
    for (String described : wanted.keySet()) {
      int slash = described.indexOf('/');
      String packageType = described.substring(0, slash);
      String packageName = described.substring(slash + 1);
      for (String version : presentVersions(stores, packageType, packageName)) {
        present.add(described + AT + version);
      }
    }
    return candidate ->
        candidate.released() && present.contains(candidate.identity())
            ? KEPT_ARTIFACT_PRESENT
            : null;
  }

  /**
   * The versions of one described package its own store holds, across every repository of that
   * store's hosted type.
   *
   * <ul>
   *   <li>{@code maven} — {@code group:artifact}; any {@code maven_artifact} row under {@code
   *       <group path>/<artifact>/<version>/}, read with one prefix query and parsed by {@code
   *       MavenLayout} so a {@code LIKE} wildcard or a longer artifactId sharing the prefix cannot
   *       answer for it.
   *   <li>{@code npm} — any {@code npm_version} row of that name.
   *   <li>{@code docker} — {@code <repository>/<image>}, the full image name every image pin
   *       spells; any {@code oci_tag} row of that image whose tag is the version.
   *   <li>{@code daemon} — any {@code daemon_binary} row of that name.
   * </ul>
   */
  private Set<String> presentVersions(
      List<ArtifactRepository> stores, String packageType, String packageName) {
    Set<String> versions = new HashSet<>();
    switch (packageType) {
      case "maven" -> {
        int colon = packageName.indexOf(':');
        if (colon < 0) {
          return versions;
        }
        String groupId = packageName.substring(0, colon);
        String artifactId = packageName.substring(colon + 1);
        // The repository method appends the closing `/%` itself.
        String prefix = groupId.replace('.', '/') + "/" + artifactId;
        for (String repository : named(stores, MavenPackagesProfile.KEY)) {
          for (Object[] row :
              mavenArtifacts.listPathsAndCreatedAtStartingWith(repository, prefix)) {
            MavenLayout.ArtifactPath parsed = MavenLayout.parse((String) row[0]);
            if (parsed != null
                && parsed.groupId().equals(groupId)
                && parsed.artifactId().equals(artifactId)) {
              versions.add(parsed.version());
            }
          }
        }
      }
      case "npm" -> {
        for (String repository : named(stores, NpmPackagesProfile.KEY)) {
          for (Object[] row : npmVersions.listVersionRows(repository, packageName)) {
            versions.add((String) row[0]);
          }
        }
      }
      case "docker" -> {
        int slash = packageName.indexOf('/');
        if (slash < 0) {
          return versions;
        }
        String repository = packageName.substring(0, slash);
        if (named(stores, OciImagesProfile.KEY).contains(repository)) {
          for (OciTag tag : ociTags.listByImage(repository, packageName.substring(slash + 1))) {
            versions.add(tag.tag);
          }
        }
      }
      case "daemon" -> {
        for (String repository : named(stores, DaemonBinariesProfile.KEY)) {
          for (DaemonBinary row : daemonBinaries.listVersions(repository, packageName)) {
            versions.add(row.version);
          }
        }
      }
      default -> {
        // A type SbomPaths admits and no store here holds: nothing to be present in.
      }
    }
    return versions;
  }

  /** The names of the repositories of one stored type. */
  private static List<String> named(List<ArtifactRepository> stores, String type) {
    return stores.stream()
        .filter(store -> type.equals(store.type))
        .map(store -> store.name)
        .toList();
  }

  /**
   * Oldest first: calver versions by their numeric parts, everything else below them by {@code
   * lastAccessAt}; ties on the identity so a report is stable across runs.
   *
   * <p>With {@code released = isCalver} the non-calver rungs are unreachable from the belt — the
   * engine sorts released candidates only — but the comparator must stay total and sane: if the
   * release meaning ever changes here, "oldest" must not quietly mean "smallest string". {@code
   * lastAccessAt} is the honest age the candidate actually carries for a non-calver version.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return (left, right) -> {
      String leftVersion = versionOf(left.identity());
      String rightVersion = versionOf(right.identity());
      boolean leftCalver = CALVER.matcher(leftVersion).matches();
      boolean rightCalver = CALVER.matcher(rightVersion).matches();
      if (leftCalver != rightCalver) {
        return leftCalver ? 1 : -1; // every calver release ranks above (newer than) any other row
      }
      int compared =
          leftCalver
              ? BY_VERSION.compare(leftVersion, rightVersion)
              : left.lastAccessAt().compareTo(right.lastAccessAt());
      return compared != 0 ? compared : left.identity().compareTo(right.identity());
    };
  }

  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      String packageType = packageTypeOf(dead.identity());
      String packageName = packageNameOf(dead.identity());
      String version = versionOf(dead.identity());
      try {
        SbomDocument row =
            documents.findOne(dead.repository(), packageType, packageName, version).orElse(null);
        if (row == null) {
          errors.add(dead.identity() + ": no such sbom row — the store moved since planning");
          continue;
        }
        // A document whose blob is still inside the grace window is withheld whole: deleting the row
        // over it would strand the blob as row-less and therefore untouchable forever, which is the
        // failure the window exists to prevent.
        if (grace.withinGrace(row.blobId)) {
          withheld.add(dead);
          continue;
        }
        sboms.collect(dead.repository(), packageType, packageName, version);
        deleted.add(dead);
      } catch (RuntimeException failed) {
        errors.add(dead.identity() + ": " + failed.getMessage());
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** The declared artifact type, up to the first {@code /} — {@code npm|maven|docker|daemon}. */
  private static String packageTypeOf(String identity) {
    return identity.substring(0, identity.indexOf('/'));
  }

  /**
   * The declared name, between the first {@code /} and the LAST {@code @}. It may itself contain
   * both — {@code @qits/ui-components} carries an {@code @} at its front and a {@code /} in its
   * middle — which is why neither delimiter is searched for from the side the name is on.
   */
  private static String packageNameOf(String identity) {
    return identity.substring(identity.indexOf('/') + 1, identity.lastIndexOf(AT));
  }

  /**
   * A package name may contain an {@code @} — {@code @qits/ui-components} begins with one — so the
   * <b>last</b> separates it from the version. A version cannot contain one: {@code SbomPaths}'
   * {@code VERSION} charset does not admit it.
   */
  private static String versionOf(String identity) {
    return identity.substring(identity.lastIndexOf(AT) + 1);
  }

  /** Publication counts as the first access, so a document stored minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  /**
   * Version order, oldest first: calver versions by their three numeric parts, anything else below
   * them with a lexical tie-break so the order stays total.
   *
   * <p>Deliberately <b>not</b> {@code DaemonBinariesGcAdapter.BY_VERSION}, which it otherwise
   * resembles. That comparator carries a third rung for the adopted rows whose version is the blob's
   * own digest hex — a shape only the daemon type has, from a one-off ops adoption. An SBOM's
   * version is whatever version the artifact it describes was released under, so borrowing it would
   * import a rank that can never fire and a javadoc that explains something untrue of this type.
   * Version order is a per-type fact, which is why {@link GcTypeAdapter#byAge} is on the adapter
   * rather than in an engine.
   */
  static final Comparator<String> BY_VERSION =
      (left, right) -> {
        boolean leftCalver = CALVER.matcher(left).matches();
        boolean rightCalver = CALVER.matcher(right).matches();
        if (leftCalver != rightCalver) {
          return leftCalver ? 1 : -1;
        }
        if (!leftCalver) {
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
}
