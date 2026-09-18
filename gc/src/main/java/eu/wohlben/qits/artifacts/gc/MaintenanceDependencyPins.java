package eu.wohlben.qits.artifacts.gc;

import java.util.List;

/**
 * What the platform's repositories currently <b>reference</b>: every internal maven, npm and docker
 * version named by a manifest on some repository's {@code main}, and every daemon binary one of
 * those versions carries.
 *
 * <p><b>Consumption, where the other three pin sources answer for execution.</b>
 * qits-platform-deployments names what is running, qits-ci names what a runner would launch,
 * qits-configuration names what the platform is configured to start — and none of them can answer
 * "which library version does a repository still build against". That is a fact about source, and
 * qits-platform-maintenance already holds it: it scans every repository's manifests to raise the
 * bump requests, so the dependency graph is a by-product of work it does anyway. Deriving it here
 * would mean this service cloning repositories, which is the drift the pin ports exist to refuse.
 *
 * <p><b>Only INTERNAL pins, and only the ecosystems this store hosts.</b> The answer carries no
 * gitlink, nothing REACTOR/EXTERNAL/UNRESOLVED, and nothing from a registry this store does not host
 * — an upstream coordinate is not ours to keep or to delete. Each row is one manifest reference, so
 * the same version can arrive many times over; the keep-set is a set and says so.
 *
 * <p><b>The {@code daemon} rows are derived, and they close the one gap the other three leave.</b>
 * No manifest anywhere spells a daemon binary — a daemon's version rides inside the maven or npm
 * coordinate that ships it, which is why an internally pinned daemon was, until 2026-09-18, named by
 * no pin source on the platform at all. qits-platform-maintenance resolves a pinned internal
 * coordinate to the repository that released it and to every daemon that repository released at the
 * same version, and answers those as {@code daemon} rows with {@code via} naming the carrier. It is
 * the {@code CarriedImages} mechanism one artifact type over, and it is a fact about a <b>release</b>
 * rather than about a file, which is precisely why this service cannot derive it and maintenance
 * can.
 *
 * <p><b>Absent is not a supported configuration</b>, exactly as {@link CdDeploymentPins} spells out.
 * An implementation that cannot answer throws, {@link GcPinSources} records the failure, and the run
 * deletes nothing. An empty inventory is a 503 rather than an empty list on the answering side, for
 * the same reason this port refuses rather than returning one: "nothing was ever scanned" and
 * "nothing is referenced" are opposite facts that would otherwise look identical here.
 */
@FunctionalInterface
public interface MaintenanceDependencyPins {

  /**
   * One manifest reference to an internally published version, or one daemon binary such a
   * reference carries.
   *
   * @param ecosystem {@code maven}, {@code npm}, {@code docker} or {@code daemon} — which store the
   *     coordinate is spelled for, and therefore which keep-set it joins. The first three are read
   *     off a manifest; {@code daemon} is derived from one of the first two, and reads here exactly
   *     as they do
   * @param name the ecosystem's own name: {@code groupId:artifactId} for maven, the package name for
   *     npm, the full image name for docker, the daemon's own name for daemon
   * @param version the referenced version, verbatim. For a {@code daemon} row it is the version the
   *     carrier was released at, which is the version the daemons store filed the binary under
   * @param repository which repository's manifest holds the reference — provenance for the receipt,
   *     never part of the keep key. A {@code daemon} row also carries a {@code via} naming the
   *     carrier coordinate, which is provenance of the same kind and is read past rather than
   *     modelled here: what this port needs is the coordinate, and a keep-set widened by a field
   *     nobody joins on is a field that will drift
   * @param manifestPath the file the reference was read from, provenance for the same reason
   */
  record DependencyPin(
      String ecosystem, String name, String version, String repository, String manifestPath) {}

  /**
   * Every internal dependency the platform's repositories currently reference.
   *
   * @throws RuntimeException qits-platform-maintenance could not be reached or could not be parsed —
   *     never an empty list, because an empty list reads as "nothing is referenced" and condemns
   *     every library version nothing has resolved lately
   */
  List<DependencyPin> pins();

  /** Where this implementation reads them from, for the report's pins section. */
  default String url() {
    return "(not reported by " + getClass().getSimpleName() + ")";
  }
}
