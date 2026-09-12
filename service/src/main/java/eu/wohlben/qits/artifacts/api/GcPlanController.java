package eu.wohlben.qits.artifacts.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import eu.wohlben.qits.artifacts.gc.GcPlanner;
import eu.wohlben.qits.artifacts.gc.GcSuppliedPins;
import eu.wohlben.qits.artifacts.gc.GcSweepExecutor;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoriesPlanResponse;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositoryPlanReport;
import eu.wohlben.qits.artifacts.gc.dto.GcRepositorySweepReport;
import eu.wohlben.qits.artifacts.gc.dto.GcSweepReport;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The whole GC surface: the dry-run plan to read, and the sweep to invoke once it has been read.
 *
 * <p>The order of the two routes is the feature. {@code GET /gc/plan} stays the default and changes
 * nothing — a report that could not be reviewed without side effects would be the one report nobody
 * could review. {@code POST /gc/sweep} executes exactly what a plan at that moment says: identity
 * rows per type, then the blob unlinks, each gated by the grace window (identities included — a row
 * deleted over an in-grace blob would strand the blob as row-less and untouchable). It landed after
 * the user reviewed the dry-run, and nothing executes without the {@code POST}.
 *
 * <p><b>The same order exists per repository</b>, under {@code /gc/repositories}: the listing's
 * figures, one repository's plan in full, and the scoped sweep behind them. Scope is a <b>path
 * segment</b> rather than a query parameter, and that is a decision about the destructive half of
 * the surface: a dropped or mistyped {@code ?repository=} would silently widen a scoped call into a
 * whole-store one, while a mistyped segment is a 404 and can never widen into anything. Nothing
 * about the rules changes with scope — a repository's plan is its share of its type's one plan,
 * reconciled against the same census, never a second policy — and nothing about the safety changes
 * either: the abort on an unreadable pin is still whole-run, and the blob unlinks are still
 * reconciled over the whole store, so a blob another repository names is never a candidate.
 *
 * <p><b>The guard, honestly.</b> The sweep is a write, and {@code gc} sits in {@code
 * ArtifactsTokenFilter}'s guarded prefix set, so the {@code POST} inherits the {@code
 * X-Artifacts-Token} check by construction. But the live deployment ships {@code
 * qits.artifacts.token} <b>blank</b>, which makes that filter a no-op — the guard is inert until
 * the platform's auth posture lands (the standing qits-platform-idp direction; per the recorded
 * no-interim-token-schemes decision, nothing here invents one meanwhile). Until then the real
 * front door is the gateway's session policy, and the sweep's own safety is its content: on a
 * store younger than the grace window it deletes nothing, provably.
 *
 * <p><b>Pins may arrive in the request.</b> {@code POST /gc/plan} is the {@code GET}'s twin for
 * that and nothing else, and both sweeps take the same optional body: {@code {"pins":
 * {"deployments": …, "ciDaemon": …, "dependencies": …, "configuredImages": …}}}, each member the
 * peer's response verbatim. It is how
 * qits-platform-orchestrator gives one platform-wide pin set — read once, by the one component
 * holding an idp client for every peer — to every deleter in a run. A request without a body is
 * unchanged in every respect, and a supplied set never widens what a run may delete: a member left
 * out is that source unanswered, and the run refuses.
 *
 * <p>The registries' {@code 405} on client deletes is unaffected: no client gains deletion
 * semantics from any of this. The sweep is an operator invocation of an internal process, and its
 * receipt is the plan report's executed twin.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
public class GcPlanController {

  @Inject GcPlanner planner;
  @Inject GcSweepExecutor executor;
  @Inject ObjectMapper objectMapper;

  /**
   * Every repository type, its strategy's plan or the reason it has none, the cross-type sweep, and
   * the row-less pool no plan may touch. Costs one census — a disk walk and a pass over the rows.
   */
  @GET
  @Path("/plan")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public GcPlanReport plan() {
    return planner.plan();
  }

  /**
   * The same report, over pins the caller supplied.
   *
   * <p>A {@code POST} because it carries a body, not because it does anything: this route reads and
   * deletes nothing, exactly like the {@code GET}. It exists for qits-platform-orchestrator, which
   * reads the platform's pins once per run and hands the same set to every deleter — this plan is
   * the review artifact for the sweep that follows it, and a plan read against different pins than
   * the sweep applies would not be one.
   *
   * <p><b>{@code qits:system} may read it as well as {@code qits:admin}</b>, which is the one place
   * this route's roles differ from the {@code GET}'s. The caller that needs it is a machine —
   * qits-platform-orchestrator authenticates as {@code qits:system,qits-platform:system} and never
   * holds {@code qits:admin} — and it is the same machine that is allowed to run the sweep
   * afterwards. Letting it execute a plan it may not read would be the strange posture, not this
   * one. Nothing is widened for people: a person still needs {@code qits:admin}, and the route
   * reads.
   *
   * <p>With no body, or a body without {@code pins}, it answers exactly what the {@code GET}
   * answers.
   *
   * <p><b>Being a {@code POST} does put it inside {@code AdminWriteGuard}</b>, which claims the
   * {@code gc} prefix for every write method. The guard is inert until {@code
   * qits.auth.machine.required} is on; after that a caller of this reader needs the machine
   * audience the sweep needs, which is a consequence of the verb rather than a decision about the
   * route. A caller that wants neither sends the {@code GET}.
   */
  @POST
  @Path("/plan")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  public GcPlanReport planWithSuppliedPins(String body) {
    return planner.plan(suppliedPins(body));
  }

  /**
   * Every repository's expected cleanup, from <b>one</b> run of the same plan.
   *
   * <p>What the explorer's repository listing reads. It costs exactly what {@code /gc/plan} costs —
   * one census, one pin fetch — and is a stripped reading of that report rather than a second
   * computation of it: identity lists and blob digests are left out here and answered per
   * repository below. A route that answered one repository at a time would make a listing cost N
   * censuses and 2N cross-service calls, and would read the live pins N times in a run that is
   * supposed to read them once.
   */
  @GET
  @Path("/repositories")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public GcRepositoriesPlanResponse repositories() {
    return planner.planForRepositories();
  }

  /**
   * One repository's plan in full: the identities that would die and the ones that would not, each
   * with its rule, the two blob figures, the configuration echo, the pins provenance and the
   * row-less pool.
   *
   * <p>This is the review artifact for a scoped sweep, and it is a separate URL from the sweep for
   * the same reason the global pair is: a report that could not be read without side effects would
   * be the one report nobody could review. 404 for a repository that does not exist — a path
   * segment cannot be dropped the way a query parameter can, so a mistyped name here can never
   * silently widen into something else.
   */
  @GET
  @Path("/repositories/{repository}/plan")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public GcRepositoryPlanReport repositoryPlan(@PathParam("repository") String repository) {
    return planner.planForRepository(repository);
  }

  /**
   * Executes one sweep: a fresh plan, its identity deletions, the blob unlinks, and the receipt —
   * what was deleted per type, what was unlinked, what the grace window withheld, and the
   * untouchable pool restated.
   *
   * <p>A {@code POST} and nothing else, so a crawler or a probing {@code GET} can never delete a
   * byte. The plan it applies is computed inside this request; there is no way to submit one.
   */
  @POST
  @Path("/sweep")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public GcSweepReport sweep(String body) {
    return executor.sweep(suppliedPins(body));
  }

  /**
   * Executes one sweep of <b>one repository</b>: a fresh plan of its type, scoped to it, its
   * identity deletions, the blob unlinks, and the receipt.
   *
   * <p>The plan it applies is computed inside this request and scoped inside it too — there is no
   * way to submit one, at this scope any more than at the other. That rule is not a formality here:
   * a stored plan is a plan on stale facts, and a per-repository invocation is exactly the shape
   * someone would be tempted to hand a reviewed plan back to.
   *
   * <p>A {@code POST} and nothing else, so a crawler or a probing {@code GET} can never delete a
   * byte, and the scope is a path segment so a request that loses it is a 404 rather than a
   * whole-store sweep. 404 for a repository that does not exist; a repository whose type nobody
   * collects is <b>not</b> an error — it answers a receipt with its reason and zeros.
   *
   * <p><b>Scope does not narrow the abort rule.</b> A pin source that cannot answer ends this run
   * before the census with nothing deleted, exactly as it ends a whole-store run, because blobs
   * dedupe globally and one repository's released bytes can be the last local reference to content
   * a pin names by digest.
   */
  @POST
  @Path("/repositories/{repository}/sweep")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public GcRepositorySweepReport repositorySweep(
      @PathParam("repository") String repository, String body) {
    return executor.sweep(repository, suppliedPins(body));
  }

  /**
   * The optional body, read into the documents the engine should use — or null for "fetch them".
   *
   * <p>Taken as a raw string rather than as a bound entity so that a caller sending no body, and no
   * {@code Content-Type} with it, is still the plain no-body call the SPA and every existing
   * operator recipe make. Malformed JSON is a 400 rather than a silent fall back to the HTTP
   * readers: a caller that meant to supply pins and mistyped them must not be answered with a run
   * that read different ones.
   */
  private GcSuppliedPins suppliedPins(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return GcSuppliedPins.inRequestBody(objectMapper.readTree(body)).orElse(null);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException wrong) {
      throw new BadRequestException(
          "the gc request body must be {\"pins\":{\"deployments\":…,\"ciDaemon\":…,"
              + "\"dependencies\":…,\"configuredImages\":…}} or absent: "
              + wrong.getMessage());
    }
  }
}
