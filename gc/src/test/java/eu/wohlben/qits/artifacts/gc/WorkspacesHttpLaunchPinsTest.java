package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.opentelemetry.api.OpenTelemetry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The workspace launch-pin adapter against a stub serving qits-workspaces' real response shape.
 *
 * <p>{@code GET /workspaces/api/pins} answers {@code {"generatedAt":…,"pins":[{"image","version",
 * "launches"}]}} — one row per image that service would start, at the configuration it is running
 * with. {@code launches} is provenance: it is read into the record and decides nothing, because two
 * kinds of start pulling one image are one keep.
 *
 * <p>Plain JUnit, no Quarkus, for the reason {@link CdHttpDeploymentPinsTest} gives.
 */
class WorkspacesHttpLaunchPinsTest {

  private static final String BODY =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[
         {"image":"qits/workspace","version":"2026.903.120000","launches":"workspace"},
         {"image":"qits/workspace-editor","version":"2026.904.100239","launches":"editor"}
       ]}
      """;

  @Test
  void everyImageAStartWouldPullIsReadWithTheKindOfStartAsProvenance() throws IOException {
    try (StubPinService workspaces = StubPinService.serving("/pins", BODY)) {
      List<LaunchImagePins.LaunchPin> pins = adapter(workspaces.baseUrl()).pins();

      assertEquals(2, pins.size());
      assertEquals("qits/workspace", pins.get(0).image());
      assertEquals("2026.903.120000", pins.get(0).version());
      assertEquals("workspace", pins.get(0).launches());
      assertEquals("qits/workspace-editor", pins.get(1).image());
      assertEquals("editor", pins.get(1).launches());
    }
  }

  @Test
  void aRowWithNoLaunchesIsStillAPin() throws IOException {
    // `launches` is the one field this reader would be wrong to require: it is a label for the
    // receipt, and refusing a document over it would drop a keep for a caption.
    try (StubPinService workspaces =
        StubPinService.serving(
            "/pins", "{\"pins\":[{\"image\":\"qits/workspace\",\"version\":\"1\"}]}")) {
      List<LaunchImagePins.LaunchPin> pins = adapter(workspaces.baseUrl()).pins();

      assertEquals(1, pins.size());
      assertNull(pins.get(0).launches());
    }
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    // A qits-workspaces with no image version configured launches nothing pinnable. The contract
    // omits a row whose version is blank, so this is the shape that arrives — and it must parse.
    try (StubPinService workspaces = StubPinService.serving("/pins", "{\"pins\":[]}")) {
      assertEquals(List.of(), adapter(workspaces.baseUrl()).pins());
    }
  }

  @Test
  void aShapeThisCannotReadIsRefusedRatherThanReadAsNothingLaunched() throws IOException {
    // The hazard, and it is the sharpest of the six: this is the version being pulled RIGHT NOW.
    // A keep-set read as empty here deletes the image the next workspace start needs, and under a
    // P3D window nothing else is holding it.
    try (StubPinService workspaces = StubPinService.serving("/pins", "{\"images\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(workspaces.baseUrl()).pins())
              .getMessage()
              .contains("'pins' array"));
    }
    try (StubPinService workspaces =
        StubPinService.serving("/pins", "{\"pins\":[{\"image\":\"qits/workspace\"}]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(workspaces.baseUrl()).pins())
              .getMessage()
              .contains("no image or no version"));
    }
  }

  @Test
  void everyRefusalNamesThisServiceAndNotItsTwin() throws IOException {
    // The condition the shared parse is allowed under. One reading of one shape is fine; a refusal
    // that said "a launch service" would send whoever is debugging it to the wrong one of two.
    try (StubPinService workspaces = StubPinService.answering("/pins", 503, "down")) {
      String refused =
          assertThrows(IllegalStateException.class, () -> adapter(workspaces.baseUrl()).pins())
              .getMessage();
      assertTrue(refused.contains(WorkspacesHttpLaunchPins.SERVICE), refused);
      assertTrue(refused.contains("503"), refused);
    }
    try (StubPinService workspaces = StubPinService.serving("/pins", "{\"images\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(workspaces.baseUrl()).pins())
              .getMessage()
              .startsWith(WorkspacesHttpLaunchPins.SERVICE));
    }
    String unreachable =
        assertThrows(
                IllegalStateException.class, () -> adapter("http://127.0.0.1:1/workspaces/api").pins())
            .getMessage();
    assertTrue(unreachable.contains("unreachable"), unreachable);
    assertTrue(unreachable.contains(WorkspacesHttpLaunchPins.SERVICE), unreachable);
  }

  /** The adapter as CDI would build it: the two config values and the mapper. */
  private static WorkspacesHttpLaunchPins adapter(String baseUrl) {
    WorkspacesHttpLaunchPins pins =
        new WorkspacesHttpLaunchPins(QitsHttpClients.of(OpenTelemetry.noop(), null));
    pins.baseUrl = baseUrl;
    pins.timeout = Duration.ofSeconds(5);
    pins.objectMapper = new ObjectMapper();
    return pins;
  }
}
