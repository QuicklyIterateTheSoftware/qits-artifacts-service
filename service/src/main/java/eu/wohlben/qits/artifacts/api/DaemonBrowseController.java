package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.DaemonSummary;
import eu.wohlben.qits.artifacts.dto.DaemonVersionSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Browsing a {@code daemon-binaries} repository: which daemons it holds, and what versions each has.
 *
 * <p>The wire has no enumeration of either. {@code /artifacts/daemons} answers 404 for the bare
 * segment and every route below it is version-addressed, so a bootstrap script can fetch a binary it
 * already knows the coordinates of and nothing can ask what exists. This is that question, on the
 * JAX-RS surface, for a human.
 *
 * <p><b>The daemon wire carries no repository segment; this listing does.</b> {@code DaemonPaths}'
 * one departure from npm's and maven's grammar is that the platform's own daemons are the only
 * namespace, so the repository is the seeded {@code daemons} row rather than a path segment a
 * publisher can mint. The explorer's subject is an {@code artifact_repository} row throughout —
 * repositories, then what is inside one — and giving this type a second addressing scheme would make
 * the SPA's drill-down a special case for the one type whose wire elides the segment. So the URL is
 * {@code /repositories/daemons/daemons} today, and stays correct on the day a second namespace is a
 * decision somebody takes.
 *
 * <p>The class path is spelled down to {@code /daemons} rather than stopping at {@code
 * /repositories/{repo}} on purpose — a class path that is a strict prefix of {@code BlobController}'s
 * would put two resources in the running for {@code /repositories/x/blobs}. The rule is
 * {@code ImageBrowseController}'s and it binds every resource added here.
 *
 * <p>{@code {daemon}} is a plain segment, not {@code .+}: {@code DaemonPaths} bounds a daemon name
 * to {@code [a-z0-9][a-z0-9._-]{0,63}}, which cannot contain a slash, so the encoded/literal question
 * the image and package listings have to answer does not arise here.
 *
 * <p>Reads, so unguarded by {@code AdminWriteGuard}, which covers write methods only — but
 * {@code @RolesAllowed("qits:admin")} like every browse surface. Hidden from the OpenAPI document
 * like every operation this service ships; the contract is written out in the README.
 */
@Path("/repositories/{repo}/daemons")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
public class DaemonBrowseController {

  @Inject ArtifactExplorerService explorer;

  public record ListDaemonsResponse(List<DaemonSummary> daemons) {}

  /** 404 for an unknown repository, 400 for one that is not a daemon repository. */
  @GET
  @Operation(hidden = true)
  public ListDaemonsResponse daemons(@PathParam("repo") String repo) {
    return new ListDaemonsResponse(explorer.listDaemons(repo));
  }

  public record ListDaemonVersionsResponse(List<DaemonVersionSummary> versions) {}

  /**
   * Newest first, matching the wire's own version listing — an unknown daemon answers an empty list
   * rather than a 404, because a daemon is not a row and only the repository can be absent.
   */
  @GET
  @Path("/{daemon}/versions")
  @Operation(hidden = true)
  public ListDaemonVersionsResponse versions(
      @PathParam("repo") String repo, @PathParam("daemon") String daemon) {
    return new ListDaemonVersionsResponse(explorer.listDaemonVersions(repo, daemon));
  }
}
