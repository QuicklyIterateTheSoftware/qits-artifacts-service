package eu.wohlben.qits.artifacts.gc;

import java.util.List;

/**
 * What the deployer needs kept: per application, every image sha that is serving or that a rollback
 * would pull again.
 *
 * <p><b>The name is history.</b> qits-cd and qits-serviceregistry merged back into
 * qits-platform-deployments, which owns both the topology and the execution. This port, its
 * implementation and the {@code cd-} config keys kept their names; the address moved. Renaming the
 * keys is the one change that would lose a deployment's override in silence.
 *
 * <p><b>The deployer owns this policy, and that is the point of the port's shape.</b> It used to
 * hand over raw deployment rows and this repository derived "ACTIVE plus the previous distinct sha"
 * from them — a keep-set computed in the service that deletes rather than in the service that
 * deploys, which is two definitions of one rule waiting to disagree. They did: the local derivation
 * stopped at the first older row of any status, so a history of {@code ACTIVE(A) / FAILED(C) /
 * DECOMMISSIONED(B)} pinned C, an attempt that never served, and dropped B, the sha a rollback
 * actually restores. {@code GET /deployments/api/pins} answers with the deployer's own rule
 * ({@code RollbackPins}, which lives beside the code that performs the rollback), and this port
 * carries transport and nothing else.
 *
 * <p><b>The shas are a set, not a sequence.</b> The answer is a union over every environment, and a
 * union has no "most recent" — an application running in two environments contributes both of its
 * shas. A reader keeps all of them and must not try to order them.
 *
 * <p><b>Absent is not a supported configuration</b>, and that is this repository's one documented
 * exception to the ports rule. An implementation that cannot answer throws, {@link GcPinSources}
 * records the failure, and the run deletes nothing: a dry-run reports itself non-executable and a
 * sweep aborts whole. Reclaiming nothing is the correct outcome of an unreachable deployer;
 * reclaiming something is never.
 */
@FunctionalInterface
public interface CdDeploymentPins {

  /**
   * One application's pinned shas.
   *
   * @param applicationName the application's name, which is also the image name: cd pulls every
   *     deployment as {@code <repository>/<application>:<sha>}, so this is the join to an {@code
   *     oci_tag}
   * @param shas every sha that must survive for it — what is serving, and what a rollback restores
   */
  record ApplicationPin(String applicationName, List<String> shas) {}

  /**
   * Every pin cd holds, across every environment.
   *
   * @throws RuntimeException cd could not be reached or could not be parsed — never an empty list,
   *     because an empty list reads as "nothing is deployed" and condemns every tag
   */
  List<ApplicationPin> pins();

  /**
   * Where this implementation reads them from, for the report's pins section.
   *
   * <p>A reviewer checking a keep-set against their own deployments needs the url that produced it:
   * a run pointed at the wrong deployer answers plausibly and pins the wrong shas, and that is not
   * visible in a list of shas. Default because it is provenance rather than policy — a stub in a
   * suite has nothing useful to say here and must not be made to invent something.
   */
  default String url() {
    return "(not reported by " + getClass().getSimpleName() + ")";
  }
}
