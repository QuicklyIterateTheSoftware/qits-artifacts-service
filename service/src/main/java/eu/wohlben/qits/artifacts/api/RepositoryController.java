package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.blobstore.control.ArtifactRepositoryService;
import eu.wohlben.qits.blobstore.dto.ArtifactRepositoryDto;
import eu.wohlben.qits.blobstore.control.RepositoryTypeProfiles;
import eu.wohlben.qits.artifacts.dto.RepositorySummary;
import eu.wohlben.qits.blobstore.mapper.ArtifactRepositoryMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The artifacts repository lifecycle boundary (docs/epics/qits-artifacts/). Thin controllers over
 * the {@code artifacts} module's services; all paths are relative to {@code quarkus.rest.path},
 * which is {@code /artifacts/api} — so this resource is served at {@code
 * /artifacts/api/repositories}. The {@code artifacts} the path used to carry is gone: the gateway
 * segment already says it. Hidden from the OpenAPI document (a wire/system API — like the
 * capture/OTLP receivers — so {@code docs/openapi.yml} and the generated Angular client stay
 * untouched).
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject ArtifactRepositoryService repositoryService;

  @Inject ArtifactExplorerService explorer;

  @Inject ArtifactRepositoryMapper mapper;

  @Inject RepositoryTypeProfiles repositoryTypes;

  /**
   * The request body, and the reason it carries a plain {@code String}.
   *
   * <p>The type used to deserialize straight into an enum through a {@code @JsonCreator}, so an
   * unknown value failed inside Jackson with a message about a Java type nobody outside this
   * codebase has heard of. Types are registered openly now — there is no enum to bind to — so the
   * wire form arrives as text and {@link RepositoryTypeProfiles#requireWireName} resolves it,
   * answering an unregistered value with a 400 that names what IS registered. Which is the answer a
   * caller can act on, and the one that stays correct when a deployment ships a different set of
   * format modules.
   */
  public record EnsureRepositoryRequest(@NotNull String type) {
    public record Response(ArtifactRepositoryDto repository) {}
  }

  /**
   * Idempotently create/ensure a repository with a type. Write path — token-guarded in `service`.
   */
  @PUT
  @Path("/{repo}")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public EnsureRepositoryRequest.Response ensure(
      @PathParam("repo") String repo, @Valid EnsureRepositoryRequest request) {
    var profile = repositoryTypes.requireWireName(request.type());
    var entity = repositoryService.ensure(repo, profile.key());
    return new EnsureRepositoryRequest.Response(mapper.toDto(entity));
  }

  public record ListRepositoriesResponse(List<RepositorySummary> repositories) {}

  /**
   * The explorer's top level: every repository, with the one count and the one size its type can
   * answer. The rows carry more than the ensure response's {@link ArtifactRepositoryDto} because
   * this is the only place that enumerates anything — see {@code ArtifactExplorerService}.
   */
  @GET
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public ListRepositoriesResponse list() {
    return new ListRepositoriesResponse(explorer.listRepositories());
  }
}
