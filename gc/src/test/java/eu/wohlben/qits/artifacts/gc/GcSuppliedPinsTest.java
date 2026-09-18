package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins handed in with the request, against pins fetched over the wire.
 *
 * <p>qits-platform-orchestrator reads the platform's pins once per run and gives the same set to
 * every deleter. The property that makes that safe is the one this suite is about: the supplied
 * documents are read by the <b>same parsers</b> as the fetched ones, so a keep-set does not change
 * because of how it travelled. Only the provenance differs — the source's name, and an empty url.
 *
 * <p>The other half is that supplying pins can never widen a run. A member the caller left out is
 * that source <b>unanswered</b>, which fails the run closed exactly as an unreachable service does,
 * and a document this cannot read is refused rather than read as "nothing is pinned".
 *
 * <p><b>Six members since 2026-09-05</b>, and every one added behaves identically to the two that
 * were there first: same verbatim documents, same shared parsers, same fail-closed rule for a member
 * left out. That last property is what makes the rollout order load-bearing — an orchestrator still
 * sending four members supplies two thirds of a keep-set — so this service ships last.
 */
class GcSuppliedPinsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String DEPLOYMENTS =
      """
      {"pins":[
        {"applicationName":"qits-artifacts","shas":["aaaa","bbbb"]},
        {"applicationName":"qits-ci","shas":["cccc"]}
      ]}
      """;

  private static final String CI_DAEMON =
      """
      {"daemonName":"qits-ci-daemon","daemonVersion":"2026.805.1",
       "previousDaemonVersion":"2026.804.9","source":"adopted"}
      """;

  private static final String DEPENDENCIES =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "repositories":[{"name":"qits-githost-service","status":"OK"}],
       "pins":[
         {"ecosystem":"maven","name":"eu.wohlben.qits:qits-blobstore","version":"2026.903.85122",
          "repository":"qits-githost-service","manifestPath":"pom.xml"},
         {"ecosystem":"npm","name":"@qits/ui-components","version":"2026.902.204627",
          "repository":"qits-spa-home","manifestPath":"package.json"},
         {"ecosystem":"docker","name":"qits/workspace-base","version":"2026.902.143920",
          "repository":"qits-workspace-daemon","manifestPath":"docker/Dockerfile"},
         {"ecosystem":"daemon","name":"qits-platform-access-cli","version":"2026.917.65806",
          "repository":"qits-platform-access-cli","manifestPath":"pom.xml",
          "via":"eu.wohlben.qits:qits-platform-access-cli-binary"}
       ]}
      """;

  private static final String CONFIGURED_IMAGES =
      """
      {"generatedAt":"2026-09-04T20:00:00Z",
       "pins":[
         {"image":"qits/workspace","version":"2026.904.160522","application":"qits-workspaces",
          "key":"env.QITS_WORKSPACE_IMAGE_VERSION"}
       ]}
      """;

  private static final String WORKSPACE_LAUNCHES =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[
         {"image":"qits/workspace","version":"2026.903.120000","launches":"workspace"},
         {"image":"qits/workspace-editor","version":"2026.904.100239","launches":"editor"}
       ]}
      """;

  private static final String PROJECT_LAUNCHES =
      """
      {"generatedAt":"2026-09-05T06:00:00Z",
       "pins":[
         {"image":"qits/project-agent","version":"2026.903.090000","launches":"agent"}
       ]}
      """;

  @Test
  void suppliedDocumentsProduceTheSameKeepsAsFetchedOnes() throws IOException {
    // The equivalence the orchestrator's whole design rests on: one document, two ways in, one
    // keep-set. Anything read differently here would be a run planning against pins the reviewer
    // never saw.
    try (StubPinService cd = StubPinService.serving("/pins", DEPLOYMENTS);
        StubPinService ci = StubPinService.serving("/daemon", CI_DAEMON);
        StubPinService maintenance = StubPinService.serving("/pins", DEPENDENCIES);
        StubPinService configuration = StubPinService.serving("/pins", CONFIGURED_IMAGES);
        StubPinService workspaces = StubPinService.serving("/pins", WORKSPACE_LAUNCHES);
        StubPinService projects = StubPinService.serving("/pins", PROJECT_LAUNCHES)) {
      GcPins fetched =
          httpSources(
                  cd.baseUrl(),
                  ci.baseUrl(),
                  maintenance.baseUrl(),
                  configuration.baseUrl(),
                  workspaces.baseUrl(),
                  projects.baseUrl())
              .fetch();
      GcPins supplied = new GcPinSources().fetch(documents());

      assertTrue(supplied.complete());
      assertEquals(fetched.deployments(), supplied.deployments());
      assertEquals(fetched.daemonName(), supplied.daemonName());
      assertEquals(fetched.daemonVersions(), supplied.daemonVersions());
      assertEquals(fetched.blobs(), supplied.blobs());
      assertEquals(fetched.mavenDependencies(), supplied.mavenDependencies());
      assertEquals(fetched.npmDependencies(), supplied.npmDependencies());
      assertEquals(fetched.manifestImages(), supplied.manifestImages());
      assertEquals(fetched.daemonDependencies(), supplied.daemonDependencies());
      assertEquals(fetched.configuredImages(), supplied.configuredImages());
      assertEquals(fetched.workspaceLaunchImages(), supplied.workspaceLaunchImages());
      assertEquals(fetched.projectLaunchImages(), supplied.projectLaunchImages());
      assertEquals(GcPins.BY_CD, supplied.pinsImageTag("qits-artifacts", "bbbb"));
      assertEquals(GcPins.BY_CI, supplied.pinsDaemonVersion("qits-ci-daemon", "2026.804.9"));
      assertEquals(
          GcPins.BY_MANIFEST,
          supplied.pinsMavenCoordinate("eu.wohlben.qits:qits-blobstore:2026.903.85122"));
      // The ecosystem added on 2026-09-18 needed no member of its own: it rides the `dependencies`
      // document an orchestrator already sends, through the parser that document already used. So
      // this is the one addition to the envelope's meaning that is NOT a rollout step — an
      // orchestrator sending six members keeps working, and the keeps simply widen.
      assertEquals(
          GcPins.BY_CARRIED_DAEMON,
          supplied.pinsCarriedDaemon("qits-platform-access-cli@2026.917.65806"));
      assertEquals(
          GcPins.BY_CONFIGURATION,
          supplied.pinsConfiguredImage("qits/workspace", "2026.904.160522"));
      // The effective version, which is NOT the configured one — the gap these two members exist
      // to cover, and it survives the trip through the request body unchanged.
      assertEquals(
          GcPins.BY_WORKSPACE_LAUNCH,
          supplied.pinsWorkspaceLaunchImage("qits/workspace", "2026.903.120000"));
      assertEquals(
          GcPins.BY_PROJECT_LAUNCH,
          supplied.pinsProjectLaunchImage("qits/project-agent", "2026.903.090000"));
      assertEquals(
          keepsOf(fetched, "qits-platform-deployments"),
          keepsOf(supplied, GcSuppliedPins.CD_SOURCE),
          "the same identities, whichever way the document arrived");
      assertEquals(keepsOf(fetched, "qits-ci"), keepsOf(supplied, GcSuppliedPins.CI_SOURCE));
      assertEquals(
          keepsOf(fetched, "qits-platform-maintenance"),
          keepsOf(supplied, GcSuppliedPins.MAINTENANCE_SOURCE));
      assertEquals(
          keepsOf(fetched, "qits-configuration"),
          keepsOf(supplied, GcSuppliedPins.CONFIGURATION_SOURCE));
      assertEquals(
          keepsOf(fetched, "qits-workspaces"),
          keepsOf(supplied, GcSuppliedPins.WORKSPACES_SOURCE));
      assertEquals(
          keepsOf(fetched, "qits-projects"), keepsOf(supplied, GcSuppliedPins.PROJECTS_SOURCE));
    }
  }

  @Test
  void aSuppliedSourceIsNamedAsSuppliedAndReportsNoUrl() throws IOException {
    // Provenance is the half of a keep-set a reviewer cannot check from the plan alone, so a run
    // that was handed its pins must not read as one that went and asked. There is no url to repeat
    // by hand, and inventing this service's own would name a call it never made.
    GcPins pins = new GcPinSources().fetch(documents());

    GcPinSource cd = source(pins, GcSuppliedPins.CD_SOURCE);
    assertTrue(cd.answered());
    assertEquals("", cd.url());
    assertEquals(2, cd.pinCount());
    assertEquals("", source(pins, GcSuppliedPins.CI_SOURCE).url());
    assertEquals("", source(pins, GcSuppliedPins.MAINTENANCE_SOURCE).url());
    assertEquals("", source(pins, GcSuppliedPins.CONFIGURATION_SOURCE).url());
    assertEquals("", source(pins, GcSuppliedPins.WORKSPACES_SOURCE).url());
    assertEquals("", source(pins, GcSuppliedPins.PROJECTS_SOURCE).url());
  }

  @Test
  void aMissingMemberIsAnUnansweredSourceRatherThanNothingPinned() throws IOException {
    // The fail-closed rule, unchanged: a caller that supplied one document supplied half a keep-set,
    // and half a keep-set condemns whatever the missing half protected. The source that DID arrive
    // still answers, so a report says exactly what was missing.
    GcPins noCi =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS,
                    null,
                    DEPENDENCIES,
                    CONFIGURED_IMAGES,
                    WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));

    assertFalse(noCi.complete());
    assertEquals(1, noCi.failures().size());
    assertTrue(noCi.whyIncomplete().contains(GcSuppliedPins.CI_SOURCE), noCi.whyIncomplete());
    assertTrue(noCi.whyIncomplete().contains("ciDaemon"), noCi.whyIncomplete());
    assertTrue(source(noCi, GcSuppliedPins.CD_SOURCE).answered());
    assertFalse(source(noCi, GcSuppliedPins.CI_SOURCE).answered());

    // The two members added on 2026-09-04, each unanswered on its own: an orchestrator that has not
    // been upgraded sends neither, and the run must refuse rather than plan against half a keep-set.
    GcPins noDependencies =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS, CI_DAEMON, null, CONFIGURED_IMAGES, WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));
    assertFalse(noDependencies.complete());
    assertTrue(
        noDependencies.whyIncomplete().contains("dependencies"), noDependencies.whyIncomplete());
    assertTrue(
        noDependencies.whyIncomplete().contains(GcSuppliedPins.MAINTENANCE_SOURCE),
        noDependencies.whyIncomplete());
    assertEquals(
        Set.of(),
        noDependencies.mavenDependencies(),
        "and nothing half-folded is left behind for a report to claim");

    GcPins noImages =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS, CI_DAEMON, DEPENDENCIES, null, WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));
    assertFalse(noImages.complete());
    assertTrue(noImages.whyIncomplete().contains("configuredImages"), noImages.whyIncomplete());

    // The two members of 2026-09-05, each unanswered on its own. This is the case an orchestrator
    // that has not been upgraded lands in, and it is the reason this service ships last: it sends
    // four members, so a run against it refuses rather than planning against a keep-set with the
    // effective versions missing.
    GcPins noWorkspaceLaunches =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS, CI_DAEMON, DEPENDENCIES, CONFIGURED_IMAGES, null,
                    PROJECT_LAUNCHES));
    assertFalse(noWorkspaceLaunches.complete());
    assertTrue(
        noWorkspaceLaunches.whyIncomplete().contains("workspaceLaunches"),
        noWorkspaceLaunches.whyIncomplete());
    assertTrue(
        noWorkspaceLaunches.whyIncomplete().contains(GcSuppliedPins.WORKSPACES_SOURCE),
        noWorkspaceLaunches.whyIncomplete());
    assertEquals(
        Set.of(),
        noWorkspaceLaunches.workspaceLaunchImages(),
        "and nothing half-folded is left behind for a report to claim");
    assertFalse(
        noWorkspaceLaunches.projectLaunchImages().isEmpty(),
        "while the twin that DID answer keeps its keeps — one outage, named once");

    GcPins noProjectLaunches =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS, CI_DAEMON, DEPENDENCIES, CONFIGURED_IMAGES, WORKSPACE_LAUNCHES,
                    null));
    assertFalse(noProjectLaunches.complete());
    assertTrue(
        noProjectLaunches.whyIncomplete().contains("projectLaunches"),
        noProjectLaunches.whyIncomplete());

    GcPins none = new GcPinSources().fetch(new GcSuppliedPins(null, null, null, null, null, null));
    assertFalse(none.complete());
    assertEquals(6, none.failures().size(), "every source is asked, so no outage hides");
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    // A platform with nothing deployed. It is the opposite of a missing member: the source answered,
    // the answer is "no application is serving", and the run may proceed — which means every tag
    // old enough is a candidate. The orchestrator has to know these two states read differently.
    GcPins pins =
        new GcPinSources()
            .fetch(
                documents(
                    "{\"pins\":[]}",
                    CI_DAEMON,
                    "{\"pins\":[]}",
                    "{\"pins\":[]}",
                    "{\"pins\":[]}",
                    "{\"pins\":[]}"));

    assertTrue(pins.complete());
    assertEquals(0, source(pins, GcSuppliedPins.CD_SOURCE).pinCount());
    assertTrue(source(pins, GcSuppliedPins.CD_SOURCE).answered());
    assertEquals(Set.of(), pins.deploymentShas("qits-artifacts"));
    assertTrue(source(pins, GcSuppliedPins.MAINTENANCE_SOURCE).answered());
    assertEquals(Set.of(), pins.mavenDependencies());
    assertTrue(source(pins, GcSuppliedPins.CONFIGURATION_SOURCE).answered());
    assertEquals(Set.of(), pins.configuredImages());
    // A launching service with no image version configured launches nothing pinnable, and that is
    // an answer rather than an outage — the state a fresh install is in.
    assertTrue(source(pins, GcSuppliedPins.WORKSPACES_SOURCE).answered());
    assertEquals(Set.of(), pins.workspaceLaunchImages());
    assertTrue(source(pins, GcSuppliedPins.PROJECTS_SOURCE).answered());
    assertEquals(Set.of(), pins.projectLaunchImages());
  }

  @Test
  void aSuppliedDocumentThisCannotReadIsRefusedRatherThanReadAsNoPins() throws IOException {
    // The same refusal the HTTP reader makes, from the same code. A body with no 'pins' array read
    // as an empty keep-set would condemn every sha tag on the platform.
    GcPins pins =
        new GcPinSources()
            .fetch(
                documents(
                    "{\"deployments\":[]}",
                    CI_DAEMON,
                    DEPENDENCIES,
                    CONFIGURED_IMAGES,
                    WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));

    assertFalse(pins.complete());
    assertTrue(pins.whyIncomplete().contains("'pins' array"), pins.whyIncomplete());

    // And the same refusal from the two new parsers, from the same code the HTTP readers run: a
    // dependency document with no 'pins' array read as empty would condemn every library version,
    // and an ecosystem this store cannot file is a keep silently dropped.
    GcPins badDependencies =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS,
                    CI_DAEMON,
                    "{\"repositories\":[]}",
                    CONFIGURED_IMAGES,
                    WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));
    assertFalse(badDependencies.complete());
    assertTrue(badDependencies.whyIncomplete().contains("'pins' array"), badDependencies.whyIncomplete());

    GcPins unknownEcosystem =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS,
                    CI_DAEMON,
                    "{\"pins\":[{\"ecosystem\":\"gitlink\",\"name\":\"qits-ci\",\"version\":\"abc\"}]}",
                    CONFIGURED_IMAGES,
                    WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));
    assertFalse(unknownEcosystem.complete());
    assertTrue(unknownEcosystem.whyIncomplete().contains("gitlink"), unknownEcosystem.whyIncomplete());

    GcPins badImages =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS,
                    CI_DAEMON,
                    DEPENDENCIES,
                    "{\"pins\":[{\"image\":\"qits/workspace\"}]}",
                    WORKSPACE_LAUNCHES,
                    PROJECT_LAUNCHES));
    assertFalse(badImages.complete());
    assertTrue(badImages.whyIncomplete().contains("no image or no version"), badImages.whyIncomplete());

    // And from the shared launch parse, with the SERVICE that answered in the message — which is
    // the condition one parse for two sources is allowed under.
    GcPins badLaunches =
        new GcPinSources()
            .fetch(
                documents(
                    DEPLOYMENTS,
                    CI_DAEMON,
                    DEPENDENCIES,
                    CONFIGURED_IMAGES,
                    WORKSPACE_LAUNCHES,
                    "{\"pins\":[{\"image\":\"qits/project-agent\",\"launches\":\"agent\"}]}"));
    assertFalse(badLaunches.complete());
    assertTrue(
        badLaunches.whyIncomplete().contains("qits-projects"), badLaunches.whyIncomplete());
    assertTrue(
        badLaunches.whyIncomplete().contains("no image or no version"),
        badLaunches.whyIncomplete());
  }

  @Test
  void noBodyAndNoPinsMemberBothMeanReadThemOverHttp() throws IOException {
    // Every caller that exists today sends nothing, and nothing must keep meaning what it meant.
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(null));
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(json("{}")));
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(json("{\"pins\":null}")));

    GcSuppliedPins all =
        GcSuppliedPins.inRequestBody(
                json(
                    "{\"pins\":{\"deployments\":" + DEPLOYMENTS
                        + ",\"ciDaemon\":" + CI_DAEMON
                        + ",\"dependencies\":" + DEPENDENCIES
                        + ",\"configuredImages\":" + CONFIGURED_IMAGES
                        + ",\"workspaceLaunches\":" + WORKSPACE_LAUNCHES
                        + ",\"projectLaunches\":" + PROJECT_LAUNCHES
                        + "}}"))
            .orElseThrow();
    assertEquals(2, all.deploymentPins().size());
    assertEquals("qits-ci-daemon", all.daemonPin().daemonName());
    assertEquals(4, all.dependencyPins().size());
    assertEquals(1, all.configuredImagePins().size());
    assertEquals(2, all.workspaceLaunchPins().size());
    assertEquals(1, all.projectLaunchPins().size());

    // Present but empty is a supplied set with nothing in it — six unanswered sources, not a
    // no-body call, because the caller said it was supplying pins and then supplied none.
    assertTrue(GcSuppliedPins.inRequestBody(json("{\"pins\":{}}")).isPresent());
    assertThrows(
        IllegalArgumentException.class, () -> GcSuppliedPins.inRequestBody(json("{\"pins\":5}")));
  }

  /** The six documents this suite's happy path supplies. */
  private static GcSuppliedPins documents() throws IOException {
    return documents(
        DEPLOYMENTS,
        CI_DAEMON,
        DEPENDENCIES,
        CONFIGURED_IMAGES,
        WORKSPACE_LAUNCHES,
        PROJECT_LAUNCHES);
  }

  private static GcSuppliedPins documents(
      String deployments,
      String ciDaemon,
      String dependencies,
      String configuredImages,
      String workspaceLaunches,
      String projectLaunches)
      throws IOException {
    return new GcSuppliedPins(
        deployments == null ? null : json(deployments),
        ciDaemon == null ? null : json(ciDaemon),
        dependencies == null ? null : json(dependencies),
        configuredImages == null ? null : json(configuredImages),
        workspaceLaunches == null ? null : json(workspaceLaunches),
        projectLaunches == null ? null : json(projectLaunches));
  }

  private static JsonNode json(String document) throws IOException {
    return MAPPER.readTree(document);
  }

  private static GcPinSource source(GcPins pins, String named) {
    return pins.sources().stream()
        .filter(source -> source.source().equals(named))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no pin source named " + named));
  }

  private static java.util.List<String> keepsOf(GcPins pins, String named) {
    return source(pins, named).keeps();
  }

  /** The six HTTP adapters as CDI would wire them, pointed at the stubs. */
  private static GcPinSources httpSources(
      String cdBaseUrl,
      String ciBaseUrl,
      String maintenanceBaseUrl,
      String configurationBaseUrl,
      String workspacesBaseUrl,
      String projectsBaseUrl) {
    GcPinSources sources = new GcPinSources();
    CdHttpDeploymentPins cd = new CdHttpDeploymentPins();
    cd.baseUrl = cdBaseUrl;
    cd.timeout = Duration.ofSeconds(5);
    cd.objectMapper = MAPPER;
    sources.cd = cd;
    CiHttpDaemonPins ci = new CiHttpDaemonPins();
    ci.baseUrl = ciBaseUrl;
    ci.timeout = Duration.ofSeconds(5);
    ci.objectMapper = MAPPER;
    sources.ci = ci;
    MaintenanceHttpDependencyPins maintenance = new MaintenanceHttpDependencyPins();
    maintenance.baseUrl = maintenanceBaseUrl;
    maintenance.timeout = Duration.ofSeconds(5);
    maintenance.objectMapper = MAPPER;
    sources.maintenance = maintenance;
    ConfigurationHttpImagePins configuration = new ConfigurationHttpImagePins();
    configuration.baseUrl = configurationBaseUrl;
    configuration.timeout = Duration.ofSeconds(5);
    configuration.objectMapper = MAPPER;
    sources.configuration = configuration;
    WorkspacesHttpLaunchPins workspaces = new WorkspacesHttpLaunchPins();
    workspaces.baseUrl = workspacesBaseUrl;
    workspaces.timeout = Duration.ofSeconds(5);
    workspaces.objectMapper = MAPPER;
    sources.workspaces = workspaces;
    ProjectsHttpLaunchPins projects = new ProjectsHttpLaunchPins();
    projects.baseUrl = projectsBaseUrl;
    projects.timeout = Duration.ofSeconds(5);
    projects.objectMapper = MAPPER;
    sources.projects = projects;
    return sources;
  }
}
