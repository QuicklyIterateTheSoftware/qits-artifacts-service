package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.blobstore.control.ArtifactMetadataHeaders;
import eu.wohlben.qits.blobstore.control.ArtifactQueryService;
import eu.wohlben.qits.blobstore.control.BlobService;
import eu.wohlben.qits.blobstore.dto.ArtifactRecordDto;
import eu.wohlben.qits.blobstore.dto.UploadResult;
import eu.wohlben.qits.blobstore.mapper.ArtifactRecordMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The artifacts blob boundary (docs/epics/qits-artifacts/): upload (raw body stream + {@code
 * X-Artifacts-Meta-*} headers), serve by content id, and metadata query with the {@code latest}
 * collapse. Hidden from the OpenAPI document (a wire/system API). Uploads (POST) are token-guarded
 * by {@code ArtifactsTokenFilter}; GET is open so a blob is usable directly as an {@code
 * <img>}/{@code <video>} src.
 *
 * <p>The upload injects the body as an {@link InputStream} — RESTEasy Reactive streams it, so
 * {@link BlobService} writes it to disk incrementally (no whole-video buffer). The wire size is
 * bounded by {@code quarkus.http.limits.max-body-size} (a hard global ceiling on every route — see
 * the service config), sized to the largest type cap; the specific per-type cap is enforced while
 * streaming.
 */
@Path("/repositories/{repo}/blobs")
public class BlobController {

  /**
   * Metadata rides these request headers (flat strings map cleanly to headers). Case-insensitive.
   * The parsing itself is {@link ArtifactMetadataHeaders}', shared with the docs wire.
   */
  static final String META_PREFIX = ArtifactMetadataHeaders.PREFIX;

  @Inject BlobService blobService;

  @Inject ArtifactQueryService queryService;

  @Inject ArtifactRecordMapper recordMapper;

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public Response upload(
      @PathParam("repo") String repo,
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @Context HttpHeaders headers,
      InputStream body) {
    UploadResult result = blobService.upload(repo, contentType, metadataFrom(headers), body);
    return Response.status(Response.Status.CREATED).entity(result).build();
  }

  @GET
  @Path("/{id}")
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public Response serve(@PathParam("repo") String repo, @PathParam("id") String id) {
    BlobService.BlobContent content = blobService.serve(repo, id);
    return Response.ok(content.stream())
        .type(content.mediatype())
        .header(HttpHeaders.CONTENT_LENGTH, content.size())
        // Content-addressed ids never change meaning — cache aggressively and immutably.
        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
        .build();
  }

  public record ListBlobsResponse(List<ArtifactRecordDto> records) {}

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(hidden = true)
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  public ListBlobsResponse query(
      @PathParam("repo") String repo,
      @Context UriInfo uriInfo,
      @QueryParam("accessed-after") String accessedAfter,
      @QueryParam("accessed-before") String accessedBefore,
      @QueryParam("created-after") String createdAfter,
      @QueryParam("created-before") String createdBefore,
      @QueryParam("min-size") String minSize,
      @QueryParam("max-size") String maxSize,
      @QueryParam("never-accessed") String neverAccessed) {
    Map<String, String> predicates = new LinkedHashMap<>();
    boolean latest = false;
    for (var entry : uriInfo.getQueryParameters().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue().isEmpty() ? "" : entry.getValue().get(0);
      if (key.startsWith("meta.")) {
        predicates.put(key.substring("meta.".length()), value);
      } else if (key.equals("latest")) {
        latest = value.isBlank() || Boolean.parseBoolean(value);
      }
    }
    var records =
        queryService
            .query(
                repo,
                predicates,
                latest,
                ArtifactListFilters.parse(
                    accessedAfter, accessedBefore, createdAfter, createdBefore, minSize, maxSize,
                    neverAccessed))
            .stream()
            .map(recordMapper::toDto)
            .toList();
    return new ListBlobsResponse(records);
  }

  /**
   * Collects {@code X-Artifacts-Meta-<key>} request headers into the flat metadata map — the JAX-RS
   * thin adapter over the shared reading (which also strips server-owned keys and enforces the wire
   * caps; a violation surfaces as this boundary's ordinary 400).
   */
  private static Map<String, String> metadataFrom(HttpHeaders headers) {
    try {
      return ArtifactMetadataHeaders.fromLists(headers.getRequestHeaders());
    } catch (IllegalArgumentException capped) {
      throw new jakarta.ws.rs.BadRequestException(capped.getMessage(), capped);
    }
  }
}
