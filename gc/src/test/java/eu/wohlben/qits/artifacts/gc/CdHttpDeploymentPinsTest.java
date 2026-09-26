package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cd pin adapter against a stub serving cd's real response shape.
 *
 * <p>{@code GET /deployments/api/pins} answers {@code {"pins":[{"applicationName":…,"shas":[…]}]}} — one
 * entry per application serving somewhere, the shas a set rather than a sequence. This suite exists
 * because the adapter's job is now entirely that shape: the ACTIVE-plus-previous derivation that
 * used to live here is cd's, and a keep-set derived twice is a keep-set waiting to disagree.
 *
 * <p>Plain JUnit, no Quarkus: the class has three fields and one method, and a container would only
 * slow down proving what a body parses to.
 */
class CdHttpDeploymentPinsTest {

  @Test
  void everyApplicationsShasAreReadOffTheNewResponseShape() throws IOException {
    try (StubPinService cd =
        StubPinService.serving(
            "/pins",
            """
            {"pins":[
              {"applicationName":"qits-artifacts","shas":["aaaa","bbbb"]},
              {"applicationName":"qits-ci","shas":["cccc"]}
            ]}
            """)) {
      List<CdDeploymentPins.ApplicationPin> pins = adapter(cd.baseUrl()).pins();

      assertEquals(2, pins.size());
      assertEquals("qits-artifacts", pins.get(0).applicationName());
      assertEquals(List.of("aaaa", "bbbb"), pins.get(0).shas());
      assertEquals(List.of("cccc"), pins.get(1).shas());
    }
  }

  @Test
  void anApplicationServingNothingIsSimplyAbsentAndThatIsAnAnswer() throws IOException {
    // cd omits an application with no ACTIVE deployment rather than sending it with an empty list,
    // so an empty pins array is a real state — a platform where nothing is deployed — and must parse
    // rather than refuse. What must never happen is inventing a pin for an image cd did not name.
    try (StubPinService cd = StubPinService.serving("/pins", "{\"pins\":[]}")) {
      assertEquals(List.of(), adapter(cd.baseUrl()).pins());
    }
  }

  @Test
  void aShapeThisCannotReadIsRefusedRatherThanReadAsNoPins() throws IOException {
    // The whole reason this throws: a pin list read as empty condemns every sha tag on the platform.
    // A body with no 'pins' array, and a pin with no shas, are both that hazard.
    try (StubPinService cd = StubPinService.serving("/pins", "{\"deployments\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(cd.baseUrl()).pins())
              .getMessage()
              .contains("'pins' array"));
    }
    try (StubPinService cd =
        StubPinService.serving("/pins", "{\"pins\":[{\"applicationName\":\"qits-platform-deployments\"}]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(cd.baseUrl()).pins())
              .getMessage()
              .contains("no applicationName or no shas"));
    }
  }

  @Test
  void aNon200AndAClosedPortBothRefuseWithTheUrlInTheMessage() throws IOException {
    try (StubPinService cd = StubPinService.answering("/pins", 503, "upstream down")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(cd.baseUrl()).pins())
              .getMessage()
              .contains("503"));
    }
    // The deployed failure this suite runs under by default, and the one a closed port stands in
    // for: cd not answering at all. It refuses at once rather than costing a DNS timeout.
    assertTrue(
        assertThrows(
                IllegalStateException.class, () -> adapter("http://127.0.0.1:1/cd/api").pins())
            .getMessage()
            .contains("unreachable"));
  }

  /** The adapter as CDI would build it: the two config values and the mapper. */
  private static CdHttpDeploymentPins adapter(String baseUrl) {
    CdHttpDeploymentPins pins = new CdHttpDeploymentPins();
    pins.baseUrl = baseUrl;
    pins.timeout = Duration.ofSeconds(5);
    pins.objectMapper = new ObjectMapper();
    return pins;
  }
}
