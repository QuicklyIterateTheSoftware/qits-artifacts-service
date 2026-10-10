package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;

/**
 * The six real pin readers, built outside CDI and pointed at a pact mock server, for the consumer
 * pacts in {@code eu.wohlben.qits.artifacts.contracts.consumer} (qits-1149). Lives in the readers'
 * own package because their config fields are package-private; it sets only what a CDI container
 * would inject, so the reader under test is the production class unchanged.
 *
 * <p>{@code base} is the mock server's root, and each reader gets the base url its deployment
 * config gives it — the provider's {@code /<app>/api} prefix — so the request path is the one
 * production sends.
 */
public final class ContractPinReaders {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private ContractPinReaders() {}

  public static CdHttpDeploymentPins deployments(String base, ObjectMapper mapper) {
    CdHttpDeploymentPins reader = new CdHttpDeploymentPins();
    reader.baseUrl = base + "/deployments/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }

  public static CiHttpDaemonPins ci(String base, ObjectMapper mapper) {
    CiHttpDaemonPins reader = new CiHttpDaemonPins();
    reader.baseUrl = base + "/ci/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }

  public static MaintenanceHttpDependencyPins maintenance(String base, ObjectMapper mapper) {
    MaintenanceHttpDependencyPins reader = new MaintenanceHttpDependencyPins();
    reader.baseUrl = base + "/maintenance/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }

  public static ConfigurationHttpImagePins configuration(String base, ObjectMapper mapper) {
    ConfigurationHttpImagePins reader = new ConfigurationHttpImagePins();
    reader.baseUrl = base + "/configuration/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }

  public static WorkspacesHttpLaunchPins workspaces(String base, ObjectMapper mapper) {
    WorkspacesHttpLaunchPins reader = new WorkspacesHttpLaunchPins();
    reader.baseUrl = base + "/workspaces/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }

  public static ProjectsHttpLaunchPins projects(String base, ObjectMapper mapper) {
    ProjectsHttpLaunchPins reader = new ProjectsHttpLaunchPins();
    reader.baseUrl = base + "/projects/api";
    reader.timeout = TIMEOUT;
    reader.objectMapper = mapper;
    return reader;
  }
}
