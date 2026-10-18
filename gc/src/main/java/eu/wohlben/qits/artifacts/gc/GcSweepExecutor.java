package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.control.BlobReclaim;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcCondemnedIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositorySweepReport;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The executed sweep: what the dry-run reports, done — plan, condemn the dead, purge what an earlier
 * run condemned long enough ago, unlink the blobs that frees, and hand back the receipt.
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
 * <p><b>Two phases, since qits-1172: a run condemns, a later run purges.</b> An identity this run's
 * plan finds dead is not deleted on the run that finds it — it is condemned ({@link
 * GcCondemnations}), which touches nothing it owns: rows, blobs and reads stay as they were. A run
 * deletes only what an earlier run condemned at least {@code qits.artifacts.gc.condemn-grace-period}
 * ago <em>and</em> its own fresh plan still finds dead; what its plan keeps again has its
 * condemnation lifted. So the delete path below is unchanged and simply reached later: the plan
 * handed to {@code apply} and to the blob loop is {@link GcStrategy.Plan#purging} of the run's
 * plan, holding only the ripe identities and retaining every still-condemned identity's blobs. The
 * adapters needed no change for it, because their delete loops act on {@code plan.dead()} and
 * nothing else. The blob grace still gates that purge, exactly as before.
 *
 * <p><b>Condemnation is per type and per scope, and it is fail-closed with the plan.</b> A type whose
 * strategy refused, or whose condemnation step failed, changes no condemnation and purges nothing:
 * its keep-set is unknown, so lifting a condemnation would be as unfounded as writing one. An aborted
 * run changes none at all — it is over before any plan exists. A scoped run reads, writes and lifts
 * the condemnations of its own repository only: another repository's condemnation was judged by a
 * plan this run never applied, so it is not this run's to clear.
 *
 * <p>Every run logs one line per type and one for the blob loop, so "what did GC ever do" stays a
 * question with an answer.
 */
@ApplicationScoped
public class GcSweepExecutor {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject BlobReclaim blobs;
  @Inject Instance<GcStrategy> strategies;
  @Inject GcPinSources pinSources;
  @Inject GcTypeConfig config;
  @Inject ArtifactRepositoryService repositories;
  @Inject GcCondemnations condemnations;

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
   * One repository's run: the same choreography, over the identities of one {@code
   * artifact_repository} row and nothing else.
   *
   * <p><b>The plan applied here is the same plan, filtered.</b> The claiming strategy is asked for
   * its whole type's plan and the answer is scoped ({@link GcStrategy.Plan#scopedTo}) before
   * anything is applied — never a second planner, because a second planner over one type is a
   * second policy. The adapters need no change: every {@code GcIdentity} carries its repository and
   * their delete loops already dispatch on it, so the funnels — the npm tombstone, the OCI
   * collect — run exactly as they do in a whole-store run.
   *
   * <p><b>The whole-run abort translates unchanged, and it has to.</b> A pin source that cannot
   * answer ends this run before the census with nothing deleted, even though only one repository
   * was in scope: blobs dedupe globally, so a blob this repository releases can be the last local
   * reference to bytes a pin names by digest. "Only one repository" is not a smaller blast radius
   * at the blob layer, and treating it as one would be the documented way to delete something a
   * live service still fetches.
   *
   * <p><b>The name is resolved before the pins are read</b>, which is the one order difference from
   * the whole-store run and is deliberate: a repository that does not exist is a fact about the
   * request, not about the run, and answering an aborted receipt for it would claim a run was
   * attempted against something that is not there. Resolving a name takes no census and touches no
   * row, so the "pins first, before anything is deleted" rule is untouched.
   *
   * @throws eu.wohlben.qits.blobstore.error.NotFoundException no repository of that name
   */
  public GcRepositorySweepReport sweep(String name) {
    return sweep(name, null);
  }

  /**
   * One repository's run, over pins the caller supplied instead of the ones this service would
   * fetch. Null is the no-body path; everything else this method's untyped twin says still holds,
   * the whole-run abort included.
   */
  public GcRepositorySweepReport sweep(String name, GcSuppliedPins supplied) {
    ArtifactRepository row = repositories.require(name);
    GcPins pins = pinSources.fetch(supplied);
    if (!pins.complete()) {
      return aborted(row, pins);
    }
    return execute(row, census.take(), strategies.stream().toList(), pins);
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
        new GcUntouchablePool(
            "not computed: this run aborted before taking a census, and deleted nothing",
            0,
            0L,
            List.of()),
        newRunId(),
        GcPlanner.iso(condemnations.gracePeriod()));
  }

  /**
   * The scoped twin of {@link #aborted(GcPins)}: nothing read, nothing deleted, the reason carried.
   *
   * <p>No census either, for the same reason — the run is over before anything could have moved, so
   * the row-less pool is reported as uncomputed rather than as an empty list, which would be a
   * claim about a store this run never read.
   */
  private GcRepositorySweepReport aborted(ArtifactRepository row, GcPins pins) {
    String why = "the run was aborted before anything was deleted: " + pins.whyIncomplete();
    Log.warnf("gc sweep of %s aborted: %s", row.name, pins.whyIncomplete());
    return new GcRepositorySweepReport(
        row.name,
        RepositoryTypeProfile.wireNameOf(row.type),
        Instant.now(),
        false,
        GcPlanner.iso(sweep.graceWindow()),
        why,
        pins.sources(),
        null,
        null,
        why,
        List.of(),
        List.of(),
        new GcSweepOutcome(0, 0L, 0, 0L, 0, 0, List.of()),
        new GcUntouchablePool(
            "not computed: this run aborted before taking a census, and deleted nothing",
            0,
            0L,
            List.of()),
        newRunId(),
        GcPlanner.iso(condemnations.gracePeriod()),
        List.of(),
        List.of(),
        List.of());
  }

  GcRepositorySweepReport execute(
      ArtifactRepository row,
      LiveBlobCensus.Census taken,
      Collection<GcStrategy> registered,
      GcPins pins) {
    Instant executedAt = startedAt();
    String runId = newRunId();
    Duration window = sweep.graceWindow();
    GcStrategy.GraceWindow grace = graceSince(executedAt.minus(window));
    String type = row.type;

    List<GcStrategy> claiming =
        registered.stream().filter(strategy -> type.equals(strategy.type())).toList();
    if (claiming.isEmpty()) {
      // Not an error status: a repository nobody collects gets a receipt saying so and zeros, the
      // same posture the whole-store surface holds. The UI never offers the button for such a row.
      return nothingRan(
          row,
          taken,
          executedAt,
          runId,
          window,
          pins,
          null,
          "no strategy registered for " + RepositoryTypeProfile.wireNameOf(type),
          null);
    }
    if (claiming.size() > 1) {
      return nothingRan(
          row,
          taken,
          executedAt,
          runId,
          window,
          pins,
          names(claiming),
          null,
          "two strategies claim this type; a type has exactly one policy, and merging them is never"
              + " the answer");
    }

    GcStrategy strategy = claiming.get(0);
    String name = GcPlanner.nameOf(strategy);
    GcStrategy.Plan scoped;
    Verdict verdict;
    try {
      scoped = strategy.plan(taken, pins).scopedTo(row.name);
      // Scoped to the repository here as well as in the plan: the condemnations this run reads,
      // writes and lifts are this repository's and nobody else's.
      verdict = condemn(type, row.name, scoped, executedAt, runId);
    } catch (RuntimeException refused) {
      // Fail-closed, exactly as the whole-store run: no plan, so no row of this repository moves,
      // no condemnation changes, and the blob loop is never reached.
      Log.infof("gc sweep of %s: refused — %s", row.name, message(refused));
      return nothingRan(row, taken, executedAt, runId, window, pins, name, null, message(refused));
    }

    GcStrategy.Applied applied = strategy.apply(verdict.purge(), grace);
    purged(type, applied);
    Log.infof(
        "gc sweep %s (%s): condemned %d, cleared %d, purged %d identities, awaiting purge %d,"
            + " withheld %d by grace, %d errors",
        row.name,
        RepositoryTypeProfile.wireNameOf(type),
        verdict.condemned().size(),
        verdict.cleared().size(),
        applied.deleted().size(),
        verdict.awaitingPurge().size(),
        applied.withheldByGraceWindow().size(),
        applied.errors().size());
    // The blob loop runs over the whole store with only this repository's plan applied. Every other
    // type contributes its census live set, and every other repository of THIS type is inside the
    // scoped plan's retained set — so a shared blob is protected by the reconciliation before the
    // re-census and the store's own guard ever have to catch it.
    GcSweepOutcome outcome = sweep.execute(taken, Map.of(type, verdict.purge()));
    Log.infof(
        "gc sweep %s blobs: unlinked %d (%d bytes), withheld %d by grace (%d bytes), %d still"
            + " referenced, %d already gone",
        row.name,
        outcome.blobsUnlinked(),
        outcome.bytesReclaimed(),
        outcome.withheldByGraceWindow(),
        outcome.withheldBytes(),
        outcome.stillReferenced(),
        outcome.alreadyGone());

    return new GcRepositorySweepReport(
        row.name,
        RepositoryTypeProfile.wireNameOf(type),
        executedAt,
        false,
        GcPlanner.iso(window),
        null,
        pins.sources(),
        name,
        GcRules.note(config, type, strategy.note()),
        applied.errors().isEmpty() ? null : String.join("; ", applied.errors()),
        applied.deleted(),
        applied.withheldByGraceWindow(),
        outcome,
        sweep.untouchable(taken),
        runId,
        GcPlanner.iso(condemnations.gracePeriod()),
        verdict.condemned(),
        verdict.cleared(),
        verdict.awaitingPurge());
  }

  /** A run that read the store, planned nothing, and therefore deleted and condemned nothing. */
  private GcRepositorySweepReport nothingRan(
      ArtifactRepository row,
      LiveBlobCensus.Census taken,
      Instant executedAt,
      String runId,
      Duration window,
      GcPins pins,
      String strategy,
      String note,
      String error) {
    return new GcRepositorySweepReport(
        row.name,
        RepositoryTypeProfile.wireNameOf(row.type),
        executedAt,
        false,
        GcPlanner.iso(window),
        null,
        pins.sources(),
        strategy,
        note,
        error,
        List.of(),
        List.of(),
        new GcSweepOutcome(0, 0L, 0, 0L, 0, 0, List.of()),
        // A census WAS taken here, so the pool is a real reading rather than "not computed".
        sweep.untouchable(taken),
        runId,
        GcPlanner.iso(condemnations.gracePeriod()),
        List.of(),
        List.of(),
        List.of());
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
        Verdict verdict = condemn(type, null, plan, executedAt, runId);
        // In the map before apply: even a partially applied type must contribute its released set
        // to the blob loop, or rows already deleted would leave their blobs unswept and stranded.
        // The PURGE plan, not the run's plan: a condemned identity still standing keeps its blobs
        // on the retained side, so they are never even a candidate.
        plans.put(type, verdict.purge());
        GcStrategy.Applied applied = strategy.apply(verdict.purge(), grace);
        purged(type, applied);
        Log.infof(
            "gc sweep %s: condemned %d, cleared %d, purged %d identities, awaiting purge %d,"
                + " withheld %d by grace, %d errors",
            RepositoryTypeProfile.wireNameOf(type),
            verdict.condemned().size(),
            verdict.cleared().size(),
            applied.deleted().size(),
            verdict.awaitingPurge().size(),
            applied.withheldByGraceWindow().size(),
            applied.errors().size());
        types.add(
            new GcTypeSweepResult(
                RepositoryTypeProfile.wireNameOf(type),
                name,
                GcRules.note(config, type, strategy.note()),
                applied.errors().isEmpty() ? null : String.join("; ", applied.errors()),
                applied.deleted(),
                applied.withheldByGraceWindow(),
                verdict.condemned(),
                verdict.cleared(),
                verdict.awaitingPurge()));
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
    Log.infof(
        "gc sweep blobs: unlinked %d (%d bytes), withheld %d by grace (%d bytes), %d still"
            + " referenced, %d already gone",
        outcome.blobsUnlinked(),
        outcome.bytesReclaimed(),
        outcome.withheldByGraceWindow(),
        outcome.withheldBytes(),
        outcome.stillReferenced(),
        outcome.alreadyGone());
    return new GcSweepReport(
        executedAt,
        false,
        GcPlanner.iso(window),
        null,
        pins.sources(),
        types,
        outcome,
        // The pool as it stood before this run: a version row deleted moments ago leaves its
        // within-grace tarball row-less in a fresh census, and listing that as "untouchable" would
        // misname a blob the next run is going to sweep.
        sweep.untouchable(taken),
        runId,
        GcPlanner.iso(condemnations.gracePeriod()));
  }

  /** The rule a lifted condemnation names when the identity it was about is not in the store. */
  static final String NO_LONGER_EXISTS =
      "no longer exists: the identity is gone from the store, so its condemnation names nothing";

  /**
   * What the condemnation phase decided for one type's plan in one scope.
   *
   * @param purge the plan to apply now — {@link GcStrategy.Plan#purging} of the run's plan
   * @param condemned the dead identities condemned by this run for the first time
   * @param cleared the condemnations this run lifted, each naming the rule that saves it now
   * @param awaitingPurge the dead identities left standing because their condemnation is younger
   *     than the grace
   */
  private record Verdict(
      GcStrategy.Plan purge,
      List<GcIdentity> condemned,
      List<GcIdentity> cleared,
      List<GcCondemnedIdentity> awaitingPurge) {}

  /**
   * The first phase: reconcile the standing condemnations against this run's dead set, and say
   * which dead identities are ripe.
   *
   * <p>Three moves, all inside {@code (type, scope)} and nowhere else. A standing condemnation whose
   * identity this plan does not find dead is <b>lifted</b> — the keep-set names it again, or it is
   * gone. A dead identity without one is <b>condemned</b> at this run's instant. A dead identity whose
   * condemnation is at least the grace old is <b>ripe</b>, and only those reach {@code apply}. The
   * comparison is "the grace has fully elapsed" ({@code purgeAt <= now}), so a deployment that sets
   * the grace to zero gets the one-phase collector back, condemn and purge in the same run — the
   * escape hatch, never the default.
   *
   * <p>The writes happen before anything is deleted, so a run that dies after this point leaves its
   * condemnations recorded and nothing else changed. They are what a later run's purge rests on.
   *
   * @param scope null for the whole type; a repository name for a scoped run
   */
  private Verdict condemn(
      String type, String scope, GcStrategy.Plan plan, Instant now, String runId) {
    Duration condemnGrace = condemnations.gracePeriod();
    Map<GcStrategy.IdentityKey, GcCondemnations.Condemnation> standing =
        condemnations.of(type, scope);
    Map<GcStrategy.IdentityKey, GcIdentity> dead = new LinkedHashMap<>();
    plan.dead().forEach(identity -> dead.put(GcStrategy.IdentityKey.of(identity), identity));
    Map<GcStrategy.IdentityKey, GcIdentity> kept = new HashMap<>();
    plan.kept().forEach(identity -> kept.put(GcStrategy.IdentityKey.of(identity), identity));

    List<GcStrategy.IdentityKey> lift = new ArrayList<>();
    List<GcIdentity> cleared = new ArrayList<>();
    for (GcStrategy.IdentityKey key : standing.keySet()) {
      if (!dead.containsKey(key)) {
        lift.add(key);
        cleared.add(
            kept.getOrDefault(key, new GcIdentity(key.repository(), key.identity(), NO_LONGER_EXISTS)));
      }
    }

    List<GcStrategy.IdentityKey> fresh = new ArrayList<>();
    List<GcIdentity> condemned = new ArrayList<>();
    Set<GcStrategy.IdentityKey> ripe = new HashSet<>();
    List<GcCondemnedIdentity> awaiting = new ArrayList<>();
    for (Map.Entry<GcStrategy.IdentityKey, GcIdentity> entry : dead.entrySet()) {
      GcCondemnations.Condemnation existing = standing.get(entry.getKey());
      GcCondemnations.Condemnation condemnation = existing;
      if (existing == null) {
        fresh.add(entry.getKey());
        condemned.add(entry.getValue());
        condemnation =
            new GcCondemnations.Condemnation(
                type, entry.getKey().repository(), entry.getKey().identity(), now, runId);
      }
      if (condemnation.condemnedAt().plus(condemnGrace).isAfter(now)) {
        awaiting.add(condemnation.toWire(condemnGrace));
      } else {
        ripe.add(entry.getKey());
      }
    }

    condemnations.clear(type, lift);
    condemnations.condemn(type, fresh, now, runId);
    return new Verdict(plan.purging(ripe), condemned, cleared, awaiting);
  }

  /**
   * The purge's bookkeeping: a deleted identity's condemnation goes with it.
   *
   * <p>Not fatal when it fails — the rows are already gone, and the next run lifts the stale
   * condemnation by itself, because an identity that no longer exists is never in its dead set.
   */
  private void purged(String type, GcStrategy.Applied applied) {
    try {
      condemnations.clear(
          type, applied.deleted().stream().map(GcStrategy.IdentityKey::of).toList());
    } catch (RuntimeException failed) {
      Log.warnf(
          "gc sweep %s: purged identities' condemnations not lifted (the next run lifts them) — %s",
          RepositoryTypeProfile.wireNameOf(type),
          message(failed));
    }
  }

  /**
   * The run's one clock, at the database's resolution. A condemnation written at this instant is
   * read back by the next run as exactly this instant, so a receipt's {@code executedAt}, the
   * condemnation's {@code condemnedAt} and the purge arithmetic on it never disagree by a few
   * nanoseconds {@code timestamptz} cannot hold.
   */
  private static Instant startedAt() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  private static String newRunId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Whether a blob's file was written after the given instant — the run's one clock, read once.
   *
   * <p>Shared by both runs on purpose: a whole-store sweep and a scoped one must judge an
   * identity's youth by the same comparison, or the same content would be withheld by one and taken
   * by the other on the same afternoon.
   */
  private GcStrategy.GraceWindow graceSince(Instant graceStartsAt) {
    return blobId -> {
      Instant written = blobs.lastWrittenAt(blobId);
      return written != null && written.isAfter(graceStartsAt);
    };
  }

  private static String names(List<GcStrategy> claiming) {
    return String.join(", ", claiming.stream().map(GcPlanner::nameOf).sorted().toList());
  }

  private static String message(RuntimeException aborted) {
    String message = aborted.getMessage();
    return aborted.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
