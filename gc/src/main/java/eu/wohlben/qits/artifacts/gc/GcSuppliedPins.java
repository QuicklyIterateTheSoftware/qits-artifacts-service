package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * The pin documents a caller handed in, instead of this service fetching them.
 *
 * <p>qits-platform-orchestrator reads the platform's pins <b>once</b> per run and gives the same
 * set to every deleter — this service, qits-containers, whatever comes next. One read for the whole
 * run is what keeps two deleters from working off two different truths, and the orchestrator is the
 * one component holding an idp client for every peer, so it is the one that can read them at all on
 * an authenticated platform.
 *
 * <p>Each member is the peer's response body <b>verbatim</b>: {@code deployments} is what {@code
 * GET /platform-deployments/api/pins} answered, {@code ciDaemon} is what {@code GET /ci/api/daemon}
 * answered, {@code dependencies} is what {@code GET /maintenance/api/pins} answered, {@code
 * configuredImages} is what {@code GET /configuration/api/pins} answered, and {@code
 * workspaceLaunches} / {@code projectLaunches} are what {@code GET /workspaces/api/pins} and {@code
 * GET /projects/api/pins} answered. Verbatim rather than a re-shaped keep-set, because they are then
 * read by the very same parsers the HTTP readers use ({@link CdHttpDeploymentPins#parse}, {@link
 * CiHttpDaemonPins#parse}, {@link MaintenanceHttpDependencyPins#parse}, {@link
 * ConfigurationHttpImagePins#parse}, {@link LaunchImagePins#parse}); a caller-shaped keep-set would
 * be a second definition of the same document waiting to disagree with the first.
 *
 * <p><b>A missing member is not "nothing is pinned".</b> It is that source unanswered, and it fails
 * the run closed exactly as an unreachable service does: the plan reports itself non-executable and
 * a sweep aborts with nothing deleted. An empty {@code {"pins":[]}} is the opposite — a real answer
 * from a platform with nothing deployed — and it pins nothing, which is what it says.
 *
 * <p><b>Every member added makes an older orchestrator's body a refusal</b>, which is why this half
 * always ships last: a caller sending four of six supplies two thirds of a keep-set, and a partial
 * keep-set condemns whatever the missing part protected. It happened on 2026-09-04 with {@code
 * dependencies}/{@code configuredImages} and again on 2026-09-05 with the two launch members, and
 * the rollout order is the same shape both times — the answering services, then the orchestrator,
 * then this.
 *
 * @param deployments the deployer's pins document, or null when the caller sent none
 * @param ciDaemon qits-ci's daemon document, or null when the caller sent none
 * @param dependencies qits-platform-maintenance's dependency pins document, or null when the caller
 *     sent none
 * @param configuredImages qits-configuration's configured image document, or null when the caller
 *     sent none
 * @param workspaceLaunches qits-workspaces' launch pins document, or null when the caller sent none
 * @param projectLaunches qits-projects' launch pins document, or null when the caller sent none
 */
public record GcSuppliedPins(
    JsonNode deployments,
    JsonNode ciDaemon,
    JsonNode dependencies,
    JsonNode configuredImages,
    JsonNode workspaceLaunches,
    JsonNode projectLaunches) {

  /** How the deployments source is named on a report when it was supplied rather than fetched. */
  public static final String CD_SOURCE = "supplied: qits-platform-deployments";

  /** How the qits-ci source is named on a report when it was supplied rather than fetched. */
  public static final String CI_SOURCE = "supplied: qits-ci";

  /** How the dependency source is named on a report when it was supplied rather than fetched. */
  public static final String MAINTENANCE_SOURCE = "supplied: qits-platform-maintenance";

  /** How the configured-image source is named on a report when it was supplied. */
  public static final String CONFIGURATION_SOURCE = "supplied: qits-configuration";

  /** How the workspace launch source is named on a report when it was supplied. */
  public static final String WORKSPACES_SOURCE = "supplied: qits-workspaces";

  /** How the project launch source is named on a report when it was supplied. */
  public static final String PROJECTS_SOURCE = "supplied: qits-projects";

  /**
   * The optional request body of {@code POST /gc/plan} and the two sweeps, read into this.
   *
   * <pre>
   * {"pins": {"deployments":       &lt;verbatim body of GET /platform-deployments/api/pins&gt;,
   *           "ciDaemon":          &lt;verbatim body of GET /ci/api/daemon&gt;,
   *           "dependencies":      &lt;verbatim body of GET /maintenance/api/pins&gt;,
   *           "configuredImages":  &lt;verbatim body of GET /configuration/api/pins&gt;,
   *           "workspaceLaunches": &lt;verbatim body of GET /workspaces/api/pins&gt;,
   *           "projectLaunches":   &lt;verbatim body of GET /projects/api/pins&gt;}}
   * </pre>
   *
   * <p>An envelope with one member rather than the four documents at the top level, so a later run
   * option can be added beside {@code pins} without moving anything a caller already sends.
   *
   * <p>Read off the tree rather than bound to a class, which is why this repository still adds no
   * native-image configuration: {@code JsonNode} needs no reflection, and the documents are carried
   * verbatim anyway.
   *
   * @param body the parsed body, or null when the request had none
   * @return the supplied documents, or empty for "no body, no {@code pins} member" — the plain call
   *     that reads its pins over HTTP, which is every existing caller
   * @throws IllegalArgumentException the envelope is there but is not the shape above
   */
  public static Optional<GcSuppliedPins> inRequestBody(JsonNode body) {
    if (body == null || body.isNull()) {
      return Optional.empty();
    }
    if (!body.isObject()) {
      throw new IllegalArgumentException("the gc request body must be a JSON object");
    }
    JsonNode pins = body.get("pins");
    if (pins == null || pins.isNull()) {
      return Optional.empty();
    }
    if (!pins.isObject()) {
      throw new IllegalArgumentException(
          "'pins' must be an object holding 'deployments', 'ciDaemon', 'dependencies',"
              + " 'configuredImages', 'workspaceLaunches' and 'projectLaunches'");
    }
    return Optional.of(
        new GcSuppliedPins(
            pins.get("deployments"),
            pins.get("ciDaemon"),
            pins.get("dependencies"),
            pins.get("configuredImages"),
            pins.get("workspaceLaunches"),
            pins.get("projectLaunches")));
  }

  /**
   * The deployer's pins, read from the supplied document.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public List<CdDeploymentPins.ApplicationPin> deploymentPins() {
    return CdHttpDeploymentPins.parse(require(deployments, "deployments"), "the supplied document");
  }

  /**
   * qits-ci's ladder, read from the supplied document.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public CiDaemonPins.DaemonPin daemonPin() {
    return CiHttpDaemonPins.parse(require(ciDaemon, "ciDaemon"), "the supplied document");
  }

  /**
   * The manifest dependency pins, read from the supplied document.
   *
   * <p>No member of its own was needed when the {@code daemon} ecosystem was added: this delegates
   * to the same {@link MaintenanceHttpDependencyPins#parse} the HTTP reader runs, so an ecosystem
   * taught there is taught here in the same commit and cannot be taught to only one of the two.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public List<MaintenanceDependencyPins.DependencyPin> dependencyPins() {
    return MaintenanceHttpDependencyPins.parse(
        require(dependencies, "dependencies"), "the supplied document");
  }

  /**
   * The configured container images, read from the supplied document.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public List<ConfigurationImagePins.ImagePin> configuredImagePins() {
    return ConfigurationHttpImagePins.parse(
        require(configuredImages, "configuredImages"), "the supplied document");
  }

  /**
   * The images qits-workspaces would launch today, read from the supplied document.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public List<LaunchImagePins.LaunchPin> workspaceLaunchPins() {
    return LaunchImagePins.parse(
        require(workspaceLaunches, "workspaceLaunches"),
        WorkspacesHttpLaunchPins.SERVICE,
        "the supplied document");
  }

  /**
   * The images qits-projects would launch today, read from the supplied document.
   *
   * @throws IllegalStateException no document was supplied, or its shape cannot be read
   */
  public List<LaunchImagePins.LaunchPin> projectLaunchPins() {
    return LaunchImagePins.parse(
        require(projectLaunches, "projectLaunches"),
        ProjectsHttpLaunchPins.SERVICE,
        "the supplied document");
  }

  private static JsonNode require(JsonNode document, String member) {
    if (document == null || document.isNull()) {
      throw new IllegalStateException(
          "no '" + member + "' document was supplied, so this source is unanswered");
    }
    return document;
  }
}
