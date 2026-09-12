package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.StoreSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The whole store, in seven numbers that do not add up.
 *
 * <p>The first resource this service serves outside {@code /repositories/}, which is worth knowing
 * for one reason: {@code ArtifactsTokenFilter} matches by path prefix, and it now names this segment
 * too, so a write added here would be guarded rather than quietly open. Nothing here writes.
 *
 * <p><b>{@code qits:system} may read it as well as {@code qits:admin}</b>, for the reason {@code
 * GcPlanController}'s {@code POST /gc/plan} carries the same pair: qits-platform-orchestrator is a
 * machine, it holds {@code qits:system}, and it reads this summary before and after every GC run so
 * the run's own receipt states what the sweep actually cost. A machine allowed to execute the sweep
 * and not to read the number it moved would be the strange posture. Nothing is widened for people —
 * a person still needs {@code qits:admin} — and the route reads.
 */
@Path("/store")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
public class StoreController {

  @Inject ArtifactExplorerService explorer;

  @GET
  @Path("/summary")
  @Operation(hidden = true)
  public StoreSummary summary() {
    return explorer.storeSummary();
  }
}
