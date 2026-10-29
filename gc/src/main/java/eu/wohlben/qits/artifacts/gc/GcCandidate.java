package eu.wohlben.qits.artifacts.gc;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One identity of one type, reduced to the facts a generic engine reads.
 *
 * <p>This is the whole translation between a repository type and the two engines. What an identity
 * <em>is</em> — a tag, a version, a deployed path, a record row — stays inside its {@link
 * GcTypeAdapter}; what an engine sees is this record, and nothing here names a protocol.
 *
 * @param repository the {@code artifact_repository} row it lives in
 * @param identity the type's own coordinate, spelled the way that type's tools spell it, so a
 *     report can be looked up without translating
 * @param group the identity group the release rule counts within — the package, the image, the
 *     {@code (group, artifact)} coordinate, the daemon's name. Only {@link OwnArtifactsStrategy}
 *     reads it; a cache has no groups worth counting.
 * @param released whether this identity is a <b>release</b> in its own type's meaning. That meaning
 *     differs per type by design (a version string with no prerelease part for npm, a calver tag
 *     beside a sha tag for docker), which is exactly why the adapter answers it rather than the
 *     engine deciding.
 * @param lastAccessAt the <b>effective access time</b>: {@code max(created/published/fetched,
 *     accessed_at)}. Creation counts as a first access, so a freshly published artifact nobody has
 *     read yet is young rather than never-read, and null therefore never reaches an engine.
 * @param blobs every blob this identity names. The engines put them in the released or the retained
 *     set; which of them may actually be unlinked stays {@link BlobSweep}'s answer alone.
 * @param stampedAt the identity's <b>own row stamp</b>: when the push that made it this identity
 *     wrote its row, server-set and moved by no read — {@code oci_tag.updated_at}, {@code
 *     npm_version.created_at}, and so on per adapter. No rule reads it. It is carried so the dry run
 *     can judge the identity grace a sweep applies (qits-1732): a dead identity whose row is inside
 *     the window is withheld whole even when every blob it names is old, because a push that dedupes
 *     onto bytes the store already held leaves their {@code stored_at} where it was. Null where a
 *     caller has no row to read it from — a hand-built candidate — and then the identity's youth is
 *     its blobs' alone.
 */
public record GcCandidate(
    String repository,
    String identity,
    String group,
    boolean released,
    Instant lastAccessAt,
    Set<String> blobs,
    Instant stampedAt) {

  public GcCandidate {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(group, "group");
    Objects.requireNonNull(
        lastAccessAt,
        "lastAccessAt: an adapter must fold creation into the access time, so that a row with no"
            + " read yet reads as young rather than as unknown");
    blobs = Set.copyOf(blobs);
  }

  /** A candidate without a row stamp — what a hand-built candidate can honestly say. */
  public GcCandidate(
      String repository,
      String identity,
      String group,
      boolean released,
      Instant lastAccessAt,
      Set<String> blobs) {
    this(repository, identity, group, released, lastAccessAt, blobs, null);
  }

  /** Whether this identity was last touched before the cut — the access rule, said once. */
  boolean unaccessedSince(Instant cut) {
    return lastAccessAt.isBefore(cut);
  }
}
