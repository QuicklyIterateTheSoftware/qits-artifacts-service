package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.OciManifestFootprints;
import eu.wohlben.qits.artifacts.control.OciRegistryCollection;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.blobstore.persistence.ArtifactRepositoryRepository;
import eu.wohlben.qits.artifacts.persistence.OciManifestRepository;
import eu.wohlben.qits.artifacts.persistence.OciTagRepository;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The platform's own images, as facts: what a coordinate is, what a release is, which of two
 * releases is newer, what qits-platform-deployments would pull, and how a row goes.
 *
 * <h2>What a release is here</h2>
 *
 * <p>A <b>calver tag</b>. In docker there is no {@code -main.g<sha>} suffix — the sha tag <em>is</em>
 * the prerelease coordinate and a release adds a version tag beside it — so "the last 2 released
 * versions of every image" means exactly "the two newest tags shaped like a calver version". Newer
 * is the version's own order ({@link #BY_CALVER}) rather than a row timestamp, because a release
 * pulled last week is not thereby a newer release than one cut yesterday.
 *
 * <h2>The two belts this type derives itself: the newest RELEASE tag, and {@code latest}</h2>
 *
 * <p>{@link #pinnedBy} keeps every coordinate the five image pin sources name <b>and</b> each
 * image's newest calver tag. The second is a pin in all but name — the pull the <em>next</em> deploy
 * will make — and it is here rather than in cd because cd cannot answer for a deployment that has
 * not happened: {@code qits-spa-home} has tags and not a single deployment row, and without this
 * line the next deploy could pull a tag this run deleted. That is the measured {@code
 * IMAGE_MISSING} hazard, and it is one line.
 *
 * <p>The other is {@link #KEPT_LATEST}: a tag literally named {@code latest} is always kept. It is a
 * pointer rather than a coordinate, no release rule can see it, and the pulls that would keep it warm
 * are the ones a host's keep-prefix suppresses. Its own constant carries the argument.
 *
 * <p><b>It reads the CALVER tag and not the sha tag, and that flip is the whole of 2026-09-04.</b>
 * This belt used to name each image's most recently written <em>build sha</em>, because that is what
 * cd pulled: a deployment was created from {@code qits/<app>:<sha>} and the next one would be
 * created from the newest sha the store held. Deployments are made by VERSION COORDINATE now — cd
 * asks for {@code qits/<app>:<version>}, the calver the release was cut under — so the newest sha
 * is a coordinate nothing will ever pull again, and a belt spent on it protects nothing while
 * telling every reader of the report that it is protecting "the next deployment's pull target". A
 * lie in a keep-rule is worse than a missing belt: it is the line a reviewer trusts when deciding
 * whether a tag may go.
 *
 * <p><b>Newest is {@link #BY_CALVER}, the version's own order, and no timestamp at all.</b> The sha
 * form had no intrinsic order, so the old belt had to read {@code oci_tag.updated_at} and had to
 * argue at length for why it was not reading the access time (a pull of an older sha would have
 * lifted it and disarmed the belt on exactly the cold image it exists for). A calver tag carries its
 * order in its name: the next deploy pulls the highest version, whenever its row happened to be
 * written and whoever last pulled it. The argument disappears with the timestamp, and so does the
 * per-group query the old belt needed — the enumeration in hand already says which candidates are
 * releases.
 *
 * <p><b>The belt overlaps the last-2 retention today, and it is still not the same fact.</b> The
 * newest calver of a group is always among that group's two newest releases, so this rule and {@code
 * OwnArtifactsStrategy}'s keep the same tag — under different names and for different reasons.
 * Retention is archival policy ("keep the last two, whatever their age"); this is an operational
 * fact ("this exact coordinate is what the next deploy will ask the registry for"). Two independent
 * reasons for one keep is what a belt is; and the belt is the one that survives a change to
 * {@code RELEASES_KEPT}, which is a number and not a guarantee.
 *
 * <h2>Whether a released-but-undeployed version can be collected</h2>
 *
 * <p>Audited on 2026-09-04, because cd's pins cover only <em>live</em> deployments and retention
 * covers only the last two releases — so a third-newest version that was released and never deployed
 * is named by neither. It is nevertheless covered, by the rule that was already there: a candidate's
 * effective access time folds creation in ({@link #latest}), and for a tag "creation" is {@code
 * updated_at}, the moment the release pushed it. <b>Every calver tag is therefore kept for a full
 * window from its push, deployed or not</b> — which is the "minimum age before a release is
 * collectable" guard, already load-bearing and arrived at from the other direction.
 *
 * <p>What is left eligible is a version that is all four of: older than the window, unpulled for
 * every day of it, displaced from the last two releases of its image, and named by no deployment cd
 * would restore. That is "a release nobody deployed in a month, superseded twice since" — the
 * settlement's intended collection rather than a hazard, and the same sentence it already applies to
 * an old npm version nobody installs. Nothing was widened; this paragraph is the change.
 *
 * <h2>What is a candidate, and what is not</h2>
 *
 * <p>Every tag, and every manifest that <b>no tag names and no tagged manifest reaches</b>. The
 * second half is the mirror adapter's rule for the mirror adapter's reason: a tagged manifest's
 * identity is its tag, and a child of a tagged index is reached through that index's closure, so
 * neither is an identity of its own. What is left is the store's measured 73 untagged manifests — a
 * tag re-push moves the tag row to the new manifest and leaves the old row reachable from no
 * coordinate anyone uses — and they now age out on access like anything else.
 *
 * <p>A manifest under a tag this run condemns is therefore collected on the <em>next</em> run, once
 * it is untagged. One run, one class of identity; the alternative is a two-phase rule inside a
 * single plan, which is the shape the engine seam exists to refuse.
 *
 * <p><b>Effective access is {@code max(updated/created, accessed_at)}</b>, from V9's columns. A
 * tag's non-access timestamp is {@code updated_at} — the moment those bytes became this tag's — and
 * creation counting as a first access is what stops a tag pushed ten minutes ago from reading as
 * never-pulled.
 *
 * <p>Deletion runs through the registry's own funnel ({@code OciRegistryCollection}), tags before
 * manifests, exactly as {@code OciMirrorGcAdapter} does it: nothing in that funnel reads a
 * repository's type, so both OCI types have always come through the same door. The code is written
 * out twice rather than shared, which is this module's standing rule — two types may share an
 * engine the user picked for both, and they still own their own facts.
 */
@Singleton
public class OciImagesGcAdapter implements GcTypeAdapter {

  /**
   * The release coordinate: {@code <year>.<month><day>.<time>}, e.g. {@code 2026.801.85448}.
   *
   * <p>It is the only tag shape this type classifies now. There used to be a {@code BUILD_SHA}
   * pattern beside it, matched second so a version could never be read as an abbreviated digest;
   * the belt that consulted it is gone with the sha deploys it served, and no other rule here asks
   * whether a tag is a sha. A tag that is not a calver is simply not a release — it is kept for as
   * long as something pulls it and no longer, which is what the access rule already said about every
   * coordinate this pattern does not match.
   */
  static final Pattern CALVER = Pattern.compile("\\d{4}\\.\\d{1,4}\\.\\d+");

  /** The digest form the untagged-manifest identities are spelled with. */
  static final String DIGEST_PREFIX = "@sha256:";

  /**
   * The belt this type derives for itself, named so a report says which pull it protects.
   *
   * <p>It says "release" rather than "build" since 2026-09-04, and the word is the assertion: the
   * coordinate a deployment is created from is the version, so the version is the only tag this
   * sentence can truthfully be written beside.
   */
  static final String KEPT_NEWEST =
      "this image's newest release — the next deployment's pull target";

  /**
   * The second structural belt, and the one the access window cannot stand in for.
   *
   * <p>CI step recipes name their images by the moving pointer — {@code qits/build-images/*:latest}
   * — so the tag is pulled constantly and is nevertheless the coordinate most likely to be condemned
   * here. Three facts stack up against it and no existing rule catches any of them: {@code latest} is
   * not a calver, so neither the release retention nor {@link #KEPT_NEWEST} covers it; the host-side
   * keep-prefix suppresses exactly the pulls that would keep it access-warm, so {@code accessed_at}
   * under-reports it precisely where it is most used; and deleting it 404s a fresh host's first pull
   * of a step image, which fails every pipeline on that machine rather than one build.
   *
   * <p>It costs approximately nothing: {@code latest} points at the newest push's manifest, so its
   * closure is blobs that push already holds.
   */
  static final String KEPT_LATEST =
      "the moving 'latest' pointer — CI step recipes pull it by name, and a fresh host's pull 404s"
          + " without it";

  /** The one tag name that is a pointer rather than a coordinate. */
  static final String LATEST = "latest";

  @Inject ArtifactRepositoryRepository repositories;
  @Inject OciTagRepository tags;
  @Inject OciManifestRepository manifests;
  @Inject OciManifestFootprints footprints;
  @Inject OciRegistryCollection registry;

  @Override
  public String type() {
    return OciImagesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    List<GcCandidate> candidates = new ArrayList<>();
    for (ArtifactRepository repository : repositories.listAll()) {
      if (!OciImagesProfile.KEY.equals(repository.type)) {
        continue;
      }
      for (String image : manifests.listImageNames(repository.name)) {
        collect(repository.name, image, candidates);
      }
    }
    return List.copyOf(candidates);
  }

  /**
   * Four coordinate pins and two structural belts: every coordinate qits-platform-deployments holds,
   * every image a repository's Dockerfile references, every image qits-workspaces or qits-projects
   * would launch today, the moving {@code latest} pointer, and each image's newest release tag.
   *
   * <p><b>No two pin sources are the same claim.</b> The deployer names containers that exist;
   * qits-platform-maintenance names what source still builds {@code FROM}; and qits-workspaces and
   * qits-projects each name what they would pull if somebody clicked right now.
   *
   * <p>The order below is the order they are checked in and it is only a report-readability
   * decision: a tag pinned by two sources is kept once, under the first sentence, and the first is
   * the deployer because "a deployment holds it" is the answer a reviewer expects to see first.
   *
   * <p><b>Three of the four join on the FULL image name and the deployer does not.</b> A Dockerfile
   * and a launch answer both spell {@code qits/workspace-base}, while an {@code oci_tag} row carries
   * the repository and the image in two columns; cd's {@code applicationName} is the image half
   * alone, because it deploys within one repository. So the three lookups compose the name and the
   * old one does not, and conflating them would silently pin nothing.
   *
   * <p>The newest release is read straight off the enumeration in hand — no second query and no
   * timestamp. {@link #BY_CALVER} orders releases by the version itself, so nothing a pull or a
   * re-push does to a row can move this belt, which is the property the old sha belt had to buy with
   * a per-group read of {@code oci_tag.updated_at} and a paragraph explaining which timestamp it was
   * careful not to use.
   */
  @Override
  public GcPinned pinnedBy(List<GcCandidate> candidates, GcPins pins) {
    Map<String, String> newestRelease = newestReleasePerGroup(candidates);
    return candidate -> {
      if (isManifest(candidate.identity())) {
        return null;
      }
      int colon = candidate.identity().lastIndexOf(':');
      String image = candidate.identity().substring(0, colon);
      String tag = candidate.identity().substring(colon + 1);
      String byCd = pins.pinsImageTag(image, tag);
      if (byCd != null) {
        return byCd;
      }
      String fullImage = group(candidate.repository(), image);
      String byManifest = pins.pinsManifestImage(fullImage, tag);
      if (byManifest != null) {
        return byManifest;
      }
      String byWorkspaceLaunch = pins.pinsWorkspaceLaunchImage(fullImage, tag);
      if (byWorkspaceLaunch != null) {
        return byWorkspaceLaunch;
      }
      String byProjectLaunch = pins.pinsProjectLaunchImage(fullImage, tag);
      if (byProjectLaunch != null) {
        return byProjectLaunch;
      }
      if (LATEST.equals(tag)) {
        return KEPT_LATEST;
      }
      return tag.equals(newestRelease.get(candidate.group())) ? KEPT_NEWEST : null;
    };
  }

  /**
   * Oldest release first, by the calver version's own order; ties on the identity so a report is
   * stable across runs.
   *
   * <p>Only released candidates ever reach this comparator ({@link OwnArtifactsStrategy} sorts a
   * group's releases and nothing else), so it only has to be right about calver tags. It is total
   * anyway: anything it cannot read as a version sorts below everything it can, which keeps an
   * unreadable coordinate from displacing a real release off the belt.
   */
  @Override
  public Comparator<GcCandidate> byAge() {
    return Comparator.comparing(
            (GcCandidate candidate) -> tagOf(candidate.identity()), BY_CALVER)
        .thenComparing(GcCandidate::identity);
  }

  /**
   * Tags first, then manifests, and the order is load-bearing: a dead tag over a dead manifest must
   * lose its row before {@code collectManifest}'s a-tag-still-names-it belt looks. The two cannot
   * both be condemned in one run here — a tagged manifest is never a candidate — but the order costs
   * nothing and removes the question.
   */
  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    List<GcIdentity> deleted = new ArrayList<>();
    List<GcIdentity> withheld = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    for (GcIdentity dead : plan.dead()) {
      if (!isManifest(dead.identity())) {
        deleteTag(dead, grace, deleted, withheld, errors);
      }
    }
    for (GcIdentity dead : plan.dead()) {
      if (isManifest(dead.identity())) {
        deleteManifest(dead, grace, deleted, withheld, errors);
      }
    }
    return new GcStrategy.Applied(deleted, withheld, errors);
  }

  /** One image: every tag, then every manifest no kept coordinate can reach. */
  private void collect(String repository, String image, List<GcCandidate> candidates) {
    Map<String, OciManifest> byDigest = new LinkedHashMap<>();
    for (OciManifest manifest : manifests.listByImage(repository, image)) {
      byDigest.put(manifest.digest, manifest);
    }
    Set<String> tagged = new HashSet<>();
    Set<String> reachableFromTags = new HashSet<>();
    for (OciTag tag : tags.listByImage(repository, image)) {
      tagged.add(tag.manifestDigest);
      Set<String> blobs = blobsOf(byDigest.get(tag.manifestDigest));
      reachableFromTags.addAll(blobs);
      candidates.add(
          new GcCandidate(
              repository,
              image + ":" + tag.tag,
              group(repository, image),
              CALVER.matcher(tag.tag).matches(),
              latest(tag.updatedAt, tag.accessedAt),
              blobs));
    }
    for (OciManifest manifest : byDigest.values()) {
      // A tagged manifest's identity is its tag; an index child's is the index that names it.
      // Neither is an identity of its own, and enumerating one would let the window condemn a
      // manifest a live coordinate reaches.
      if (tagged.contains(manifest.digest) || reachableFromTags.contains(manifest.digest)) {
        continue;
      }
      candidates.add(
          new GcCandidate(
              repository,
              image + DIGEST_PREFIX + manifest.digest,
              group(repository, image),
              false,
              latest(manifest.createdAt, manifest.accessedAt),
              footprints.of(manifest).keySet()));
    }
  }

  /**
   * The identity group the release belt counts within: one image of one repository.
   *
   * <p>Qualified by the repository because the belt is a count and two repositories holding the same
   * image name would otherwise spend each other's slots.
   */
  private static String group(String repository, String image) {
    return repository + "/" + image;
  }

  /**
   * Each identity group's highest calver tag, in encounter order; a group with no release at all
   * contributes no entry, and an image made only of sha tags therefore has no belt.
   *
   * <p>That absence is the honest answer now, not a hole. The old sha belt existed because a
   * never-deployed image's newest sha was the coordinate the next deploy would ask for; nothing asks
   * for a sha any more, so an image that has never been released has no next-deploy pull target to
   * name, and its tags live or die on use like any other unclassified coordinate.
   *
   * <p>{@link GcCandidate#released()} is the release test rather than a second match of {@link
   * #CALVER} here: the enumeration already decided which coordinates are releases, and asking twice
   * is how the two answers drift apart.
   */
  private static Map<String, String> newestReleasePerGroup(List<GcCandidate> candidates) {
    Map<String, String> newest = new LinkedHashMap<>();
    for (GcCandidate candidate : candidates) {
      if (!candidate.released()) {
        continue;
      }
      String tag = tagOf(candidate.identity());
      String held = newest.get(candidate.group());
      if (held == null || BY_CALVER.compare(tag, held) > 0) {
        newest.put(candidate.group(), tag);
      }
    }
    return newest;
  }

  /** Everything a manifest reaches, or nothing when the row it named is already gone. */
  private Set<String> blobsOf(OciManifest manifest) {
    return manifest == null ? Set.of() : footprints.of(manifest).keySet();
  }

  /** Creation counts as the first access, so a tag pushed minutes ago reads as young. */
  private static Instant latest(Instant created, Instant accessed) {
    return accessed == null || accessed.isBefore(created) ? created : accessed;
  }

  private static boolean isManifest(String identity) {
    return identity.contains(DIGEST_PREFIX);
  }

  /** The tag half of an {@code image:tag} identity; a manifest identity has none and answers whole. */
  private static String tagOf(String identity) {
    return isManifest(identity) ? identity : identity.substring(identity.lastIndexOf(':') + 1);
  }

  /**
   * Calver order over the three numeric parts, oldest first — {@code 2026.801.85447} below {@code
   * 2026.801.85448} and {@code 2026.1201.5} above both, which a lexical comparison gets backwards.
   * Anything that is not a calver version sorts below every version that is.
   */
  static final Comparator<String> BY_CALVER =
      (left, right) -> {
        boolean leftIsVersion = CALVER.matcher(left).matches();
        boolean rightIsVersion = CALVER.matcher(right).matches();
        if (!leftIsVersion || !rightIsVersion) {
          return leftIsVersion == rightIsVersion
              ? left.compareTo(right)
              : (leftIsVersion ? 1 : -1);
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        for (int part = 0; part < leftParts.length; part++) {
          int compared = Long.compare(Long.parseLong(leftParts[part]), Long.parseLong(rightParts[part]));
          if (compared != 0) {
            return compared;
          }
        }
        return 0;
      };

  private void deleteTag(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    // An image name cannot contain a colon, so the last one separates image from tag.
    int colon = dead.identity().lastIndexOf(':');
    String image = dead.identity().substring(0, colon);
    String tagName = dead.identity().substring(colon + 1);
    try {
      OciTag row = tags.findOne(dead.repository(), image, tagName).orElse(null);
      if (row == null) {
        errors.add(dead.identity() + ": no such tag row — the store moved since planning");
        return;
      }
      if (anyWithinGrace(dead.repository(), image, row.manifestDigest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectTag(dead.repository(), image, tagName);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  private void deleteManifest(
      GcIdentity dead,
      GcStrategy.GraceWindow grace,
      List<GcIdentity> deleted,
      List<GcIdentity> withheld,
      List<String> errors) {
    int at = dead.identity().lastIndexOf(DIGEST_PREFIX);
    String image = dead.identity().substring(0, at);
    String digest = dead.identity().substring(at + DIGEST_PREFIX.length());
    try {
      if (anyWithinGrace(dead.repository(), image, digest, grace)) {
        withheld.add(dead);
        return;
      }
      registry.collectManifest(dead.repository(), image, digest);
      deleted.add(dead);
    } catch (RuntimeException failed) {
      errors.add(dead.identity() + ": " + failed.getMessage());
    }
  }

  /**
   * Whether any blob the manifest's closure releases is still inside the grace window — the gate on
   * identity deletion, because a row deleted over a young file would strand that file row-less and
   * therefore untouchable forever. A manifest row already gone gates on nothing.
   */
  private boolean anyWithinGrace(
      String repository, String image, String digest, GcStrategy.GraceWindow grace) {
    OciManifest manifest = manifests.findOne(repository, image, digest).orElse(null);
    if (manifest == null) {
      return false;
    }
    return footprints.of(manifest).keySet().stream().anyMatch(grace::withinGrace);
  }
}
