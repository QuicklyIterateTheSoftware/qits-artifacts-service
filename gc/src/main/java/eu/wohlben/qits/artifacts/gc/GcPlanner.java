package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.blobstore.entity.ArtifactRepository;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoriesPlanResponse;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanSummary;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepPlan;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import eu.wohlben.qits.artifacts.gc.dto.GcTypePlan;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

/**
 * The dry run: every repository type, what its strategy would delete, and what a sweep would then
 * unlink.
 *
 * <p>This is the artifact the user reads before any collection is switched on, so its job is to be
 * <b>honest about absence</b>. Every REGISTERED type appears in every report, including the ones
 * nobody collects: a missing entry would read as "nothing to collect here", and "no strategy
 * registered" is a different fact. "Registered" is the profile beans on the classpath rather than an
 * enum's constants — the cache types are qits-platform-mirror's and are absent here, which is a
 * stronger statement than reporting them uncollected.
 *
 * <p>Strategies are found by CDI type. Adding one to a repository type is a bean implementing {@link
 * GcStrategy}; nothing here is edited, and nothing here knows what a tag or a version is.
 *
 * <p>Two failure shapes are reported rather than thrown, because a report that 500s tells a reviewer
 * nothing about the other four types: a strategy that refuses to plan (fail-closed — its type keeps
 * every blob), and two strategies claiming one type, which is a policy collision and never a merge.
 *
 * <p><b>The report also echoes the configuration</b> ({@link GcRules}): per type, the configured
 * engine, its window and the effective rule as a sentence. The settlement moved the policy into
 * configuration, and configuration is the half of a plan the dead and kept lists cannot show —
 * "nothing died" reads identically whether the rule is right or the window is a year. The echo reads
 * as the rule that actually ran: {@link OwnArtifactsStrategy} writes the sentence and the same class
 * produced the plan beside it.
 *
 * <p><b>Per repository is a reading of the same run, never a second planner.</b> The type loop is
 * the only place a rule is ever applied; a repository's figures are its share of its type's plan
 * ({@link GcStrategy.Plan#scopedTo}) reconciled against the same census. So the listing, the
 * whole-store report and a single repository's detail report cannot disagree — there is one plan
 * underneath all three. The rows come from {@code artifact_repository} rather than from the planned
 * identities, because a repository with nothing to collect must still appear: derived from
 * identities, an empty repository would silently vanish from the list.
 */
@ApplicationScoped
public class GcPlanner {

  @Inject LiveBlobCensus census;
  @Inject BlobSweep sweep;
  @Inject Instance<GcStrategy> strategies;
  @Inject GcTypeConfig config;
  @Inject GcPinSources pinSources;
  @Inject ArtifactRepositoryService repositories;

  /**
   * Every repository type this deployment registers — what "every type appears in every report"
   * means now that the set is open. It is the profile beans on the classpath, so a type the mirror
   * owns is absent here rather than reported as uncollected, and a format module left out of the
   * build takes its line out with it.
   */
  @Inject RepositoryTypeProfiles repositoryTypes;

  /**
   * A fresh census, every live pin, every registered strategy, and the reconciliation over all of
   * them.
   *
   * <p>The pins are read <b>first</b> and once. A source that cannot answer does not make this
   * method throw — a dry-run that 500s tells a reviewer nothing about the types that are fine — so
   * the report comes back with the pin-dependent types failed and {@code executable} false.
   */
  public GcPlanReport plan() {
    return plan((GcSuppliedPins) null);
  }

  /**
   * The same plan, over pins the caller supplied instead of the ones this service would fetch.
   *
   * <p>Null is the no-body path and behaves exactly like {@link #plan()}. A supplied set drives the
   * keeps in its place — one platform-wide pin set, read once by qits-platform-orchestrator and
   * given to every deleter in the run — and a member it left out is that source unanswered, so the
   * report comes back non-executable just as an unreachable service makes it.
   */
  public GcPlanReport plan(GcSuppliedPins supplied) {
    return plan(census.take(), registered(), pinSources.fetch(supplied));
  }

  /**
   * The per-repository figures alone, from one run of the same plan.
   *
   * <p>What a listing reads. It costs exactly what {@link #plan()} costs — one census, one pin
   * fetch — and answers every repository, which is the whole point: a plan per row would be N
   * censuses and 2N cross-service calls to draw one column.
   */
  public GcRepositoriesPlanResponse planForRepositories() {
    GcPlanReport report = plan();
    return new GcRepositoriesPlanResponse(
        report.generatedAt(),
        report.executable(),
        report.pinFailures(),
        report.graceWindow(),
        report.repositories());
  }

  /**
   * One repository's plan in full — the review artifact a scoped sweep is authorised from.
   *
   * <p>Only the strategy claiming that repository's type is asked to plan, and its answer is then
   * scoped. Every other type contributes its whole census live set to the reconciliation exactly as
   * it does in a full report, which is what makes the figures here identical to this repository's
   * row in {@link #planForRepositories()} rather than merely similar.
   *
   * @throws eu.wohlben.qits.blobstore.error.NotFoundException no repository of that name
   */
  public GcRepositoryPlanReport planForRepository(String name) {
    ArtifactRepository row = repositories.require(name);
    GcPins pins = pinSources.fetch();
    LiveBlobCensus.Census taken = census.take();
    String type = row.type;
    Outcome outcome = outcomeOf(type, claiming(registered(), type), taken, pins);

    GcStrategy.Plan scoped = outcome.plan() == null ? null : outcome.plan().scopedTo(name);
    GcSweepPlan structural =
        scoped == null ? nothing() : sweep.planForOneType(taken, type, scoped, false);
    GcSweepPlan matured =
        scoped == null ? nothing() : sweep.planForOneType(taken, type, scoped, true);

    return new GcRepositoryPlanReport(
        name,
        RepositoryTypeProfile.wireNameOf(type),
        taken.takenAt(),
        true,
        iso(sweep.graceWindow()),
        pins.complete(),
        pins.failures(),
        pins.sources(),
        GcRules.line(config, type),
        outcome.strategy(),
        outcome.note(),
        outcome.error(),
        scoped == null ? List.of() : scoped.dead(),
        scoped == null ? List.of() : scoped.kept(),
        matured,
        structural,
        sweep.untouchable(taken));
  }

  /** What CDI found. Empty is the shipped state and a supported one. */
  List<GcStrategy> registered() {
    return strategies.stream().toList();
  }

  GcPlanReport plan(
      LiveBlobCensus.Census census, Collection<GcStrategy> strategies, GcPins pins) {
    Map<String, GcStrategy.Plan> plans = new TreeMap<>();
    Map<String, Outcome> outcomes = new TreeMap<>();
    List<GcTypePlan> types = new ArrayList<>();
    for (String type : repositoryTypes.keys()) {
      Outcome outcome = outcomeOf(type, claiming(strategies, type), census, pins);
      outcomes.put(type, outcome);
      GcStrategy.Plan plan = outcome.plan();
      if (plan == null) {
        types.add(
            new GcTypePlan(
                RepositoryTypeProfile.wireNameOf(type),
                outcome.strategy(), outcome.note(), outcome.error(),
                List.of(), List.of(), 0, 0, 0L));
        continue;
      }
      plans.put(type, plan);
      GcSweepPlan attributed = sweep.planForOneType(census, type, plan);
      types.add(
          new GcTypePlan(
              RepositoryTypeProfile.wireNameOf(type),
              outcome.strategy(),
              outcome.note(),
              null,
              plan.dead(),
              plan.kept(),
              plan.blobsReleased().size(),
              attributed.blobCount(),
              attributed.reclaimableBytes()));
    }

    // The configuration echo, beside the outcomes rather than instead of them: what each type is
    // configured to do today, in the sentence the engine that will do it writes for itself.
    List<GcTypeConfiguration> configuration = GcRules.echo(config, repositoryTypes.keys());
    GcSweepPlan sweepPlan = sweep.plan(census, plans);
    String graceWindow = iso(sweep.graceWindow());
    return new GcPlanReport(
        // First in the record, so it is first on the wire and first on the page: a reviewer reads
        // the summary, then goes looking in the detail for the line that surprised them.
        GcSummary.of(configuration, types, sweepPlan, pins, graceWindow),
        census.takenAt(),
        true,
        graceWindow,
        // Executable only when every pin source answered: a plan whose keep-set is missing a live
        // pin is a plan nobody may execute, and saying so on the report is how a reader knows the
        // zeros beside a pin-dependent type are a refusal rather than a finding.
        pins.complete(),
        pins.failures(),
        // How the pins were read, which is the provenance of every keep the report claims below.
        pins.sources(),
        configuration,
        types,
        perRepository(census, outcomes),
        sweepPlan,
        sweep.untouchable(census));
  }

  /**
   * The same run, attributed per {@code artifact_repository} row.
   *
   * <p>In-memory over sets the type loop already computed: no second census, no second pin fetch,
   * and no rule applied twice. The two reconciliations per planned repository are the honest pair —
   * what the rule frees, and what a run now would actually unlink.
   */
  private List<GcRepositoryPlanSummary> perRepository(
      LiveBlobCensus.Census census, Map<String, Outcome> outcomes) {
    List<ArtifactRepository> rows = new ArrayList<>(repositories.list());
    rows.sort(Comparator.comparing(row -> row.name));
    List<GcRepositoryPlanSummary> summaries = new ArrayList<>();
    for (ArtifactRepository row : rows) {
      // A row whose type no profile claims is not this deployment's to plan — the mirror rows the
      // migration chain still prefills are the case. Reporting one with zeros would read as
      // "nothing to collect here", which is a claim about a type this service cannot enforce.
      if (repositoryTypes.find(row.type).isEmpty()) {
        continue;
      }
      Outcome outcome = outcomes.get(row.type);
      GcStrategy.Plan plan = outcome == null ? null : outcome.plan();
      if (plan == null) {
        summaries.add(
            new GcRepositoryPlanSummary(
                row.name,
                RepositoryTypeProfile.wireNameOf(row.type),
                outcome == null ? null : outcome.strategy(),
                outcome == null ? null : outcome.note(),
                outcome == null ? null : outcome.error(),
                0,
                0,
                0,
                0L,
                0,
                0L));
        continue;
      }
      GcStrategy.Plan scoped = plan.scopedTo(row.name);
      GcSweepPlan structural = sweep.planForOneType(census, row.type, scoped, false);
      GcSweepPlan matured = sweep.planForOneType(census, row.type, scoped, true);
      summaries.add(
          new GcRepositoryPlanSummary(
              row.name,
              RepositoryTypeProfile.wireNameOf(row.type),
              outcome.strategy(),
              outcome.note(),
              null,
              scoped.dead().size(),
              scoped.kept().size(),
              structural.blobCount(),
              structural.reclaimableBytes(),
              matured.withheldByGraceWindow(),
              matured.withheldBytes()));
    }
    return List.copyOf(summaries);
  }

  /**
   * One type's answer: the plan, or the honest reason there is none.
   *
   * <p>Factored out rather than duplicated, because the whole-store report and a single
   * repository's report have to agree about what happened to a type — an unclaimed type, a
   * collision, a pin refusal and a strategy that threw are four different sentences, and two copies
   * of them would eventually be two different sentences.
   */
  private Outcome outcomeOf(
      String type,
      List<GcStrategy> claiming,
      LiveBlobCensus.Census census,
      GcPins pins) {
    if (claiming.isEmpty()) {
      return new Outcome(
          null,
          "no strategy registered for " + RepositoryTypeProfile.wireNameOf(type),
          null,
          null);
    }
    if (claiming.size() > 1) {
      return new Outcome(
          names(claiming),
          null,
          "two strategies claim this type; a type has exactly one policy, and merging them is never"
              + " the answer",
          null);
    }
    GcStrategy strategy = claiming.get(0);
    String name = nameOf(strategy);
    if (strategy.readsPins() && !pins.complete()) {
      // Not asked to plan at all: its keep-set is partly qits-platform-deployments's or qits-ci's answer, and
      // planning it against "nothing is pinned" is the one mistake that condemns everything.
      return new Outcome(name, null, "live pins unavailable — " + pins.whyIncomplete(), null);
    }
    try {
      GcStrategy.Plan plan = strategy.plan(census, pins);
      return new Outcome(name, GcRules.note(config, type, strategy.note()), null, plan);
    } catch (RuntimeException aborted) {
      // Fail-closed: no plan, so the sweep keeps this type's whole census set. A strategy whose
      // keep-set comes from elsewhere (the OCI rule reads qits-platform-deployments's live pins) must land here
      // rather than plan on facts it could not fetch.
      return new Outcome(name, null, message(aborted), null);
    }
  }

  /** A type's outcome: how it is reported, and its plan when it has one. */
  private record Outcome(String strategy, String note, String error, GcStrategy.Plan plan) {}

  private static List<GcStrategy> claiming(
      Collection<GcStrategy> strategies, String type) {
    return strategies.stream().filter(strategy -> type.equals(strategy.type())).toList();
  }

  /** The empty attribution for a repository whose type produced no plan at all. */
  private static GcSweepPlan nothing() {
    return new GcSweepPlan(0, 0L, 0, 0L, List.of());
  }

  private static String names(List<GcStrategy> claiming) {
    return String.join(", ", claiming.stream().map(GcPlanner::nameOf).sorted().toList());
  }

  /**
   * The class a reviewer would recognise, not the class CDI handed over.
   *
   * <p>A normal-scoped bean is injected as a client proxy, so {@code getClass().getSimpleName()}
   * reads {@code SomeGcStrategy_ClientProxy} — a name that appears in no source file and sends
   * whoever greps for it nowhere. Every strategy so far is {@code @Singleton} (a pseudo-scope, so no
   * proxy) and the shipped names are asserted, but that is a choice each new strategy has to make
   * correctly, and the report is the wrong place to find out it was made wrong. Unwrapping here
   * costs one call and removes the question.
   */
  static String nameOf(Object strategy) {
    return ClientProxy.unwrap(strategy).getClass().getSimpleName();
  }

  /**
   * The window, spelled the way it is configured. {@code Duration.toString()} normalises days into
   * hours — the shipped {@code P7D} prints as {@code PT168H} — and a safety window a reviewer has to
   * divide by 24 is a safety window they will misread. Package-visible because the sweep receipt
   * spells the same window and must spell it the same way.
   *
   * <p>Zero is spelled {@code P0D} for the same reason and only that reason: the configuration says
   * {@code P0D} and {@code Duration.ZERO.toString()} says {@code PT0S}, so a reviewer comparing the
   * report against the property file would be comparing two spellings of one number.
   */
  static String iso(Duration window) {
    if (window.isZero()) {
      return "P0D";
    }
    return window.toDaysPart() > 0 && window.minusDays(window.toDaysPart()).isZero()
        ? "P" + window.toDaysPart() + "D"
        : window.toString();
  }

  private static String message(RuntimeException aborted) {
    String message = aborted.getMessage();
    return aborted.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
