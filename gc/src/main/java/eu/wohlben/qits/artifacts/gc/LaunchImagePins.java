package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * What a launching service would pull <b>today</b> — the effective image versions, answered by the
 * service that would do the pulling out of the configuration it is actually running with.
 *
 * <p><b>The launching service is the only source for these images.</b> Their versions come from
 * the service's own config, built from its pom, so no other service holds them. Without this answer
 * an image every start pulls is named by nothing, and under a P3D window it is kept alive by access
 * alone, which is exactly the inference the short windows gave up on.
 *
 * <p><b>One interface, two ports, and the split is not cosmetic.</b> {@link WorkspacesLaunchPins}
 * and {@link ProjectsLaunchPins} are separate types because they are separate sources: two injection
 * points, two urls, two entries in a run's pins section, and two independent failures. What they
 * share is the document shape, which is why the parse below is shared — one reading of one shape,
 * with the service that answered it named in every refusal.
 */
public interface LaunchImagePins {

  /**
   * One image a launch would pull.
   *
   * @param image the registry-relative image name, e.g. {@code qits/workspace} — the answering
   *     service strips a leading registry host from its configured repo, so this joins directly
   *     against {@code <repository row>/<image>} here
   * @param version the effective version — the tag a start would pull right now
   * @param launches which kind of start pulls it ({@code workspace}, {@code editor}, {@code agent},
   *     {@code refinement}), provenance for the receipt and nothing else
   */
  record LaunchPin(String image, String version, String launches) {}

  /**
   * Every image this service would launch, at its currently resolved configuration.
   *
   * @throws RuntimeException the service could not be reached or its answer could not be read —
   *     never an empty list on doubt, because an empty list reads as "this service launches nothing"
   *     and condemns the image its next start pulls
   */
  List<LaunchPin> pins();

  /** Where this implementation reads them from, for the report's pins section. */
  default String url() {
    return "(not reported by " + getClass().getSimpleName() + ")";
  }

  /**
   * A launch-pin answer, read into pins — the parsing half, with no transport in it.
   *
   * <p>Shared by both readers and by both supplied-document paths for the reason {@link
   * CdHttpDeploymentPins#parse} gives: the same document arrives two ways, fetched by a reader or
   * handed in by qits-platform-orchestrator, and one reader for both is what stops one document
   * being read two ways. The {@code service} argument is what keeps the sharing honest — every
   * refusal still names which service answered, so a report never says "a launch service" where a
   * reviewer needs "qits-projects".
   *
   * <p>{@code launches} is parsed and dropped into the record as provenance. It decides nothing: two
   * kinds of start pulling the same image are one keep, and a document that named a start this store
   * has never heard of is still a perfectly readable pin.
   *
   * @param body the response body, verbatim
   * @param service which service answered, for the message a refusal carries
   * @param origin where it came from, for the same reason
   * @throws IllegalStateException the shape is one this cannot read. Never an empty list on doubt.
   */
  static List<LaunchPin> parse(JsonNode body, String service, String origin) {
    if (body == null || !body.isObject()) {
      throw new IllegalStateException(service + " answered a non-object body for " + origin);
    }
    JsonNode pins = body.get("pins");
    if (pins == null || !pins.isArray()) {
      throw new IllegalStateException(service + " answered without a 'pins' array for " + origin);
    }
    List<LaunchPin> rows = new ArrayList<>();
    for (JsonNode pin : pins) {
      String image = text(pin, "image");
      String version = text(pin, "version");
      if (image == null || version == null) {
        // A row this cannot name pins nothing, and guessing is how a live image gets deleted.
        // Refusing the whole answer is the honest response to a shape this does not know: this is
        // the version that IS being pulled.
        throw new IllegalStateException(service + " returned a pin with no image or no version");
      }
      rows.add(new LaunchPin(image, version, text(pin, "launches")));
    }
    return List.copyOf(rows);
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row == null ? null : row.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String read = value.asText().trim();
    return read.isEmpty() ? null : read;
  }
}
