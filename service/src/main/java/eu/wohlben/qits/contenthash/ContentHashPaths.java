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

  /** {@code SbomPaths.SEGMENT}, verbatim. */
  private static final String SEGMENT = "(?:@?[A-Za-z0-9][A-Za-z0-9._~:-]{0,127})";

  /** {@code SbomPaths.NAME}, verbatim: up to four segments. */
  private static final String NAME = "(?<name>" + SEGMENT + "(?:/" + SEGMENT + "){0,3})";

  /** {@code SbomPaths.VERSION}, verbatim. */
  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  /** {@code /artifacts/content-hashes/<ecosystem>/<name>/-/<version|newest>}. */
  static final String VERSIONED = route(ECOSYSTEM + "/" + NAME + "/-/" + VERSION);

  /** A method call, not concatenation, for {@code SbomPaths.route}'s constant-inlining reason. */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
