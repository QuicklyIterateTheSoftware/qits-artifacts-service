package eu.wohlben.qits.stories.support;

import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>incoming</b> tap for the command-line stories: the launched process' own access log, read
 * back as {@link NetworkCapture} edges.
 *
 * <p>A RestAssured filter cannot serve these stories the way it serves {@code
 * TokenValidationBootstrapIT}. The subject here is a <b>real external tool</b> — {@code npm
 * publish}, {@code mvn deploy-file}, {@code skopeo copy}, {@code curl} — talking to the packaged
 * process over a socket this JVM never touches. Nothing in the test process is on that path, so the
 * only place the traffic exists is the server's own record of it. {@code
 * quarkus.http.access-log.*} is that record; {@link #configOverrides()} is the whole configuration
 * and {@code PackagedProcessIT.TargetDirState} is the one profile that installs it.
 *
 * <h2>The pattern, and what each token actually yields</h2>
 *
 * <p>{@code %m %U %s} — method, requested URL, response status. <b>{@code %U} is {@code
 * HttpServerRequest.uri()}</b>, so it carries the query string as well as the path; that is
 * deliberate rather than tolerated, because two of these stories are <i>about</i> a query (the CI
 * media plane's {@code ?meta.…&latest} golden lookup) and a path-only token would erase the thing
 * they document. {@code %R} is the path-only spelling if that ever has to change.
 *
 * <h2>Attribution: one CLI actor per story</h2>
 *
 * <p>A cumulative source is read <b>lazily, at story end</b>, and the framework's per-source cursor
 * hands each recorded line to exactly one story. Both the actor and the kind are therefore read at
 * <i>drain</i> time, which means a story gets <b>one</b> initiator and <b>one</b> kind for every
 * line it drains. Every story here is one tool doing one job, so that is exactly right — but it is
 * a real limitation and a story that ever drives two different tools would need the edges telling
 * apart another way.
 *
 * <p>{@link #attribute} is what a story calls, once, before its first request. It sets both halves
 * together on purpose: {@link NetworkCapture} resets the actor at every story border but nothing
 * can reset a kind this class invented, so a story that set only the actor would silently inherit
 * the previous story's kind. One call cannot drift.
 *
 * <h2>The floor</h2>
 *
 * <p>The log file is append-only across the whole IT phase — one launched process serves {@code
 * PackagedProcessIT} and all nineteen stories — and it survives between builds. So the source
 * registers a <b>floor</b> the first time a story attributes anything: every line already in the
 * file at that moment belongs to an earlier build or to {@code PackagedProcessIT}, and none of it
 * is any story's traffic. That works because the class order puts {@code PackagedProcessIT} ahead
 * of every story class ({@code UserflowClassOrderer} breaks ties by class name, and {@code
 * eu.wohlben.qits.PackagedProcessIT} sorts before {@code eu.wohlben.qits.stories.…}). <b>A new
 * non-story IT under this profile whose name sorts after {@code stories} would land its traffic in
 * the first story's diagram</b> — that is the one thing to re-check here.
 *
 * <p>Probes are skipped, as in every tap: this service's non-application root is {@code
 * /artifacts/q}, so the check is on the {@code /q/} <b>segment</b> and a diagram in which every
 * node hangs off {@code /q/health/ready} never happens.
 */
public final class AccessLogSource {

  /** The {@code to} of every edge this tap observes — the launched process, as a diagram names it. */
  public static final String SERVICE = "qits-artifacts";

  /** One registration per JVM; re-registering under this id would keep the cursor anyway. */
  private static final String SOURCE_ID = "access-log";

  /**
   * The file name halves. Quarkus resolves the access log as {@code
   * <log-directory>/<base-file-name><log-suffix>} and {@code rotate=false} keeps it at that one
   * name for the life of the build — a rotated file would leave the tail of a run in a sibling this
   * class never reads.
   */
  private static final String BASE_FILE_NAME = "story-access";

  private static final String LOG_SUFFIX = ".log";

  /**
   * How long {@link #awaitLogged} waits for a line to reach disk. The receiver writes on its own
   * executor and flushes per batch, so the gap between a tool's response and the line existing is
   * milliseconds — this is a ceiling, not a budget.
   */
  private static final Duration FLUSH_PATIENCE = Duration.ofSeconds(5);

  private static final long POLL_MILLIS = 25;

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many lines the file already held when the first story attributed traffic. */
  private static int floor;

  /** The story-scoped edge kind, read at drain time exactly as the actor is. */
  private static volatile String kind = NetworkEdge.HTTP;

  private AccessLogSource() {}

  // --- configuration ---------------------------------------------------------------------------

  /**
   * Where the launched process writes, as an <b>absolute</b> path. The process is started with a
   * working directory this suite does not choose, so a relative {@code log-directory} would put the
   * file somewhere nothing here could find; and it sits under {@code target/} so a {@code clean}
   * takes it.
   */
  public static Path logDirectory() {
    return Path.of(System.getProperty("user.dir"), "target", "packaged-it", "access-log")
        .toAbsolutePath();
  }

  /** The single file {@link #configOverrides()} configures and this class reads. */
  public static Path logFile() {
    return logDirectory().resolve(BASE_FILE_NAME + LOG_SUFFIX);
  }

  /**
   * The access-log block a launched process needs for these stories to have a diagram at all.
   * Every key is <b>runtime</b> configuration ({@code VertxHttpConfig}, not {@code
   * VertxHttpBuildTimeConfig}), so it reaches an already-built artifact as a {@code -D} flag and
   * nothing re-augments.
   */
  public static Map<String, String> configOverrides() {
    try {
      Files.createDirectories(logDirectory());
    } catch (IOException unwritable) {
      throw new IllegalStateException("cannot create " + logDirectory(), unwritable);
    }
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("quarkus.http.access-log.enabled", "true");
    overrides.put("quarkus.http.access-log.log-to-file", "true");
    overrides.put("quarkus.http.access-log.pattern", "%m %U %s");
    overrides.put("quarkus.http.access-log.log-directory", logDirectory().toString());
    overrides.put("quarkus.http.access-log.base-file-name", BASE_FILE_NAME);
    overrides.put("quarkus.http.access-log.log-suffix", LOG_SUFFIX);
    overrides.put("quarkus.http.access-log.rotate", "false");
    return overrides;
  }

  // --- what a story calls ----------------------------------------------------------------------

  /**
   * Name the initiator and the kind of traffic this story is about to make, and register the tap if
   * this is the first story to do so.
   *
   * <p>Both halves travel together because both are read at drain time and only one of them is
   * reset for you. {@code kind} is {@link NetworkEdge#PACKAGE} wherever the flow <i>is</i> a
   * package-manager exchange — an npm publish, a maven deploy, an image copy, a daemon binary, a
   * docs bundle — whatever transport carries it, and {@link NetworkEdge#HTTP} where the story is a
   * plain call against the JSON API or a browser reading the explorer.
   */
  public static void attribute(String actor, String kind) {
    register();
    NetworkCapture.actor(actor);
    AccessLogSource.kind = kind;
  }

  /**
   * Wait, briefly and without asserting anything, for a line containing {@code fragment} to reach
   * the log file.
   *
   * <p>The receiver writes off the request thread, so a tool's response can be back before the line
   * is on disk — and a line that lands after the story's drain is a line in the <i>next</i> story's
   * diagram. A story therefore calls this once its last interesting request has answered.
   *
   * <p>Deliberately silent on timeout: this is a latency hedge, not a proof. The proof is the
   * {@code assertEdge} in the class's {@code @AfterAll}, and a failure there says which edge is
   * missing, which a timeout here would only obscure.
   */
  public static void awaitLogged(String fragment) {
    long deadline = System.nanoTime() + FLUSH_PATIENCE.toNanos();
    while (true) {
      for (String line : recordedLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      try {
        Thread.sleep(POLL_MILLIS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  // --- the source ------------------------------------------------------------------------------

  /**
   * Register the cumulative source once per JVM, taking the current end of the file as the floor.
   * Called from {@link #attribute}, so the first story to name an actor is what bounds what any
   * story can see.
   */
  private static void register() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = readLines().size();
      NetworkCapture.source(SOURCE_ID, AccessLogSource::edges);
      registered = true;
    }
  }

  /**
   * The whole recording, every time — the contract {@link NetworkCapture#source} states, with the
   * cursor deciding which slice of it belongs to the story now draining. The actor and the kind are
   * read here, on the drain, which is what gives a story one initiator for all of its lines.
   */
  private static List<NetworkEdge> edges() {
    String actor = NetworkCapture.actor();
    String edgeKind = kind;
    List<NetworkEdge> edges = new ArrayList<>();
    for (String line : recordedLines()) {
      // "%m %U %s" — three fields, no quoting, and a URI can carry no raw space.
      String[] fields = line.strip().split(" ");
      if (fields.length != 3) {
        continue;
      }
      String method = fields[0];
      String uri = fields[1];
      String status = fields[2];
      // An attribute the handler could not resolve is written as "-"; such a line describes no
      // request anybody made and is not an edge.
      if (!uri.startsWith("/") || !status.chars().allMatch(Character::isDigit)) {
        continue;
      }
      // The probe root is /artifacts/q here, so the check is on the segment rather than a prefix.
      if (uri.contains("/q/")) {
        continue;
      }
      edges.add(
          new NetworkEdge(
              edgeKind, actor, SERVICE, method + " " + Labels.scrub(uri) + " -> " + status));
    }
    return edges;
  }

  /** Everything logged since the floor — i.e. everything a story could own. */
  private static List<String> recordedLines() {
    List<String> all = readLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The log file's complete lines. A missing file is an empty recording rather than a failure — the
   * suite must stay green on a machine that skipped every CLI story — and an <b>unterminated tail
   * is dropped</b>, because the writer is appending while this reads and half a line would shape
   * half an edge. The next drain sees it whole.
   */
  private static List<String> readLines() {
    Path file = logFile();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }
}
