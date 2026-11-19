package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.MavenPackageSummary;
import eu.wohlben.qits.artifacts.dto.MavenVersionSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Read-only Maven coordinate drill-down for the artifacts explorer. */
@Path("/repositories/{repo}/maven-packages")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent", "qits:system", "qits:ci-run"})
public class MavenBrowseController {
  @Inject ArtifactExplorerService explorer;
  // Named apart: the npm and maven listings both declare a ListPackagesResponse, and two schemas
  // with one simple name would be numbered rather than named in the OpenAPI document (qits-2316).
  @Schema(name = "ListMavenPackagesResponse")
  public record ListPackagesResponse(List<MavenPackageSummary> packages) {}
  public record ListVersionsResponse(List<MavenVersionSummary> versions) {}
  @GET
  // Named: golden-masters/ records it under this operationId (qits-2316); qits-landing-app's
  // artifacts store page reads it.
  @Operation(operationId = "listMavenPackages")
  public ListPackagesResponse packages(@PathParam("repo") String repo) {
    return new ListPackagesResponse(explorer.listMavenPackages(repo));
  }
  @GET @Path("/{coordinate:.+}/versions") @Operation(hidden = true)
  public ListVersionsResponse versions(@PathParam("repo") String repo, @PathParam("coordinate") String coordinate) {
    return new ListVersionsResponse(explorer.listMavenVersions(repo, coordinate));
  }
}
