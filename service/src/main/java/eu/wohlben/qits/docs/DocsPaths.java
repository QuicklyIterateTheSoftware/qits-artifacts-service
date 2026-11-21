package eu.wohlben.qits.docs;

/**
 * The route grammar for {@code /artifacts/docs}.
 *
 * <p>One regex per route with named {@code (?<name>…)} groups, the {@code RegistryPaths}/{@code
 * NpmPaths}/{@code MavenPaths}/{@code DaemonPaths} pattern.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with no error
 * anywhere.
 *
 * <p>Matching runs against {@code normalizedPath()}, which collapses dot-segments before routing —
 * so {@code ..} never reaches a handler and {@link #FILE} cannot be walked out of its version. (The
 * publish path has to defend itself separately: a tar entry's name is not a URL and no router
 * normalises it. That is {@link DocsBundle}'s job.)
 *
 * <p><b>{@code /-/} is what separates a multi-segment name from its version</b>, and it is npm's
 * separator rather than a new invention — {@code NpmPaths.TARBALL} already puts {@code /-/} between
 * a package and its file. A docs name is whatever namespacing the publishing project already uses:
 * {@code @qits/ui-components} where there is an npm package, {@code someproject/somelib} where there
 * is not. With a variable-depth name, {@code <name>/<version>/<path>} is ambiguous — three segments
 * could be split three ways — so the grammar needs a marker, and the one already in this codebase is
 * the right one to reuse.
 *
 * <p><b>The ambiguity is closed by the segment shape, not by ordering the routes.</b> A {@link
 * #SEGMENT} must begin with {@code @} or an alphanumeric, so a bare {@code -} is not a name segment
 * and {@code @qits/ui-components/-/1.0.0} cannot be read as a four-segment name however hard the
 * matcher tries. That is what lets {@link #SITE} and {@link #BUNDLE} coexist without either shadowing
 * the other, and it is the kind of property that stays true until someone loosens a character class
 * — so {@code DocsPathsTest} pins it.
 *
 * <p><b>A site name is accepted in two spellings, literal and percent-encoded, and they mean the
 * same name.</b> Matching runs against {@code normalizedPath()}, and in vertx-web that decodes only
 * the unreserved escapes — {@code %40} and {@code %2F} reach the regex exactly as sent. {@code curl}
 * in a pipeline and qits-docs send a real slash, but a generated client builds its path with {@code
 * encodeURIComponent}: the landing app asks for {@code docs/%40qits%2Fui-components}, and a grammar
 * that admitted only the literal characters answered it with the catch-all 404. So {@link #AT} and
 * {@link #SLASH} each take the character or its escape, either hex case, and a request may mix
 * them. Once a route has matched, {@code pathParam(…)} hands the handler the group <em>decoded</em>,
 * so the handler sees one site whichever spelling arrived. {@code NpmPaths} accepts {@code
 * (?:/|%2[fF])} for the same reason — its CLI sends {@code @scope%2fname} for a packument.
 *
 * <p>The escapes are admitted in exactly the places the characters are and nowhere else, so the
 * decoded name always satisfies the literal grammar: a segment still begins with {@code @} or an
 * alphanumeric however its separator was spelled, which keeps a bare {@code -} — and a {@code ..},
 * also when it arrives as {@code %2F..%2F} or as {@code %2F%2E%2E%2F}, which {@code normalizedPath()}
 * decodes to dots without collapsing — from ever being a name segment. The {@code /-/} separator and
 * the slash before {@link #PATH} are literal only.
 */
final class DocsPaths {

  private DocsPaths() {}

  /**
   * The mount point — a literal in the code exactly as {@code /artifacts/git}, {@code /artifacts/npm},
   * {@code /artifacts/maven} and {@code /artifacts/daemons} are. No config key moves it and no JAX-RS
   * test would notice if it drifted, which is why {@code DocsRegistryTest} spells its paths out
   * absolutely.
   */
  static final String BASE = "/artifacts/docs";

  /** The {@code artifact_repository} row, the first segment after {@link #BASE}. */
  private static final String REPOSITORY = "(?<repository>[a-z0-9][a-z0-9._-]{0,63})";

  /** {@code @} or its escape — a scope marker, only ever a segment's optional first character. */
  private static final String AT = "(?:@|%40)";

  /** {@code /} or its escape, either hex case — what separates two name segments. */
  private static final String SLASH = "(?:/|%2[fF])";

  /**
   * One component of a site name. The leading {@link #AT} is optional and the character after it is
   * not: that is the rule that makes a bare {@code -} and a {@code ..} unmatchable and the {@code
   * /-/} separator unambiguous.
   *
   * <p>No {@code :} and no escape in the tail — {@code SbomPaths}' segment admits a colon for maven
   * coordinates, and no site name carries one — so the 128 bound counts characters, sent and decoded
   * alike.
   */
  private static final String SEGMENT = "(?:" + AT + "?[A-Za-z0-9][A-Za-z0-9._~-]{0,127})";

  /**
   * {@code <site>} — {@code ui-components}, {@code @qits/ui-components}, or a deeper project path,
   * in either spelling; {@code pathParam("name")} returns it decoded.
   *
   * <p>Bounded at four segments. Not a technical limit — a depth cap keeps the matcher's work
   * bounded on a hostile path, and four is already deeper than any namespacing this platform has.
   * The cap counts segments, so an encoded {@link #SLASH} spends it exactly as a literal one does.
   */
  private static final String NAME = "(?<name>" + SEGMENT + "(?:" + SLASH + SEGMENT + "){0,3})";

  /** The version. Wide enough for calver and for a semver prerelease, {@code DaemonPaths}' shape. */
  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  /**
   * The bundle-relative file path. Deliberately permissive — whatever a documentation tool emitted
   * is what has to be servable — and safe because it is matched against the <b>normalised</b> path,
   * so it cannot contain a dot-segment, and because a path that names no {@code docs_file} row is a
   * 404 rather than a filesystem read.
   */
  private static final String PATH = "(?<path>.+)";

  /**
   * {@code /artifacts/docs/<repository>} — every site, with its version count and its newest.
   *
   * <p>Cannot collide with {@link #SITE}: that one needs a name segment after the repository, and
   * this one is the repository alone.
   */
  static final String SITES = route(REPOSITORY);

  /** {@code /artifacts/docs/<repository>/<site>} — the version list. */
  static final String SITE = route(REPOSITORY + "/" + NAME);

  /** {@code /artifacts/docs/<repository>/<site>/-/<version>} — publish, and the version's metadata. */
  static final String BUNDLE = route(REPOSITORY + "/" + NAME + "/-/" + VERSION);

  /** {@code /artifacts/docs/<repository>/<site>/-/<version>/<path>} — one file of a bundle. */
  static final String FILE = route(REPOSITORY + "/" + NAME + "/-/" + VERSION + "/" + PATH);

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling — the reason is
   * {@code MavenPaths.route}'s verbatim: a {@code static final String} initialised from a constant
   * expression is inlined by javac into every class that reads it, including the test, which would
   * then keep asserting against whatever the value was when it was last compiled.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
