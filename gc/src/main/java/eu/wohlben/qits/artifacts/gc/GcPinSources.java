package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Reads all six pin sources once and folds them into one {@link GcPins}.
 *
 * <p>Called at the start of a plan and at the start of a sweep, and nowhere else — one aggregate per
 * run is what keeps two halves of a run from planning against two different truths.
 *
 * <p><b>Every source is asked even when an earlier one fails.</b> A run that stopped at the first
 * failure would report one broken service and hide the other five, and whoever fixed it would find
 * the next run just as dead with no warning it was coming. Every failure lands in {@link
 * GcPins#failures()}, and the run's refusal is what the executor and the planner do with it.
 *
 * <p><b>Each read also records how it went</b> ({@link GcPinSource}): the url, the outcome, the time
 * it took and the keep-identities the answer resolved to. That is the half of a keep-set a reviewer
 * cannot check from the plan alone — a run pointed at the wrong deployer answers plausibly and pins
 * the wrong shas — and it is recorded here, at the only place that knows what was actually called.
 *
 * <p><b>The pins may also arrive in the request</b> ({@link #fetch(GcSuppliedPins)}), which is how
 * qits-platform-orchestrator hands one platform-wide pin set to every deleter in a run. The fold
 * below is the same either way — same parsers, same keep-identities, same fail-closed rule — and
 * only the source name and the url differ, so a report says which of the two happened.
 */
@ApplicationScoped
public class GcPinSources {

  @Inject CdDeploymentPins cd;
  @Inject CiDaemonPins ci;
  @Inject MaintenanceDependencyPins maintenance;
  @Inject ConfigurationImagePins configuration;
  @Inject WorkspacesLaunchPins workspaces;
  @Inject ProjectsLaunchPins projects;

  /**
   * One source as the fold sees it: how it is named on the report, where it was read from (empty
   * when the document was supplied), and how to produce its answer or throw.
   */
  private record Source<T>(String name, String url, Supplier<T> read) {}

  /** One reading of every pin the platform holds, over the wire. Never cached. */
  public GcPins fetch() {
    return fold(
        new Source<>("qits-platform-deployments", cd.url(), cd::pins),
        new Source<>("qits-ci", ci.url(), ci::daemonPin),
        new Source<>("qits-platform-maintenance", maintenance.url(), maintenance::pins),
        new Source<>("qits-configuration", configuration.url(), configuration::pins),
        new Source<>(WorkspacesHttpLaunchPins.SERVICE, workspaces.url(), workspaces::pins),
        new Source<>(ProjectsHttpLaunchPins.SERVICE, projects.url(), projects::pins));
  }

  /**
   * The same reading, from documents the caller supplied instead of the network.
   *
   * <p>Null — no {@code pins} member in the request — means the caller asked for nothing special,
   * so the HTTP readers run and the behaviour is exactly {@link #fetch()}. A supplied set with a
   * member missing is that source <b>unanswered</b>, not "nothing is pinned": the fold records the
   * failure and the run refuses, which is the same rule an unreachable service meets.
   */
  public GcPins fetch(GcSuppliedPins supplied) {
    if (supplied == null) {
      return fetch();
    }
    return fold(
        new Source<>(GcSuppliedPins.CD_SOURCE, "", supplied::deploymentPins),
        new Source<>(GcSuppliedPins.CI_SOURCE, "", supplied::daemonPin),
        new Source<>(GcSuppliedPins.MAINTENANCE_SOURCE, "", supplied::dependencyPins),
        new Source<>(GcSuppliedPins.CONFIGURATION_SOURCE, "", supplied::configuredImagePins),
        new Source<>(GcSuppliedPins.WORKSPACES_SOURCE, "", supplied::workspaceLaunchPins),
        new Source<>(GcSuppliedPins.PROJECTS_SOURCE, "", supplied::projectLaunchPins));
  }

  /** All six answers folded into one aggregate, whoever produced them. */
  private GcPins fold(
      Source<List<CdDeploymentPins.ApplicationPin>> cdSource,
      Source<CiDaemonPins.DaemonPin> ciSource,
      Source<List<MaintenanceDependencyPins.DependencyPin>> maintenanceSource,
      Source<List<ConfigurationImagePins.ImagePin>> configurationSource,
      Source<List<LaunchImagePins.LaunchPin>> workspacesSource,
      Source<List<LaunchImagePins.LaunchPin>> projectsSource) {
    Map<String, Set<String>> deployments = new HashMap<>();
    Set<String> daemonVersions = new HashSet<>();
    Set<String> blobs = new HashSet<>();
    Set<String> mavenDependencies = new TreeSet<>();
    Set<String> npmDependencies = new TreeSet<>();
    Set<String> manifestImages = new TreeSet<>();
    Set<String> daemonDependencies = new TreeSet<>();
    Set<String> configuredImages = new TreeSet<>();
    Set<String> workspaceLaunchImages = new TreeSet<>();
    Set<String> projectLaunchImages = new TreeSet<>();
    List<String> failures = new ArrayList<>();
    List<GcPinSource> sources = new ArrayList<>();

    Instant startedCd = Instant.now();
    try {
      List<CdDeploymentPins.ApplicationPin> pins = cdSource.read().get();
      Set<String> keeps = new TreeSet<>();
      for (CdDeploymentPins.ApplicationPin pin : pins) {
        deployments
            .computeIfAbsent(pin.applicationName(), image -> new HashSet<>())
            .addAll(pin.shas());
        for (String sha : pin.shas()) {
          keeps.add(pin.applicationName() + ":" + sha);
        }
      }
      sources.add(
          answered(
              cdSource,
              startedCd,
              pins.size()
                  + " application pins over "
                  + keeps.size()
                  + " image shas — what is serving, and what a rollback would restore",
              pins.size(),
              keeps));
    } catch (RuntimeException unreachable) {
      String why = cdSource.name() + " deployment pins: " + message(unreachable);
      failures.add(why);
      sources.add(failed(cdSource, startedCd, why));
    }

    String daemonName = "";
    Instant startedCi = Instant.now();
    try {
      CiDaemonPins.DaemonPin pin = ciSource.read().get();
      daemonName = blank(pin.daemonName());
      // Blank is an ANSWER: qits-ci saying this deployment has pinned no daemon. Dropped from the
      // set rather than treated as a version, and never a failure.
      pin(pin.daemonVersion(), daemonVersions, blobs);
      pin(pin.previousDaemonVersion(), daemonVersions, blobs);
      Set<String> keeps = new TreeSet<>();
      for (String version : daemonVersions) {
        keeps.add(daemonName + "@" + version);
      }
      for (String blob : blobs) {
        // A digest rung pins BYTES as well as a row, and may name bytes no row exists for at all.
        // Spelled separately so the pins section shows both halves of what one rung protects.
        keeps.add("blob " + blob);
      }
      sources.add(
          answered(
              ciSource,
              startedCi,
              daemonVersions.isEmpty()
                  ? "no daemon is pinned (source: "
                      + blank(pin.source())
                      + ") — an answer, not an absence, and the shipped default"
                  : "daemon "
                      + daemonName
                      + ", "
                      + daemonVersions.size()
                      + " ladder rungs pinned (source: "
                      + blank(pin.source())
                      + ")",
              daemonVersions.size(),
              keeps));
    } catch (RuntimeException unreachable) {
      String why = ciSource.name() + " daemon pin: " + message(unreachable);
      failures.add(why);
      sources.add(failed(ciSource, startedCi, why));
    }

    Instant startedMaintenance = Instant.now();
    try {
      List<MaintenanceDependencyPins.DependencyPin> pins = maintenanceSource.read().get();
      for (MaintenanceDependencyPins.DependencyPin pin : pins) {
        // The coordinate each ecosystem's adapter already spells its identities with, so the
        // lookup on the other side is an equality test and never a translation.
        switch (pin.ecosystem()) {
          case "maven" -> mavenDependencies.add(pin.name() + ":" + pin.version());
          case "npm" -> npmDependencies.add(pin.name() + "@" + pin.version());
          case "docker" -> manifestImages.add(pin.name() + ":" + pin.version());
          // The separator is read off the adapter rather than retyped: a daemon identity is
          // `name@version` because that is how the download route spells it, and a copy of that
          // decision here is a copy that can be corrected in one place and not the other.
          case "daemon" ->
              daemonDependencies.add(
                  pin.name() + DaemonBinariesGcAdapter.AT + pin.version());
          default ->
              // Unreachable through the parser, which refuses an ecosystem it cannot file. Kept as
              // a refusal rather than a silent drop, because a pin filed nowhere is a keep lost.
              throw new IllegalStateException(
                  "cannot file a dependency pin for ecosystem " + pin.ecosystem());
        }
      }
      Set<String> keeps = new TreeSet<>();
      keeps.addAll(mavenDependencies);
      keeps.addAll(npmDependencies);
      keeps.addAll(manifestImages);
      // The daemon keeps are in the same section as the other three: they come from the same read
      // of the same document, and a reviewer checking why a binary survived reads one list.
      keeps.addAll(daemonDependencies);
      sources.add(
          answered(
              maintenanceSource,
              startedMaintenance,
              pins.size()
                  + " manifest references over "
                  + keeps.size()
                  + " coordinates ("
                  + mavenDependencies.size()
                  + " maven, "
                  + npmDependencies.size()
                  + " npm, "
                  + manifestImages.size()
                  + " docker, "
                  + daemonDependencies.size()
                  + " daemon) — what repositories on main still build against",
              pins.size(),
              keeps));
    } catch (RuntimeException unreachable) {
      String why = maintenanceSource.name() + " dependency pins: " + message(unreachable);
      failures.add(why);
      sources.add(failed(maintenanceSource, startedMaintenance, why));
      // A half-folded keep-set is worse than none: the run refuses anyway, and leaving partial
      // coordinates behind would make a report claim keeps this source did not finish stating.
      mavenDependencies.clear();
      npmDependencies.clear();
      manifestImages.clear();
      daemonDependencies.clear();
    }

    Instant startedConfiguration = Instant.now();
    try {
      List<ConfigurationImagePins.ImagePin> pins = configurationSource.read().get();
      for (ConfigurationImagePins.ImagePin pin : pins) {
        configuredImages.add(pin.image() + ":" + pin.version());
      }
      sources.add(
          answered(
              configurationSource,
              startedConfiguration,
              pins.size()
                  + " configured entries over "
                  + configuredImages.size()
                  + " image coordinates — what a launch would pull, which no deployment row names",
              pins.size(),
              configuredImages));
    } catch (RuntimeException unreachable) {
      String why = configurationSource.name() + " configured images: " + message(unreachable);
      failures.add(why);
      sources.add(failed(configurationSource, startedConfiguration, why));
      configuredImages.clear();
    }

    // The two EFFECTIVE sources. Same fold twice, because they are two services answering the same
    // question about themselves — and two sets rather than one, because a report has to be able to
    // say which of them saved a tag.
    foldLaunches(
        workspacesSource,
        workspaceLaunchImages,
        "a workspace or editor start",
        failures,
        sources);
    foldLaunches(
        projectsSource, projectLaunchImages, "an agent or refinement start", failures, sources);

    return new GcPins(
        deployments,
        daemonName,
        daemonVersions,
        blobs,
        mavenDependencies,
        npmDependencies,
        manifestImages,
        daemonDependencies,
        configuredImages,
        workspaceLaunchImages,
        projectLaunchImages,
        failures,
        sources);
  }

  /**
   * One launching service's answer, folded into its own keep-set.
   *
   * <p>Shared between the two because the shape and the rule are identical — {@code image:version},
   * cleared whole on a failure so no half-folded keep-set outlives the source that stopped stating
   * it. What is <b>not</b> shared is the set or the report line: each service gets its own of both,
   * so an outage names one service and a keep names one reason.
   */
  private static void foldLaunches(
      Source<List<LaunchImagePins.LaunchPin>> source,
      Set<String> images,
      String start,
      List<String> failures,
      List<GcPinSource> sources) {
    Instant startedAt = Instant.now();
    try {
      List<LaunchImagePins.LaunchPin> pins = source.read().get();
      for (LaunchImagePins.LaunchPin pin : pins) {
        // `launches` is provenance and decides nothing: two kinds of start pulling one image are
        // one keep, which is exactly what a set says.
        images.add(pin.image() + ":" + pin.version());
      }
      sources.add(
          answered(
              source,
              startedAt,
              pins.size()
                  + " launch images over "
                  + images.size()
                  + " coordinates — what "
                  + start
                  + " would pull TODAY, which the configured version only names after the next"
                  + " deploy",
              pins.size(),
              images));
    } catch (RuntimeException unreachable) {
      String why = source.name() + " launch pins: " + message(unreachable);
      failures.add(why);
      sources.add(failed(source, startedAt, why));
      images.clear();
    }
  }

  private static GcPinSource answered(
      Source<?> source, Instant startedAt, String outcome, int pinCount, Set<String> keeps) {
    return new GcPinSource(
        source.name(),
        source.url(),
        true,
        outcome,
        startedAt,
        took(startedAt),
        pinCount,
        List.copyOf(keeps));
  }

  private static GcPinSource failed(Source<?> source, Instant startedAt, String why) {
    return new GcPinSource(
        source.name(), source.url(), false, why, startedAt, took(startedAt), 0, List.of());
  }

  private static long took(Instant startedAt) {
    return Duration.between(startedAt, Instant.now()).toMillis();
  }

  /**
   * One rung of the ladder. A 64-hex version pins the blob at that digest as well as the row —
   * the pin has historically been a digest, fetched as {@code /v2/qits/ci-daemon/blobs/sha256:…},
   * and a version-addressed row may not exist for it at all.
   */
  private static void pin(String version, Set<String> versions, Set<String> blobs) {
    String trimmed = blank(version);
    if (trimmed.isEmpty()) {
      return;
    }
    versions.add(trimmed);
    if (GcPins.DIGEST.matcher(trimmed).matches()) {
      blobs.add(trimmed);
    }
  }

  private static String blank(String value) {
    return value == null ? "" : value.trim();
  }

  private static String message(RuntimeException failed) {
    String message = failed.getMessage();
    return failed.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
