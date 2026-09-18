package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every live pin one run holds, read once at its start and never cached.
 *
 * <p>Six services answer it, and they answer six different questions. qits-platform-deployments
 * names the image shas a restart or a rollback would pull; qits-ci names the daemon versions its
 * ladder would launch; qits-platform-maintenance names the internal maven, npm, docker and
 * <b>daemon</b> versions repositories' manifests still <b>reference</b> on main; qits-configuration
 * names the container images the platform is <b>configured</b> to launch; qits-workspaces and
 * qits-projects each name
 * the images they would launch <b>today</b>. All six are facts no timestamp in this store implies —
 * a container running untouched for months still pulls its sha the moment it restarts, and a library
 * nothing has resolved this month is still what every build of its consumer needs — which is why
 * pinned is a keep-class the engines check <b>before</b> the access rule.
 *
 * <p><b>The four consumption sources exist because age stopped carrying the safety.</b> The windows
 * are short now (P3D), which is only defensible while what is in use is named explicitly rather than
 * inferred from a pull that may not have happened inside three days. The deployment and ladder pins
 * cover what is running; these four cover what is referenced, what would be configured and what
 * would actually be pulled.
 *
 * <p><b>The last two are the fourth source in a different tense, and they close its residual.</b>
 * qits-configuration holds the version the NEXT deploy of a launching service will be handed; the
 * service running right now still launches whatever it was deployed with. For as long as that gap
 * lasts — and it can last days — the configured coordinate names an image nobody pulls while the one
 * every workspace start pulls is named by nothing at all, kept alive only by the access the short
 * window stopped trusting. So each launching service answers for itself, and the two answers are
 * kept as separate sets: a report that folded them into {@code configuredImages} could not say which
 * of the two saved a tag, which is precisely the distinction a reviewer is checking.
 *
 * <p><b>The maintenance source's fourth ecosystem is DERIVED, and that is why it exists.</b> No
 * manifest on this platform spells a daemon binary: a daemon's version travels inside the maven or
 * npm coordinate that <b>carries</b> it — qits-ci's pom pins {@code
 * eu.wohlben.qits:qits-platform-access-cli-binary}, and that version IS the coordinate the daemons
 * store files the {@code qits} CLI under. So a pinned carrier resolves to the repository that
 * released it and to every daemon that repository released at the same version, and
 * qits-platform-maintenance answers those as {@code daemon} rows. Without them a daemon binary two
 * poms pin is named by no pin source at all: qits-ci's ladder speaks for its own daemon and for
 * nothing else, the window is {@code P0D}, and the belt keeps two versions — so a pinned version
 * three releases old rots and every pipeline resolving it 404s.
 *
 * <p><b>One aggregate per run, taken at the start.</b> Not per type and not per strategy: two
 * fetches inside one run can disagree, and the disagreement is a deployment that landed between them
 * whose image the second half of the run then condemns. Never cached across runs either — a warm pin
 * list deletes the image that was deployed while it was warm.
 *
 * <p><b>{@link #failures()} is what makes a run refuse.</b> A source that could not be reached or
 * read leaves its reason here, and the collector's answer is all-or-nothing rather than per type:
 * {@code GcSweepExecutor} aborts the whole run with nothing deleted, and {@code GcPlanner} still
 * answers — a dry-run that 500s tells a reviewer nothing — but marks itself non-executable and
 * reports the pin-dependent types as failed.
 *
 * @param deployments application (and therefore image) name to every sha qits-platform-deployments pins for it
 * @param daemonName the daemon qits-ci's ladder is about, blank when it named none
 * @param daemonVersions the pinned daemon versions — the current rung and its fallback, blanks
 *     dropped, because a blank is qits-ci saying "nothing is pinned" rather than naming a version
 * @param blobs digests pinned directly: a daemon version that is 64 hex characters names the blob it
 *     is fetched by as well as any row keyed with it
 * @param mavenDependencies maven coordinates some repository's manifest references, spelled {@code
 *     groupId:artifactId:version} — which is {@code MavenPackagesGcAdapter}'s identity verbatim
 * @param npmDependencies npm coordinates some repository's manifest references, spelled {@code
 *     name@version} — which is {@code NpmPackagesGcAdapter}'s identity verbatim
 * @param manifestImages images some repository's Dockerfile references, spelled {@code image:tag}
 *     with the <b>full</b> image name ({@code qits/workspace-base:2026.902.143920})
 * @param daemonDependencies daemon binaries a pinned maven or npm coordinate carries, spelled {@code
 *     name@version} — which is {@code DaemonBinariesGcAdapter}'s identity verbatim, down to its
 *     {@code AT} separator. Beside {@link #daemonVersions} rather than folded into it: that set is
 *     qits-ci's ladder for one daemon, this one is any daemon any repository's manifest reaches
 *     through a carrier, and a receipt has to be able to say which of the two saved a version
 * @param configuredImages images qits-configuration is configured to launch, spelled the same way
 * @param workspaceLaunchImages images qits-workspaces would launch today, spelled the same way —
 *     the EFFECTIVE coordinate, which lags {@code configuredImages} until that service is redeployed
 * @param projectLaunchImages images qits-projects would launch today, on the same terms
 * @param failures one sentence per pin source that could not answer; empty is a complete aggregate
 * @param sources how this aggregate was read — one entry per source, with its url, its outcome, how
 *     long it took and the keep-identities it produced. Carried on the aggregate rather than
 *     assembled at the report, so a plan and a sweep receipt state the same provenance for the same
 *     run without either of them re-deriving it.
 */
public record GcPins(
    Map<String, Set<String>> deployments,
    String daemonName,
    Set<String> daemonVersions,
    Set<String> blobs,
    Set<String> mavenDependencies,
    Set<String> npmDependencies,
    Set<String> manifestImages,
    Set<String> daemonDependencies,
    Set<String> configuredImages,
    Set<String> workspaceLaunchImages,
    Set<String> projectLaunchImages,
    List<String> failures,
    List<GcPinSource> sources) {

  /** The rule an image sha is kept under when a deployment holds it. */
  public static final String BY_CD = "pinned by a qits-platform-deployments deployment";

  /** The rule a daemon binary is kept under when qits-ci's ladder holds it. */
  public static final String BY_CI = "pinned by qits-ci daemon ladder";

  /** The rule a version is kept under when some repository's manifest on main still names it. */
  public static final String BY_MANIFEST =
      "referenced by a repository manifest on main (qits-platform-maintenance dependency pins)";

  /**
   * The rule a daemon binary is kept under when a coordinate a manifest on main references carries
   * it.
   *
   * <p>Distinct from {@link #BY_MANIFEST} although it comes from the same source and the same
   * document: a receipt is read to decide whether a version may go, and "a pom names this jar" and
   * "a pom names a jar whose release also shipped this executable" are different claims about
   * different bytes. Distinct from {@link #BY_CI} for the same reason in the other direction — the
   * ladder is what a runner would launch, this is what a build would download.
   */
  public static final String BY_CARRIED_DAEMON =
      "a daemon binary carried by a coordinate a repository manifest on main references"
          + " (qits-platform-maintenance dependency pins)";

  /** The rule an image is kept under when the platform is configured to launch it. */
  public static final String BY_CONFIGURATION = "a configured container image (qits-configuration)";

  /** The rule an image is kept under when qits-workspaces would pull it on the next start. */
  public static final String BY_WORKSPACE_LAUNCH =
      "the image qits-workspaces would launch today (effective pin)";

  /** The rule an image is kept under when qits-projects would pull it on the next start. */
  public static final String BY_PROJECT_LAUNCH =
      "the image qits-projects would launch today (effective pin)";

  /** A pin spelled as a digest — the historic {@code QITS_CI_DAEMON_VERSION} shape. */
  static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

  public GcPins {
    deployments = Map.copyOf(deployments);
    daemonVersions = Set.copyOf(daemonVersions);
    blobs = Set.copyOf(blobs);
    mavenDependencies = Set.copyOf(mavenDependencies);
    npmDependencies = Set.copyOf(npmDependencies);
    manifestImages = Set.copyOf(manifestImages);
    daemonDependencies = Set.copyOf(daemonDependencies);
    configuredImages = Set.copyOf(configuredImages);
    workspaceLaunchImages = Set.copyOf(workspaceLaunchImages);
    projectLaunchImages = Set.copyOf(projectLaunchImages);
    failures = List.copyOf(failures);
    sources = List.copyOf(sources);
  }

  /**
   * The execution pins alone — the shape a case about deployments or the daemon ladder states, with
   * the consumption sets empty because it is not about them.
   *
   * <p>A defaulted overload rather than a new spelling at every call site: the members are on the
   * record and a reader of the report sees every set, but a case that is about one of them should
   * not have to write out the rest empty to say so.
   *
   * <p>{@link #sources()} is how a <b>run</b> read its pins, so a value constructed in a test has
   * none to report and must not invent one: an empty list reads as "this aggregate was not fetched",
   * which is exactly true of one written by hand.
   */
  public GcPins(
      Map<String, Set<String>> deployments,
      String daemonName,
      Set<String> daemonVersions,
      Set<String> blobs,
      List<String> failures) {
    this(deployments, daemonName, daemonVersions, blobs, failures, List.of());
  }

  /** The same, for a run that read its pins and has provenance to report. */
  public GcPins(
      Map<String, Set<String>> deployments,
      String daemonName,
      Set<String> daemonVersions,
      Set<String> blobs,
      List<String> failures,
      List<GcPinSource> sources) {
    this(
        deployments,
        daemonName,
        daemonVersions,
        blobs,
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        failures,
        sources);
  }

  /**
   * The four keep-sets a stored fact can name, and no provenance — what a case about manifests or
   * configured images states.
   *
   * <p>Kept beside the wider overload below rather than folded into it, so the cases written before
   * the two effective sources existed still say exactly what they meant: those keep-sets are empty
   * because that case is not about a launching service.
   */
  public GcPins(
      Map<String, Set<String>> deployments,
      String daemonName,
      Set<String> daemonVersions,
      Set<String> blobs,
      Set<String> mavenDependencies,
      Set<String> npmDependencies,
      Set<String> manifestImages,
      Set<String> configuredImages,
      List<String> failures) {
    this(
        deployments,
        daemonName,
        daemonVersions,
        blobs,
        mavenDependencies,
        npmDependencies,
        manifestImages,
        Set.of(),
        configuredImages,
        Set.of(),
        Set.of(),
        failures,
        List.of());
  }

  /**
   * Every keep-set a stored fact could name before the daemon ecosystem existed, and no provenance.
   *
   * <p>Kept beside the whole one below on the terms the overload above states: the cases written
   * when the maintenance source filed three ecosystems still say exactly what they meant, and what
   * they meant is that no carried daemon is pinned.
   */
  public GcPins(
      Map<String, Set<String>> deployments,
      String daemonName,
      Set<String> daemonVersions,
      Set<String> blobs,
      Set<String> mavenDependencies,
      Set<String> npmDependencies,
      Set<String> manifestImages,
      Set<String> configuredImages,
      Set<String> workspaceLaunchImages,
      Set<String> projectLaunchImages,
      List<String> failures) {
    this(
        deployments,
        daemonName,
        daemonVersions,
        blobs,
        mavenDependencies,
        npmDependencies,
        manifestImages,
        Set.of(),
        configuredImages,
        workspaceLaunchImages,
        projectLaunchImages,
        failures,
        List.of());
  }

  /** Every keep-set and no provenance — what a case that hands pins in directly is stating. */
  public GcPins(
      Map<String, Set<String>> deployments,
      String daemonName,
      Set<String> daemonVersions,
      Set<String> blobs,
      Set<String> mavenDependencies,
      Set<String> npmDependencies,
      Set<String> manifestImages,
      Set<String> daemonDependencies,
      Set<String> configuredImages,
      Set<String> workspaceLaunchImages,
      Set<String> projectLaunchImages,
      List<String> failures) {
    this(
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
        List.of());
  }

  /** Nothing pinned and nothing broken — the shape a test states explicitly. */
  public static GcPins none() {
    return new GcPins(Map.of(), "", Set.of(), Set.of(), List.of());
  }

  /** Whether every pin source answered. A run may only delete when this is true. */
  public boolean complete() {
    return failures.isEmpty();
  }

  /** The failures as one sentence, for a receipt that has to say why it did nothing. */
  public String whyIncomplete() {
    return String.join("; ", failures);
  }

  /** Every sha qits-platform-deployments pins for an image name. */
  public Set<String> deploymentShas(String image) {
    return deployments.getOrDefault(image, Set.of());
  }

  /** {@link #BY_CD} when a deployment pins this image's tag, else null. */
  public String pinsImageTag(String image, String tag) {
    return deploymentShas(image).contains(tag) ? BY_CD : null;
  }

  /**
   * {@link #BY_CI} when qits-ci's ladder pins this daemon version, else null.
   *
   * <p>The name is compared too: {@code daemon_binary} rows are keyed {@code (repository, name,
   * version)}, and a second daemon's identically named version is not what qits-ci pinned.
   */
  public String pinsDaemonVersion(String name, String version) {
    return daemonName.equals(name) && daemonVersions.contains(version) ? BY_CI : null;
  }

  /**
   * {@link #BY_CARRIED_DAEMON} when a coordinate some repository's manifest references carries this
   * daemon binary, else null.
   *
   * <p>The argument is the adapter's identity unchanged — {@code name@version}, {@code
   * DaemonBinariesGcAdapter}'s own spelling down to the separator — so there is no translation here
   * to get wrong, which is the rule every consumption keep-set joins on.
   *
   * <p>A sibling of {@link #pinsDaemonVersion} rather than a second answer from it: that method
   * means "qits-ci's ladder holds this", and widening it to mean "somebody holds this" would take
   * the one distinction a receipt is read for out of the report.
   */
  public String pinsCarriedDaemon(String coordinate) {
    return daemonDependencies.contains(coordinate) ? BY_CARRIED_DAEMON : null;
  }

  /** {@link #BY_CI} when a pin names one of these blobs by digest, else null. */
  public String pinsAnyBlob(Set<String> candidateBlobs) {
    for (String blobId : candidateBlobs) {
      if (blobs.contains(blobId)) {
        return BY_CI;
      }
    }
    return null;
  }

  /**
   * {@link #BY_MANIFEST} when a repository's pom still references this maven coordinate, else null.
   *
   * <p>The argument is the adapter's identity unchanged — {@code groupId:artifactId:version} is both
   * what a pom names and what {@code MavenPackagesGcAdapter} folds its rows into — so there is no
   * translation here to get wrong.
   */
  public String pinsMavenCoordinate(String coordinate) {
    return mavenDependencies.contains(coordinate) ? BY_MANIFEST : null;
  }

  /**
   * {@link #BY_MANIFEST} when a repository's package.json still references this npm coordinate, else
   * null. {@code name@version}, which is {@code NpmPackagesGcAdapter}'s identity verbatim.
   */
  public String pinsNpmCoordinate(String coordinate) {
    return npmDependencies.contains(coordinate) ? BY_MANIFEST : null;
  }

  /**
   * {@link #BY_MANIFEST} when a repository's Dockerfile still references this image's tag, else
   * null.
   *
   * @param image the <b>full</b> image name, {@code <repository row>/<image>} — what a Dockerfile
   *     writes and what an {@code oci_tag} row only carries in two halves
   */
  public String pinsManifestImage(String image, String tag) {
    return manifestImages.contains(image + ":" + tag) ? BY_MANIFEST : null;
  }

  /**
   * {@link #BY_CONFIGURATION} when the platform is configured to launch this image's tag, else null.
   * The full image name, for the reason {@link #pinsManifestImage} spells out.
   */
  public String pinsConfiguredImage(String image, String tag) {
    return configuredImages.contains(image + ":" + tag) ? BY_CONFIGURATION : null;
  }

  /**
   * {@link #BY_WORKSPACE_LAUNCH} when qits-workspaces would pull this image's tag on its next start,
   * else null. The full image name, for the reason {@link #pinsManifestImage} spells out.
   *
   * <p>Beside {@link #pinsConfiguredImage} rather than folded into it: the two answer for the same
   * image at two different versions whenever that service is behind its configuration, and which of
   * the two saved a tag is the fact a reviewer of the report is reading for.
   */
  public String pinsWorkspaceLaunchImage(String image, String tag) {
    return workspaceLaunchImages.contains(image + ":" + tag) ? BY_WORKSPACE_LAUNCH : null;
  }

  /**
   * {@link #BY_PROJECT_LAUNCH} when qits-projects would pull this image's tag on its next start,
   * else null. Same terms as {@link #pinsWorkspaceLaunchImage}.
   */
  public String pinsProjectLaunchImage(String image, String tag) {
    return projectLaunchImages.contains(image + ":" + tag) ? BY_PROJECT_LAUNCH : null;
  }
}
