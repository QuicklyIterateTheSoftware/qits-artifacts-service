package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The project launch-pin adapter against a stub serving qits-projects' real response shape.
 *
 * <p>Same document and same parse as {@link WorkspacesHttpLaunchPinsTest}, which is exactly why this
 * class exists rather than a second method over there: the two readers are two <b>sources</b>, and
 * the properties that are not shared — which url is dialled, which service a refusal names — are the
 * ones a shared parse could quietly get wrong. The shape cases live with the twin; these are the
 * ones about being a different service.
 *
 * <p>Plain JUnit, no Quarkus, for the reason {@link CdHttpDeploymentPinsTest} gives.
 */
class ProjectsHttpLaunchPinsTest {

  private static final String BODY =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[
         {"image":"qits/project-agent","version":"2026.903.090000","launches":"agent"},
         {"image":"qits/workspace","version":"2026.903.120000","launches":"refinement"}
       ]}
      """;

  @Test
  void theAgentAndRefinementImagesAreReadAndAnImageMayBeSharedWithWorkspaces() throws IOException {
    // qits/workspace is launched by BOTH services, at versions that need not agree — each answers
    // out of its own configuration. Two sets rather than one is what lets a report say which of the
    // two saved a tag, and this row is the case that makes the distinction real.
    try (StubPinService projects = StubPinService.serving("/pins", BODY)) {
      List<LaunchImagePins.LaunchPin> pins = adapter(projects.baseUrl()).pins();

      assertEquals(2, pins.size());
      assertEquals("qits/project-agent", pins.get(0).image());
      assertEquals("agent", pins.get(0).launches());
      assertEquals("qits/workspace", pins.get(1).image());
      assertEquals("2026.903.120000", pins.get(1).version());
      assertEquals("refinement", pins.get(1).launches());
    }
  }

  @Test
  void theUrlIsTheConfiguredBasePlusPinsAndTheRefusalNamesThisServiceAlone() throws IOException {
    // The two halves a shared parse cannot be trusted with: where this reader goes, and what it
    // calls whoever did not answer.
    assertEquals(
        "http://qits-projects:8080/projects/api/pins", adapter("http://qits-projects:8080/projects/api").url());

    try (StubPinService projects = StubPinService.serving("/pins", "{\"launches\":[]}")) {
      String refused =
          assertThrows(IllegalStateException.class, () -> adapter(projects.baseUrl()).pins())
              .getMessage();
      assertTrue(refused.startsWith(ProjectsHttpLaunchPins.SERVICE), refused);
      assertFalse(refused.contains(WorkspacesHttpLaunchPins.SERVICE), refused);
      assertTrue(refused.contains("'pins' array"), refused);
    }
    String unreachable =
        assertThrows(
                IllegalStateException.class, () -> adapter("http://127.0.0.1:1/projects/api").pins())
            .getMessage();
    assertTrue(unreachable.contains(ProjectsHttpLaunchPins.SERVICE), unreachable);
    assertTrue(unreachable.contains("unreachable"), unreachable);
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    try (StubPinService projects = StubPinService.serving("/pins", "{\"pins\":[]}")) {
      assertEquals(List.of(), adapter(projects.baseUrl()).pins());
    }
  }

  /** The adapter as CDI would build it: the two config values and the mapper. */
  private static ProjectsHttpLaunchPins adapter(String baseUrl) {
    ProjectsHttpLaunchPins pins =
        new ProjectsHttpLaunchPins(QitsHttpClients.of(OpenTelemetry.noop(), null));
    pins.baseUrl = baseUrl;
    pins.timeout = Duration.ofSeconds(5);
    pins.objectMapper = new ObjectMapper();
    return pins;
  }
}
