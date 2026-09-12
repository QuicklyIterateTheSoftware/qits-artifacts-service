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

/** Read-only Maven coordinate drill-down for the artifacts explorer. */
@Path("/repositories/{repo}/maven-packages")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
public class MavenBrowseController {
  @Inject ArtifactExplorerService explorer;
  public record ListPackagesResponse(List<MavenPackageSummary> packages) {}
  public record ListVersionsResponse(List<MavenVersionSummary> versions) {}
  @GET @Operation(hidden = true)
  public ListPackagesResponse packages(@PathParam("repo") String repo) {
    return new ListPackagesResponse(explorer.listMavenPackages(repo));
  }
  @GET @Path("/{coordinate:.+}/versions") @Operation(hidden = true)
  public ListVersionsResponse versions(@PathParam("repo") String repo, @PathParam("coordinate") String coordinate) {
    return new ListVersionsResponse(explorer.listMavenVersions(repo, coordinate));
  }
}
