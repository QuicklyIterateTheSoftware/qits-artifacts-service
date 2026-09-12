package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.control.ArtifactExplorerService;
import eu.wohlben.qits.artifacts.dto.ImageSummary;
import eu.wohlben.qits.artifacts.dto.ImageManifestSummary;
import eu.wohlben.qits.artifacts.dto.ImageTagSummary;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Browsing an {@code oci-images} repository: what images are in it, and what tags each has.
 *
 * <p>These are the enumerations the Distribution API deliberately does not offer — {@code
 * /v2/_catalog} is refused here, and {@code tags/list} answers 200 with an empty array for an image
 * that does not exist, so discovery by probing is impossible. This is a JSON API for a human, on the
 * JAX-RS surface, not an extension of the registry's wire protocol.
 *
 * <p>Reads, so unguarded: {@code ArtifactsTokenFilter} covers write methods only. Hidden from the
 * OpenAPI document like every operation this service ships; the contract is written out in the
 * README.
 *
 * <p>The class path is spelled down to {@code /images} rather than stopping at {@code
 * /repositories/{repo}} on purpose — a class path that is a strict prefix of {@code
 * BlobController}'s would put two resources in the running for {@code /repositories/x/blobs}.
 *
 * <p>{@code {image}} takes {@code .+} because an image name may contain slashes: {@code
 * qits/build-images/ci-base} is repository {@code qits}, image {@code build-images/ci-base}. Both
 * spellings resolve — the percent-encoded one a browser produces and the literal one a person
 * pastes — which is the same stance the npm routes take on scoped names.
 */
@Path("/repositories/{repo}/images")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
public class ImageBrowseController {

  @Inject ArtifactExplorerService explorer;

  public record ListImagesResponse(List<ImageSummary> images) {}

  /** 404 for an unknown repository, 400 for one that is not an image repository. */
  @GET
  @Operation(hidden = true)
  public ListImagesResponse images(@PathParam("repo") String repo) {
    return new ListImagesResponse(explorer.listImages(repo));
  }

  public record ListTagsResponse(List<ImageTagSummary> tags) {}

  /**
   * An unknown image answers an empty list, not a 404 — an image is not a row, so there is nothing
   * to be absent, and that is what {@code /v2/<name>/tags/list} does too.
   */
  @GET
  @Path("/{image:.+}/tags")
  @Operation(hidden = true)
  public ListTagsResponse tags(
      @PathParam("repo") String repo,
      @PathParam("image") String image,
      @QueryParam("accessed-after") String accessedAfter,
      @QueryParam("accessed-before") String accessedBefore,
      @QueryParam("created-after") String createdAfter,
      @QueryParam("created-before") String createdBefore,
      @QueryParam("min-size") String minSize,
      @QueryParam("max-size") String maxSize,
      @QueryParam("never-accessed") String neverAccessed) {
    return new ListTagsResponse(
        explorer.listTags(
            repo,
            image,
            ArtifactListFilters.parse(
                accessedAfter, accessedBefore, createdAfter, createdBefore, minSize, maxSize,
                neverAccessed)));
  }

  public record ListManifestsResponse(List<ImageManifestSummary> manifests) {}

  @GET
  @Path("/{image:.+}/manifests")
  @Operation(hidden = true)
  public ListManifestsResponse manifests(
      @PathParam("repo") String repo,
      @PathParam("image") String image,
      @QueryParam("accessed-after") String accessedAfter,
      @QueryParam("accessed-before") String accessedBefore,
      @QueryParam("created-after") String createdAfter,
      @QueryParam("created-before") String createdBefore,
      @QueryParam("min-size") String minSize,
      @QueryParam("max-size") String maxSize,
      @QueryParam("never-accessed") String neverAccessed) {
    return new ListManifestsResponse(
        explorer.listManifests(
            repo,
            image,
            ArtifactListFilters.parse(
                accessedAfter, accessedBefore, createdAfter, createdBefore, minSize, maxSize,
                neverAccessed)));
  }
}
