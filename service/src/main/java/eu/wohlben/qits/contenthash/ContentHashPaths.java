package eu.wohlben.qits.contenthash;

/**
 * The route grammar for {@code /artifacts/content-hashes}.
 *
 * <p>{@code SbomPaths}' grammar, segment for segment: the same {@link #SEGMENT}, {@link #NAME} and
 * {@link #VERSION} shapes, so {@code eu.wohlben.qits:qits-foo} and {@code @qits/foo} travel through
 * {@code NAME} unchanged and {@code /-/} separates a multi-segment name from its version for the
 * reason it does there. The same rules bind: every group is {@code (?<name>…)} or {@code (?:…)},
 * never a bare {@code (…)}, or vertx-web falls back to positional params silently.
 *
 * <p>The percent-encoded spelling comes along with it: {@code %40}, {@code %3A} and {@code %2F} are
 * accepted wherever {@code @}, {@code :} and {@code /} are, because a generated client sends {@code
 * maven/eu.wohlben.qits%3Aqits-foo} and {@code normalizedPath()} keeps those escapes as received.
 * {@code pathParam("name")} returns the name decoded, so the handler reads one name either way, and
 * a segment still has to begin with {@code @} or an alphanumeric — a {@code ..} or a bare {@code -}
 * is no segment in either spelling, and {@code /-/} stays literal. {@code SbomPaths} carries the
 * reasoning in full.
 *
 * <p>The one difference is the first segment: {@link #ECOSYSTEM} is matched <b>strictly</b>, where
 * {@code SbomPaths.PACKAGE_TYPE} is loose. The sbom store answers an unknown type with a 400 because
 * a publisher has to fix its spelling; this surface is a read whose contract says an unknown
 * ecosystem is a {@code 404}, so the grammar itself refuses it and the base's catch-all answers.
 *
 * <p>{@code newest} is not a separate route: it is a {@link #VERSION} the handler reads as "the
 * highest stored version", which no maven calver or npm semver can ever spell.
 */
final class ContentHashPaths {

  private ContentHashPaths() {}

  /** The mount point, a literal like every sibling wire's. */
  static final String BASE = "/artifacts/content-hashes";

  /** The keyword {@code …/-/newest} resolves by version order. */
  static final String NEWEST = "newest";

  /** The two ecosystems the ledger records — {@code ContentHashLedger}'s own vocabulary. */
  private static final String ECOSYSTEM = "(?<ecosystem>maven|npm)";

  /** {@code SbomPaths.AT}, verbatim: {@code @} or {@code %40}. */
  private static final String AT = "(?:@|%40)";

  /** {@code SbomPaths.COLON}, verbatim: {@code :} or {@code %3A}, either hex case. */
  private static final String COLON = "(?::|%3[aA])";

  /** {@code SbomPaths.SLASH}, verbatim: {@code /} or {@code %2F}, either hex case. */
  private static final String SLASH = "(?:/|%2[fF])";

  /** {@code SbomPaths.SEGMENT}, verbatim: its 128 bound counts decoded characters, not bytes sent. */
  private static final String SEGMENT =
      "(?:" + AT + "?[A-Za-z0-9](?:[A-Za-z0-9._~-]|" + COLON + "){0,127})";

  /** {@code SbomPaths.NAME}, verbatim: up to four segments, in either spelling of the separator. */
  private static final String NAME = "(?<name>" + SEGMENT + "(?:" + SLASH + SEGMENT + "){0,3})";

  /** {@code SbomPaths.VERSION}, verbatim. */
  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  /** {@code /artifacts/content-hashes/<ecosystem>/<name>/-/<version|newest>}. */
  static final String VERSIONED = route(ECOSYSTEM + "/" + NAME + "/-/" + VERSION);

  /** A method call, not concatenation, for {@code SbomPaths.route}'s constant-inlining reason. */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
