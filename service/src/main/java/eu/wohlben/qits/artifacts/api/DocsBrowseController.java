package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.DocsSiteSummary;
import eu.wohlben.qits.artifacts.dto.DocsVersionSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Browsing a {@code docs} repository: which sites it holds, and what versions each has.
 *
 * <p><b>This is not the wire catalog, and the difference is the point.</b> {@code GET
 * /artifacts/docs/<repo>} already lists every site with its version count and its newest version —
 * open and tokenless on qits-net, because qits-platform-docs renders it. What it deliberately does
 * not carry is a size, and this surface does: a byte figure on the open route would be an inventory
 * of the store to anybody who can reach it, whereas here the caller already holds {@code
 * qits:admin}. So the two are separate readers over the same rows rather than one reader with a
 * flag, and {@code ArtifactExplorerService.listDocsSites} does not delegate to {@code
 * DocsRegistryService.listCatalog}.
 *
 * <p><b>Listing a version's FILES is deliberately absent here.</b> The wire answers it at {@code GET
 * /artifacts/docs/<repo>/<site>/-/<version>}, which is where a reader that wants to know a bundle's
 * shape already looks; adding a second spelling under {@code /artifacts/api} would be a third place
 * the same list is assembled. It becomes worth adding the day the explorer needs per-file sizes,
 * which the wire document does not carry — and that is a decision to take then, not a gap to close
 * now.
 *
 * <p>The class path is spelled down to {@code /docs} rather than stopping at {@code
 * /repositories/{repo}} on purpose — a class path that is a strict prefix of {@code BlobController}'s
 * would put two resources in the running for {@code /repositories/x/blobs}. The rule is
 * {@code ImageBrowseController}'s and it binds every resource added here.
 *
 * <p>{@code {site}} takes {@code .+} because a site name may contain slashes:
 * {@code @userflows/qits-artifacts} is one site, not a scope and a name. Both spellings resolve — the
 * percent-encoded one a browser produces and the literal one a person pastes — which is the stance
 * the image and package listings take, and the one {@code ArtifactBrowseControllerTest} pins.
 * <b>The docs WIRE accepts only the literal spelling</b> ({@code DocsPaths} deliberately has no
 * percent-encoded separator, because its publishers are {@code curl} and qits-docs); this surface is
 * reached from a browser, so it has to answer both.
 *
 * <p>Reads, so unguarded by {@code AdminWriteGuard}, which covers write methods only — but
 * {@code @RolesAllowed({"qits:admin", "qits:agent"})} like every browse surface. Hidden from the OpenAPI document
 * like every operation this service ships; the contract is written out in the README.
 */
@Path("/repositories/{repo}/docs")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
public class DocsBrowseController {

  @Inject ArtifactExplorerService explorer;

  public record ListDocsSitesResponse(List<DocsSiteSummary> sites) {}

  /** 404 for an unknown repository, 400 for one that is not a docs repository. */
  @GET
  @Operation(hidden = true)
  public ListDocsSitesResponse sites(@PathParam("repo") String repo) {
    return new ListDocsSitesResponse(explorer.listDocsSites(repo));
  }

  public record ListDocsVersionsResponse(List<DocsVersionSummary> versions) {}

  /**
   * Newest first, matching the wire's own version listing — an unknown site answers an empty list
   * rather than a 404, because a site is not a row and only the repository can be absent.
   */
  @GET
  @Path("/{site:.+}/versions")
  @Operation(hidden = true)
  public ListDocsVersionsResponse versions(
      @PathParam("repo") String repo, @PathParam("site") String site) {
    return new ListDocsVersionsResponse(explorer.listDocsVersions(repo, site));
  }
}
