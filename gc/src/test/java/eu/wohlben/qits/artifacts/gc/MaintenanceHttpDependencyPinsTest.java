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
 * The dependency pin adapter against a stub serving qits-platform-maintenance's real response shape.
 *
 * <p>{@code GET /maintenance/api/pins} answers {@code {"generatedAt":…,"repositories":[…],"pins":[…]}}
 * — one pin per manifest reference to an internally published version, in one of the ecosystems this
 * store hosts (and, since 2026-09-18, one per daemon binary such a reference carries).
 * This suite is about that shape and nothing else: which member holds the keep-set, which member is
 * provenance to be dropped, and which malformed bodies must refuse rather than read as "nothing is
 * referenced".
 *
 * <p>Plain JUnit, no Quarkus, for the reason {@link CdHttpDeploymentPinsTest} gives.
 */
class MaintenanceHttpDependencyPinsTest {

  private static final String BODY =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "repositories":[{"name":"qits-githost-service","status":"OK","lastScanAt":"…","headSha":"…"}],
       "pins":[
         {"ecosystem":"maven","name":"eu.wohlben.qits:qits-blobstore","version":"2026.903.85122",
          "repository":"qits-githost-service","manifestPath":"pom.xml"},
         {"ecosystem":"npm","name":"@qits/ui-components","version":"2026.902.204627",
          "repository":"qits-spa-home","manifestPath":"package.json"},
         {"ecosystem":"docker","name":"qits/workspace-base","version":"2026.902.143920",
          "repository":"qits-workspace-daemon","manifestPath":"docker/Dockerfile"}
       ]}
      """;

  @Test
  void everyEcosystemsCoordinateIsReadOffTheResponseShape() throws IOException {
    try (StubPinService maintenance = StubPinService.serving("/pins", BODY)) {
      List<MaintenanceDependencyPins.DependencyPin> pins = adapter(maintenance.baseUrl()).pins();

      assertEquals(3, pins.size());
      assertEquals("maven", pins.get(0).ecosystem());
      assertEquals("eu.wohlben.qits:qits-blobstore", pins.get(0).name());
      assertEquals("2026.903.85122", pins.get(0).version());
      assertEquals("qits-githost-service", pins.get(0).repository());
      assertEquals("pom.xml", pins.get(0).manifestPath());
      assertEquals("@qits/ui-components", pins.get(1).name());
      assertEquals("qits/workspace-base", pins.get(2).name());
    }
  }

  @Test
  void theRepositoriesArrayIsProvenanceAndIsReadPastRatherThanIntoTheKeepSet() throws IOException {
    // It says which repositories were scanned and how recently, which is a fact about the INVENTORY
    // rather than about what is referenced. Folding it into the keep-set would be this service
    // deciding when maintenance's scan is fresh enough to act on; maintenance makes that call itself
    // and expresses it as a 503. A body with no repositories at all still parses, because the pins
    // array is the answer.
    try (StubPinService maintenance =
        StubPinService.serving(
            "/pins",
            "{\"pins\":[{\"ecosystem\":\"npm\",\"name\":\"@qits/ui\",\"version\":\"1.0.0\"}]}")) {
      List<MaintenanceDependencyPins.DependencyPin> pins = adapter(maintenance.baseUrl()).pins();

      assertEquals(1, pins.size());
      assertEquals("@qits/ui", pins.get(0).name());
    }
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    // A platform whose repositories reference nothing internal — the state a brand new install is
    // in. It must parse: refusing here would mean GC could never run before the first internal
    // dependency existed.
    try (StubPinService maintenance = StubPinService.serving("/pins", "{\"pins\":[]}")) {
      assertEquals(List.of(), adapter(maintenance.baseUrl()).pins());
    }
  }

  @Test
  void aShapeThisCannotReadIsRefusedRatherThanReadAsNothingReferenced() throws IOException {
    // The whole reason this throws: a dependency list read as empty condemns every library version
    // nothing happens to have resolved inside three days.
    try (StubPinService maintenance = StubPinService.serving("/pins", "{\"repositories\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(maintenance.baseUrl()).pins())
              .getMessage()
              .contains("'pins' array"));
    }
    try (StubPinService maintenance =
        StubPinService.serving("/pins", "{\"repositories\":5,\"pins\":[]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(maintenance.baseUrl()).pins())
              .getMessage()
              .contains("'repositories'"));
    }
    try (StubPinService maintenance =
        StubPinService.serving(
            "/pins", "{\"pins\":[{\"ecosystem\":\"maven\",\"name\":\"g:a\"}]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(maintenance.baseUrl()).pins())
              .getMessage()
              .contains("no ecosystem, name or version"));
    }
  }

  @Test
  void aDaemonPinParsesBecauseTheDaemonsStoreIsHostedHereToo() throws IOException {
    // The fourth ecosystem, and the ONLY reason it is a parser change at all: this reader refuses a
    // whole source on a name it does not hold, and a refused source aborts every sweep on the
    // platform. So the name has to be accepted here before maintenance starts sending it — which is
    // why this half ships first, and why the case is about parsing rather than about keeping.
    //
    // `via` is the carrier the row was derived from: read past, exactly as `repositories` is.
    try (StubPinService maintenance =
        StubPinService.serving(
            "/pins",
            """
            {"pins":[
              {"ecosystem":"daemon","name":"qits-platform-access-cli","version":"2026.917.65806",
               "repository":"qits-platform-access-cli","manifestPath":"pom.xml",
               "via":"eu.wohlben.qits:qits-platform-access-cli-binary"}
            ]}
            """)) {
      List<MaintenanceDependencyPins.DependencyPin> pins = adapter(maintenance.baseUrl()).pins();

      assertEquals(1, pins.size());
      assertEquals("daemon", pins.get(0).ecosystem());
      assertEquals("qits-platform-access-cli", pins.get(0).name());
      assertEquals("2026.917.65806", pins.get(0).version());
      assertEquals("qits-platform-access-cli", pins.get(0).repository());
    }
  }

  @Test
  void anEcosystemThisStoreCannotFileIsRefusedRatherThanSkipped() throws IOException {
    // A pin filed into no keep-set is a keep dropped in silence, and a dropped keep is a deleted
    // artifact something still references. Refusing costs one run that reclaims nothing and names
    // the ecosystem, which is a fix of one line here rather than a 404 nobody can explain.
    try (StubPinService maintenance =
        StubPinService.serving(
            "/pins",
            "{\"pins\":[{\"ecosystem\":\"gitlink\",\"name\":\"qits-ci\",\"version\":\"abc\"}]}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(maintenance.baseUrl()).pins())
              .getMessage()
              .contains("gitlink"));
    }
  }

  @Test
  void aNon200AndAClosedPortBothRefuseWithTheUrlInTheMessage() throws IOException {
    // 503 is maintenance's own refusal when its inventory holds no repository rows at all — never
    // scanned, which must not read as "nothing is referenced". It arrives here as a failure like
    // any other, which is exactly the handling it needs.
    try (StubPinService maintenance =
        StubPinService.answering("/pins", 503, "{\"message\":\"inventory is empty\"}")) {
      assertTrue(
          assertThrows(IllegalStateException.class, () -> adapter(maintenance.baseUrl()).pins())
              .getMessage()
              .contains("503"));
    }
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> adapter("http://127.0.0.1:1/maintenance/api").pins())
            .getMessage()
            .contains("unreachable"));
  }

  /** The adapter as CDI would build it: the two config values and the mapper. */
  private static MaintenanceHttpDependencyPins adapter(String baseUrl) {
    MaintenanceHttpDependencyPins pins = new MaintenanceHttpDependencyPins();
    pins.baseUrl = baseUrl;
    pins.timeout = Duration.ofSeconds(5);
    pins.objectMapper = new ObjectMapper();
    return pins;
  }
}
