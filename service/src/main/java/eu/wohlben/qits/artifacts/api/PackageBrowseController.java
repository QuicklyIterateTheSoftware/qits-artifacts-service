package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.PackageSummary;
import eu.wohlben.qits.artifacts.dto.PackageVersionSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Browsing an npm repository — hosted or proxy, the same two routes.
 *
 * <p>npm has no listing API either: {@code /-/all} and {@code /-/v1/search} are absent from this
 * registry, and a packument answers for a name you already know.
 *
 * <p>The type is not a filter here, it is the structure: proxied content outweighs published content
 * 1,971 to 1 by bytes on this store, so listing them undifferentiated would bury our own two
 * packages at 0.6% of the rows. A caller asks one repository at a time.
 *
 * <p>{@code {package}} takes {@code .+} because a scoped name contains a slash. Both spellings
 * resolve — {@code @qits%2Fui-components} and {@code @qits/ui-components} — which is the same stance
 * {@code NpmPaths} takes on the registry's own routes.
 */
@Path("/repositories/{repo}/packages")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
public class PackageBrowseController {

  @Inject ArtifactExplorerService explorer;

  public record ListPackagesResponse(List<PackageSummary> packages) {}

  /** 404 for an unknown repository, 400 for one that is neither npm type. */
  @GET
  @Operation(hidden = true)
  public ListPackagesResponse packages(@PathParam("repo") String repo) {
    return new ListPackagesResponse(explorer.listPackages(repo));
  }

  public record ListVersionsResponse(List<PackageVersionSummary> versions) {}

  @GET
  @Path("/{package:.+}/versions")
  @Operation(hidden = true)
  public ListVersionsResponse versions(
      @PathParam("repo") String repo, @PathParam("package") String packageName) {
    return new ListVersionsResponse(explorer.listVersions(repo, packageName));
  }
}
