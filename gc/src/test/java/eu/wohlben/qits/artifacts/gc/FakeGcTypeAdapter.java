package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A type that exists only in these cases, so the engines are tested on their rules and on nothing
 * else.
 *
 * <p>Both engines are generic by design, and a case driven through a real adapter would prove that
 * one type's rules work rather than that the engine's do. This one answers the seam's four questions
 * — what exists, what a release is, which of two is older, how a row goes — with the smallest thing
 * that can answer them, and orders by age with an explicit sequence number so a case never depends
 * on wall-clock resolution.
 */
final class FakeGcTypeAdapter implements GcTypeAdapter {

  static final String REPO = "fake";

  private final List<GcCandidate> candidates = new ArrayList<>();
  private final List<String> order = new ArrayList<>();
  private boolean addedOldestFirst = true;

  /** Records the plan {@link #delete} was handed, so a binder's contract can be asserted. */
  GcStrategy.Plan deleted;

  /**
   * Adds one identity, oldest-first in the order they are added.
   *
   * @param group the identity group the release belt counts within
   * @param released whether this type calls it a release
   * @param lastAccessAt the effective access time — creation already folded in
   */
  FakeGcTypeAdapter add(
      String identity, String group, boolean released, Instant lastAccessAt, String... blobs) {
    return addIn(REPO, identity, group, released, lastAccessAt, blobs);
  }

  /**
   * The same, in a named repository — one type can hold several.
   *
   * <p>Exists for the scoping cases: what a repository's figures are a filter <em>over</em> is a
   * type's plan across every repository of that type, and an adapter that could only speak for one
   * could not produce the store shape those cases are about.
   */
  FakeGcTypeAdapter addIn(
      String repository,
      String identity,
      String group,
      boolean released,
      Instant lastAccessAt,
      String... blobs) {
    candidates.add(
        new GcCandidate(repository, identity, group, released, lastAccessAt, Set.of(blobs)));
    order.add(identity);
    return this;
  }

  @Override
  public String type() {
    return NpmPackagesProfile.KEY;
  }

  @Override
  public List<GcCandidate> enumerate() {
    return List.copyOf(candidates);
  }

  /**
   * Flips this type's notion of age: identities were then added newest-first.
   *
   * <p>Exists so a case can prove an engine reads {@link #byAge()} rather than the order {@link
   * #enumerate()} happens to return — the difference between an engine that works on every type and
   * one that works on the type it was written against.
   */
  FakeGcTypeAdapter addedNewestFirst() {
    addedOldestFirst = false;
    return this;
  }

  @Override
  public Comparator<GcCandidate> byAge() {
    Comparator<GcCandidate> byInsertion =
        Comparator.comparingInt(candidate -> order.indexOf(candidate.identity()));
    return addedOldestFirst ? byInsertion : byInsertion.reversed();
  }

  @Override
  public GcStrategy.Applied delete(GcStrategy.Plan plan, GcStrategy.GraceWindow grace) {
    deleted = plan;
    return new GcStrategy.Applied(plan.dead(), List.of(), List.of());
  }
}
