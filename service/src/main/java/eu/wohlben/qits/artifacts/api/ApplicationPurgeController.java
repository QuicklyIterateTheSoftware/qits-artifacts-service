package eu.wohlben.qits.artifacts.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.gc.ApplicationPurge;
import eu.wohlben.qits.artifacts.gc.dto.ApplicationPurgeReport;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;

/**
 * The decommission door: everything a retired application and its repositories published, removed
 * from every store type (qits-1941). See {@link ApplicationPurge} for what matches and why.
 *
 * <p><b>The platform's alone.</b> {@code qits:system} and nothing else, like the GC sweeps: it
 * deletes published packages outright, past every pin and grace a sweep honours, so no person and
 * no agent reaches it with their own token. {@code applications} is in {@link AdminWriteGuard}'s
 * prefix set, so with the gate on the machine-token check applies to it as well.
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
public class ApplicationPurgeController {

  @Inject ApplicationPurge purge;
  @Inject ObjectMapper objectMapper;

  /**
   * Removes every package of the application and of the repositories the body names, and answers
   * what went. Idempotent: a second call answers an empty {@code removed}.
   *
   * <p>The body is taken raw, as the sweeps take theirs, so a call with no body and no {@code
   * Content-Type} is the plain "the application alone" call. Malformed JSON is a 400 rather than a
   * purge of the application alone: a caller that meant to name repositories must not be answered
   * with a run that left theirs standing.
   */
  @POST
  @Path("/{application}/purge")
  @Operation(operationId = "purgeApplication")
  @jakarta.annotation.security.RolesAllowed("qits:system")
  public ApplicationPurgeReport purge(
      @PathParam("application") String application,
      @RequestBody(
              required = false,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON,
                      schema = @Schema(type = SchemaType.OBJECT)))
          String body) {
    return purge.purge(application, repositoriesIn(body));
  }

  private List<String> repositoriesIn(String body) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    JsonNode document;
    try {
      document = objectMapper.readTree(body);
    } catch (JsonProcessingException wrong) {
      throw refused(wrong.getMessage());
    }
    if (!document.isObject()) {
      throw refused("not an object");
    }
    JsonNode repositories = document.path("repositories");
    if (repositories.isMissingNode() || repositories.isNull()) {
      return List.of();
    }
    if (!repositories.isArray()) {
      throw refused("repositories is not an array");
    }
    List<String> names = new ArrayList<>();
    for (JsonNode repository : repositories) {
      if (!repository.isTextual()) {
        throw refused("every repository must be a string");
      }
      names.add(repository.asText());
    }
    return names;
  }

  private static BadRequestException refused(String why) {
    return new BadRequestException(
        "the purge request body must be {\"repositories\":[\"<repository>\",…]} or absent: " + why);
  }
}
