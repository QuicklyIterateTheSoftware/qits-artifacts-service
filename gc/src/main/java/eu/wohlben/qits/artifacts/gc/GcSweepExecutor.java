package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.control.BlobCompaction;
import eu.wohlben.qits.blobstore.control.BlobReclaim;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcDiskReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepOutcome;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeSweepResult;
import eu.wohlben.qits.artifacts.gc.dto.GcUntouchablePool;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The executed sweep: what the dry-run reports, done — plan, delete what the plan finds dead, unlink
 * the blobs that frees, and hand back the receipt.
 *
 * <p>One run is one request, and that is a safety property rather than a convenience: every plan
 * this class applies was computed <b>in this run</b>, from a census taken at its start — the OCI
 * strategy's cd pins included, fetched inside its {@code plan()} moments before the rows go. There
 * is deliberately no way to hand this class a plan: a stored plan is a plan on stale facts, and a
 * plan on stale facts deletes a running image.
 *
 * <p>The dry-run's honesty rules carry over whole. Every type is in the receipt; a strategy that
 * refuses to plan fails closed with its type untouched and its reason on the wire; two claimants
 * are a collision and neither runs; the row-less pool is restated on every receipt. What the
 * receipt adds is the withheld column: <b>the grace window gates identity rows here, not only blob
 * unlinks</b>, because a row deleted while its blob's file is inside the window would strand the
 * blob — row-less, therefore untouchable, therefore never reclaimed. A withheld identity is left
 * whole and re-planned by the next run; row and file mature out of the window together. The
 * expected first receipt on a young store is therefore zeros everywhere, and that no-op is the
 * proof the mechanism is safe.
 *
 * <p><b>One phase: a run deletes what its own plan finds dead, on that run (qits-1732).</b> Between
 * qits-1172 and qits-1732 a run only <em>condemned</em> what it found dead and a later run purged it
 * once a condemnation grace had passed, so that a keep-set built from a pin source answering
 * incompletely could be corrected before anything went. That stage is gone by the owner's decision,
 * because the hole it guarded is closed where it opened, and what it cost was real: a grace's worth
 * of dead releases held on disk on a store that ran out of it. Three things make the one-phase
 * collector safe, and none of them is a delay:
 *
 * <ul>
 *   <li><b>The keep-set is fail-closed.</b> Every pin source must answer, and answer whole — an
 *       empty deployment or maintenance answer is a refusal rather than "nothing is pinned" — or
 *       {@link GcPins#complete()} is false.
 *   <li><b>The abort rule.</b> {@link #sweep(GcSuppliedPins)} reads the pins first and returns an
 *       aborted receipt <em>before the census</em> when they are incomplete, so a run whose
 *       keep-set is not known deletes nothing at all.
 *   <li><b>The identity grace</b> covers the one gap no keep-set can see: an identity pushed moments
 *       ago that no pin source names <em>yet</em> — the push-to-pin gap. It is withheld whole while
 *       its own row stamp <em>or</em> any blob it releases is inside {@code
 *       qits.artifacts.gc.blob-grace-period}; see {@link GcStrategy.GraceWindow}.
 * </ul>
 *
 * <p><b>Then the disk comes back, which the deletes alone never did (qits-2248).</b> Unlinking a blob
 * deletes its chunk rows, and PostgreSQL keeps the space they held inside the table's files: it
 * reuses it for later uploads but never returns it to the filesystem, so a run that reclaimed half
 * the store left the volume exactly as full. After the blob loop each run therefore calls the
 * library's {@link BlobCompaction}, which drains mostly-dead chunk segments and drops their
 * partitions — the one operation that unlinks files — and the receipt reports what it measured in
 * {@code disk}. A run that deleted nothing still compacts: segments emptied by an earlier run whose
 * compaction was held, failed or ran on another replica are dropped by whichever run comes next.
 *
 * <p>Every run logs one line per type and one for the blob loop and the disk, so "what did GC ever
 * do" stays a question with an answer.
 */
@ApplicationScoped
public class GcSweepExecutor {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject BlobReclaim blobs;
  @Inject BlobCompaction compaction;
  @Inject Instance<GcStrategy> strategies;
  @Inject GcPinSources pinSources;
  @Inject GcTypeConfig config;

  /** Every repository type this deployment registers — the receipt covers exactly these. */
  @Inject RepositoryTypeProfiles repositoryTypes;

  /**
   * One full run: every live pin, a fresh census, every registered strategy, the unlink loop, the
   * receipt.
   *
   * <p><b>The pins are read first, and a source that cannot answer ends the run here</b> — before
   * the census, before a single row is touched. That is the settlement's abort rule and it replaces
   * the per-type fail-closed this method used to rely on: a keep-set assembled while qits-platform-deployments or
   * qits-ci is unreachable is a keep-set assembled from "nothing is pinned", and the types that do
   * not read pins are not safe to run beside it either, because a blob one of them releases may be
   * the last reference to content a pinned identity of another type still needs.
   */
  public GcSweepReport sweep() {
    return sweep((GcSuppliedPins) null);
  }

  /**
   * The same run, over pins the caller supplied instead of the ones this service would fetch.
   *
   * <p>Null is the no-body path and behaves exactly like {@link #sweep()}. A supplied set is one
   * platform-wide pin set, read once by qits-platform-orchestrator and given to every deleter in
   * the run, and the abort rule does not soften for it: a member the caller left out is that source
   * unanswered, and the run ends here with nothing deleted.
   */
  public GcSweepReport sweep(GcSuppliedPins supplied) {
    GcPins pins = pinSources.fetch(supplied);
    if (!pins.complete()) {
      return aborted(pins);
    }
    return execute(census.take(), strategies.stream().toList(), pins);
  }

  /**
   * The receipt of a run that never started: what was wrong, per type, and zeros everywhere else.
   *
   * <p>No census is taken — the run is over before anything could have moved — so the untouchable
   * pool is reported as uncomputed rather than as empty. An empty list there would read as "this
   * store has no row-less blobs", which is a claim, and this run made none.
   */
  private GcSweepReport aborted(GcPins pins) {
    String why = "the run was aborted before anything was deleted: " + pins.whyIncomplete();
    Log.warnf("gc sweep aborted: %s", pins.whyIncomplete());
    List<GcTypeSweepResult> types = new ArrayList<>();
    for (String type : repositoryTypes.keys()) {
      types.add(
          new GcTypeSweepResult(
              RepositoryTypeProfile.wireNameOf(type), null, null, why, List.of(), List.of()));
    }
    return new GcSweepReport(
        Instant.now(),
        false,
        GcPlanner.iso(sweep.graceWindow()),
        why,
        pins.sources(),
        types,
        new GcSweepOutcome(0, 0L, 0, 0L, 0, 0, List.of()),
        null,
        new GcUntouchablePool(
            "not computed: this run aborted before taking a census, and deleted nothing",
            0,
            0L,
            List.of()),
        newRunId());
  }

  GcSweepReport execute(
      LiveBlobCensus.Census taken, Collection<GcStrategy> registered, GcPins pins) {
    Instant executedAt = startedAt();
    String runId = newRunId();
    Duration window = sweep.graceWindow();
    GcStrategy.GraceWindow grace = graceSince(executedAt.minus(window));

    Map<String, List<GcStrategy>> claimants = new TreeMap<>();
    for (GcStrategy strategy : registered) {
      claimants.computeIfAbsent(strategy.type(), type -> new ArrayList<>()).add(strategy);
    }

    Map<String, GcStrategy.Plan> plans = new TreeMap<>();
    List<GcTypeSweepResult> types = new ArrayList<>();
    for (String type : repositoryTypes.keys()) {
      List<GcStrategy> claiming = claimants.getOrDefault(type, List.of());
      if (claiming.isEmpty()) {
        types.add(
            new GcTypeSweepResult(
                RepositoryTypeProfile.wireNameOf(type),
                null,
                "no strategy registered for " + RepositoryTypeProfile.wireNameOf(type),
                null,
                List.of(),
                List.of()));
        continue;
      }
      if (claiming.size() > 1) {
        types.add(
            new GcTypeSweepResult(
                RepositoryTypeProfile.wireNameOf(type),
                names(claiming),
                null,
                "two strategies claim this type; a type has exactly one policy, and merging them"
                    + " is never the answer",
                List.of(),
                List.of()));
        continue;
      }
      GcStrategy strategy = claiming.get(0);
      String name = GcPlanner.nameOf(strategy);
      try {
        GcStrategy.Plan plan = strategy.plan(taken, pins);
        // In the map before apply: even a partially applied type must contribute its released set
        // to the blob loop, or rows already deleted would leave their blobs unswept and stranded.
        plans.put(type, plan);
        GcStrategy.Applied applied = strategy.apply(plan, grace);
        Log.infof(
            "gc sweep %s: deleted %d identities, withheld %d by grace, %d errors",
            RepositoryTypeProfile.wireNameOf(type),
            applied.deleted().size(),
            applied.withheldByGraceWindow().size(),
            applied.errors().size());
        types.add(
            new GcTypeSweepResult(
                RepositoryTypeProfile.wireNameOf(type),
                name,
                GcRules.note(config, type, strategy.note()),
                applied.errors().isEmpty() ? null : String.join("; ", applied.errors()),
                applied.deleted(),
                applied.withheldByGraceWindow()));
      } catch (RuntimeException aborted) {
        // Fail-closed, exactly as the planner: an exception out of plan() (or a wholesale one out
        // of apply()) reports the type as failed. The blob loop is safe either way — a row that
        // was not deleted keeps its blob referenced, and the fresh census sees that.
        Log.infof("gc sweep %s: refused — %s", RepositoryTypeProfile.wireNameOf(type), message(aborted));
        types.add(
            new GcTypeSweepResult(
                RepositoryTypeProfile.wireNameOf(type), name, null, message(aborted), List.of(), List.of()));
      }
    }

    GcSweepOutcome outcome = sweep.execute(taken, plans);
    GcDiskReport disk = compact();
    Log.infof(
        "gc sweep blobs: unlinked %d (%d bytes), withheld %d by grace (%d bytes), %d still"
            + " referenced, %d already gone, %s",
        outcome.blobsUnlinked(),
        outcome.bytesReclaimed(),
        outcome.withheldByGraceWindow(),
        outcome.withheldBytes(),
        outcome.stillReferenced(),
        outcome.alreadyGone(),
        diskLine(disk));
    return new GcSweepReport(
        executedAt,
        false,
        GcPlanner.iso(window),
        null,
        pins.sources(),
        types,
        outcome,
        disk,
        // The pool as it stood before this run: a version row deleted moments ago leaves its
        // within-grace tarball row-less in a fresh census, and listing that as "untouchable" would
        // misname a blob the next run is going to sweep.
        sweep.untouchable(taken),
        runId);
  }

  /**
   * Gives back the disk the run's deletes left behind, and says what that was worth — or null.
   *
   * <p><b>Only an executed sweep gets here.</b> The dry run is {@code GcPlanner}'s and never reaches
   * this class, and neither does an aborted run: it returns its receipt before the blob loop. Compaction moves and drops chunk partitions, which is
   * a write, and a plan must stay reviewable without side effects.
   *
   * <p><b>Outside any transaction, by construction.</b> {@link BlobCompaction#compact()} commits
   * each step on its own and refuses a caller's transaction with an {@link IllegalStateException};
   * this class holds none — every delete above committed in a transaction of its own, opened and
   * closed inside the adapter or the store — and the controller methods that call it are not
   * {@code @Transactional}. Should a caller ever wrap a sweep in one, the refusal lands in the catch
   * below as a logged warning and a receipt without disk figures, never as a failed sweep.
   *
   * <p><b>A failure never fails the sweep.</b> By the time this runs the identity rows and blobs are
   * gone, and the receipt is the only record of which; turning a compaction error into a 500 would
   * throw that record away to report something the next run will retry anyway. It is logged as a
   * warning, with the cause, and the receipt carries no disk figures rather than invented ones.
   */
  private GcDiskReport compact() {
    try {
      BlobCompaction.CompactionResult result = compaction.compact();
      return new GcDiskReport(
          result.state().name(),
          result.blobTablesBytesBefore(),
          result.blobTablesBytesAfter(),
          result.databaseBytesBefore(),
          result.databaseBytesAfter(),
          result.segmentsDrained(),
          result.segmentsDropped(),
          result.segmentsHeld(),
          result.bytesMoved());
    } catch (RuntimeException failed) {
      Log.warnf(
          failed,
          "gc sweep: blob compaction failed, so this run returns no disk; the deletes stand and the"
              + " next run compacts again — %s",
          message(failed));
      return null;
    }
  }

  /** The disk half of a run's blob log line, beside the data figures it is not. */
  private static String diskLine(GcDiskReport disk) {
    if (disk == null) {
      return "disk not compacted (see the warning above)";
    }
    return String.format(
        "disk %d -> %d bytes (blob tables), %d -> %d bytes (database), %d segments dropped, %d"
            + " drained, %d held (%s)",
        disk.blobTablesBytesBefore(),
        disk.blobTablesBytesAfter(),
        disk.databaseBytesBefore(),
        disk.databaseBytesAfter(),
        disk.segmentsDropped(),
        disk.segmentsDrained(),
        disk.segmentsHeld(),
        disk.state());
  }

  /**
   * The run's one clock, at the database's resolution. The grace comparisons this run makes are
   * against {@code timestamptz} stamps, which hold microseconds, so the receipt's {@code executedAt}
   * and the instant the window is measured back from never disagree by a few nanoseconds the
   * column cannot hold.
   */
  private static Instant startedAt() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static String newRunId() {
    return UUID.randomUUID().toString();
  }

  /**
   * The identity grace, measured back from the run's one clock: whether a blob's file — or an
   * identity's own row — was written after the given instant.
   *
   * <p>{@link BlobSweep} builds the dry run's from the same factory on purpose: the plan and the
   * sweep must judge an identity's youth by the same comparison, or the same content would be
   * withheld by one and taken by the other on the same afternoon.
   */
  private GcStrategy.GraceWindow graceSince(Instant graceStartsAt) {
    return GcStrategy.GraceWindow.since(graceStartsAt, blobs::lastWrittenAt);
  }

  private static String names(List<GcStrategy> claiming) {
    return String.join(", ", claiming.stream().map(GcPlanner::nameOf).sorted().toList());
  }

  private static String message(RuntimeException aborted) {
    String message = aborted.getMessage();
    return aborted.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
