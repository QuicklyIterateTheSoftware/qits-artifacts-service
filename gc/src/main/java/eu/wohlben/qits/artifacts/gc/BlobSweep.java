package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.control.BlobReclaim;
import eu.wohlben.qits.blobstore.control.BlobStore;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepOutcome;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepPlan;
import eu.wohlben.qits.artifacts.gc.dto.GcUntouchablePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one mechanism that frees disk, and it carries no policy at all.
 *
 * <p>Strategies kill identities and free nothing. This reconciles what they did: a blob dies only
 * when <b>no type</b> reaches it any more, because the store dedupes globally across types and
 * repositories. That split is what makes "one strategy per type" safe by construction — a strategy
 * cannot free a blob another type still needs, because a strategy never frees anything.
 *
 * <p><b>{@link #plan} still deletes nothing</b> — it is what the dry-run report reads, and it must
 * stay reviewable without side effects. The unlink loop is {@link #execute}, added after the user
 * reviewed the dry-run, and it is still the <b>only</b> caller of {@link BlobReclaim#delete}: it runs
 * only when {@code GcSweepExecutor} drives it behind {@code POST /artifacts/api/gc/sweep}, after
 * the strategies have deleted their identity rows, against a census taken fresh after those
 * deletions.
 */
@ApplicationScoped
public class BlobSweep {

  @Inject BlobReclaim blobs;
  @Inject LiveBlobCensus census;

  /**
   * Blobs no type reaches once every given plan is applied, with what they would free.
   *
   * <p>The reconciliation, in one line: a candidate is released by some strategy and retained by
   * none. A type with no plan keeps its whole census set, which is what makes a partial run — one
   * strategy enabled, four not — as safe as a full one.
   *
   * @param census the store as read; a type absent from {@code plans} contributes its live set
   * @param plans the strategies' answers, at most one per type
   */
  public GcSweepPlan plan(LiveBlobCensus.Census census, Map<String, GcStrategy.Plan> plans) {
    return reconcile(census, plans, true);
  }

  /**
   * One type's plan against an otherwise untouched store — the per-type attribution in a report.
   *
   * <p>The grace window is deliberately <b>not</b> applied here. It is a property of when an unlink
   * may happen, not of what a rule structurally frees, and a strategy's worth should not read as
   * zero because its content was pushed this morning. The sweep figure is the one with a date on it.
   */
  public GcSweepPlan planForOneType(
      LiveBlobCensus.Census census, String type, GcStrategy.Plan plan) {
    return planForOneType(census, type, plan, false);
  }

  /**
   * The same attribution, with the window as a choice rather than a constant.
   *
   * <p>Both answers are wanted where a plan is shown per repository, and they answer different
   * questions: {@code false} is what the rule structurally frees — the figure a repository pushed
   * this morning must not read as zero for — and {@code true} is what a run <em>now</em> would
   * unlink, with the rest counted as withheld rather than dropped. Reporting only the first would
   * promise disk the next run cannot deliver; only the second would hide a rule that works.
   *
   * <p>The plan handed in may be a whole type's or one repository's share of it ({@link
   * GcStrategy.Plan#scopedTo}) — the reconciliation cannot tell, because a scoped plan states the
   * type's whole post-plan live set exactly as an unscoped one does.
   */
  public GcSweepPlan planForOneType(
      LiveBlobCensus.Census census,
      String type,
      GcStrategy.Plan plan,
      boolean applyGraceWindow) {
    Map<String, GcStrategy.Plan> only = new TreeMap<>();
    only.put(type, plan);
    return reconcile(census, only, applyGraceWindow);
  }

  private GcSweepPlan reconcile(
      LiveBlobCensus.Census census,
      Map<String, GcStrategy.Plan> plans,
      boolean applyGraceWindow) {
    Set<String> released = new TreeSet<>();
    Set<String> live = new HashSet<>();
    // Every type the CENSUS saw, plus every type with a plan — not the registered set. A row
    // carrying a key no profile claims still holds blobs, and reconciling over registrations would
    // leave those blobs out of `live` and therefore sweepable. The census is the store as it is.
    Set<String> types = new TreeSet<>(census.liveByType().keySet());
    types.addAll(plans.keySet());
    for (String type : types) {
      GcStrategy.Plan plan = plans.get(type);
      if (plan == null) {
        live.addAll(census.live(type).keySet());
      } else {
        released.addAll(plan.blobsReleased());
        live.addAll(plan.blobsRetained());
      }
    }

    Set<String> rowless = census.rowless();
    GcStrategy.GraceWindow grace = applyGraceWindow ? grace() : null;
    Set<String> heldWhole = applyGraceWindow ? heldWhole(plans, grace) : Set.of();
    List<String> sweepable = new ArrayList<>();
    long reclaimable = 0;
    int withheld = 0;
    long withheldBytes = 0;
    for (String blobId : released) {
      if (live.contains(blobId)) {
        continue; // something that survives still names it — the whole point of reconciling
      }
      Long size = census.onDisk().get(blobId);
      if (size == null) {
        continue; // a row outliving its bytes frees nothing
      }
      // Unreachable by construction — a released blob was in its type's live set, so it had a row —
      // and asserted anyway, because a strategy that invented a digest must not reach the unlink.
      if (rowless.contains(blobId)) {
        continue;
      }
      if (applyGraceWindow && (heldWhole.contains(blobId) || grace.withinGrace(blobId))) {
        withheld++;
        withheldBytes += size;
        continue;
      }
      sweepable.add(blobId);
      reclaimable += size;
    }
    return new GcSweepPlan(sweepable.size(), reclaimable, withheld, withheldBytes, sweepable);
  }

  /**
   * The identity grace as the dry run judges it, so the blobs it withholds are the blobs of exactly
   * the identities a sweep would withhold.
   *
   * <p>A sweep's adapters withhold a dead identity <b>whole</b> when its own row stamp or any blob
   * it releases is inside the window (qits-1732, {@link GcStrategy.GraceWindow}): its rows stay, so
   * every blob it names stays referenced — the old ones beside the young one, and all of them when
   * only the row is young. Judging blob by blob would report those old blobs as sweepable, and the
   * run would then find each one still referenced; a dry run that promises disk the run cannot free
   * is the one thing this report must not do. So every blob of such an identity is withheld here.
   *
   * <p>Only an engine's plan can be judged this way, because only it says which dead identity named
   * which blobs ({@link GcStrategy.Plan#releasedByIdentity}). A hand-built plan says nothing per
   * identity and is judged on its blobs' own age alone, which is what the reconciliation did before.
   */
  private static Set<String> heldWhole(
      Map<String, GcStrategy.Plan> plans, GcStrategy.GraceWindow grace) {
    Set<String> held = new HashSet<>();
    for (GcStrategy.Plan plan : plans.values()) {
      plan.releasedByIdentity()
          .forEach(
              (identity, blobIds) -> {
                if (grace.withinGrace(plan.stampedByIdentity().get(identity))
                    || blobIds.stream().anyMatch(grace::withinGrace)) {
                  held.addAll(blobIds);
                }
              });
    }
    return held;
  }

  /**
   * The window as of now, built by the factory the executor builds its own from, so a plan and a
   * receipt compare ages the same way. A blob's write time is read once per call to this method's
   * answer, however many identities share it.
   */
  private GcStrategy.GraceWindow grace() {
    Map<String, Optional<Instant>> written = new HashMap<>();
    return GcStrategy.GraceWindow.since(
        Instant.now().minus(graceWindow()),
        blobId ->
            written
                .computeIfAbsent(blobId, id -> Optional.ofNullable(blobs.lastWrittenAt(id)))
                .orElse(null));
  }

  /**
   * Unlinks what the applied plans freed — the one loop on this platform that deletes bytes.
   *
   * <p>Called after the strategies' identity deletions, with the census the plans were computed
   * from. The safety order inside:
   *
   * <ol>
   *   <li><b>Candidates are structural</b>: released by some plan, retained by none, on disk, and
   *       rowed in the planning census — the same reconciliation the report shows, with the grace
   *       filter left to the gates below.
   *   <li><b>Grace-withheld candidates never reach the unlink.</b> Their identities were withheld
   *       too (rows intact — the executor's gate), so they are counted once, as withheld, rather
   *       than smeared across refusal counters. That includes every blob of an identity withheld
   *       whole for its row stamp or one young sibling ({@link #heldWhole}), however old the blob.
   *   <li><b>The pre-unlink re-census</b>: one fresh census taken here, after the row deletions.
   *       A candidate something still references — a withheld identity of another type, a push
   *       since planning — is skipped and counted. The same set backs the {@link
   *       BlobStore.SweepGuard} asked again inside the store's write lock, so the check and the
   *       unlink cannot be separated by a write.
   *   <li>{@link BlobStore#delete} (through {@link BlobReclaim}) enforces the grace window and the guard once more, per blob,
   *       inside the lock. Every refusal is a counted outcome, never an exception.
   * </ol>
   */
  public GcSweepOutcome execute(
      LiveBlobCensus.Census planned, Map<String, GcStrategy.Plan> plans) {
    GcSweepPlan structural = reconcile(planned, plans, false);
    GcSweepPlan matured = reconcile(planned, plans, true);
    Set<String> withheldIds = new HashSet<>(structural.blobIds());
    withheldIds.removeAll(matured.blobIds());

    LiveBlobCensus.Census fresh = census.take();
    Set<String> referenced = fresh.referenced();
    BlobStore.SweepGuard guard = blobId -> !referenced.contains(blobId);

    int unlinked = 0;
    long reclaimed = 0;
    int withheld = matured.withheldByGraceWindow();
    long withheldBytes = matured.withheldBytes();
    int stillReferenced = 0;
    int alreadyGone = 0;
    List<String> unlinkedIds = new ArrayList<>();
    for (String blobId : structural.blobIds()) {
      if (withheldIds.contains(blobId)) {
        continue; // counted in the withheld figures already; its identity rows are intact too
      }
      if (referenced.contains(blobId)) {
        stillReferenced++;
        continue;
      }
      long size = fresh.onDisk().getOrDefault(blobId, planned.onDisk().getOrDefault(blobId, 0L));
      switch (blobs.delete(blobId, guard)) {
        case DELETED -> {
          unlinked++;
          reclaimed += size;
          unlinkedIds.add(blobId);
        }
        case STILL_REFERENCED -> stillReferenced++;
        case ALREADY_GONE, NOT_A_BLOB_ID -> alreadyGone++;
        case WITHIN_GRACE_WINDOW -> {
          // The store's own belt: the reconcile above judged this blob mature, the store's clock
          // says otherwise at the unlink. Counted as withheld — that is what it is.
          withheld++;
          withheldBytes += size;
        }
      }
    }
    return new GcSweepOutcome(
        unlinked, reclaimed, withheld, withheldBytes, stillReferenced, alreadyGone, unlinkedIds);
  }

  /**
   * The pool this mechanism may never touch, listed rather than counted.
   *
   * <p>It is a report, not a gate: nothing here needs to exclude these blobs, because a candidate
   * has to have lost an identity row and these never had one. Listing them is how a reviewer
   * confirms the ci-daemon binary is on the safe side before agreeing to the first real sweep.
   */
  public GcUntouchablePool untouchable(LiveBlobCensus.Census census) {
    List<String> blobIds = List.copyOf(census.rowless());
    return new GcUntouchablePool(
        "no identity row of any type names these; only a blob that LOSES its last row can ever be"
            + " swept, so these are unreachable from the mechanism",
        blobIds.size(),
        census.bytesOnDisk(blobIds),
        blobIds);
  }

  /** How long a blob's file must sit untouched before {@link BlobReclaim#delete} will unlink it. */
  public Duration graceWindow() {
    return blobs.graceWindow();
  }
}
