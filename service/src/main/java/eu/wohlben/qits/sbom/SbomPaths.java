package eu.wohlben.qits.sbom;

/**
 * The route grammar for {@code /artifacts/sboms}.
 *
 * <p>One regex per route with named {@code (?<name>…)} groups, the {@code RegistryPaths}/{@code
 * NpmPaths}/{@code MavenPaths}/{@code DaemonPaths}/{@code DocsPaths} pattern.
 *
 * <p><b>Every group here is either {@code (?<name>…)} or {@code (?:…)}, never a bare {@code (…)}.</b>
 * vertx-web compares {@code Matcher.groupCount()} against the named groups it scraped out of the
 * pattern and silently falls back to positional {@code param0…paramN} when the two disagree — so one
 * stray capturing group breaks every {@code pathParam(…)} on that route, at runtime, with no error
 * anywhere.
 *
 * <p>Matching runs against {@code normalizedPath()}, which collapses dot-segments before routing —
 * so {@code ..} never reaches a handler.
 *
 * <p><b>The path spells the {@code SoftwareRelease} identity and nothing else</b> — {@code
 * <packageType>/<packageName>/-/<version>}. There is no repository segment: the {@code sboms} row is
 * seeded and there is exactly one of it, so a segment naming it would be a constant the publisher
 * has to know and get right.
 *
 * <p><b>{@code /-/} is what separates a multi-segment name from its version</b>, npm's separator
 * rather than a new invention, and it is here for the same reason it is in {@code DocsPaths}: a
 * package name is variable depth — {@code qits/qits-artifacts} for docker, {@code
 * @qits/ui-components} for npm — so {@code <name>/<version>} is ambiguous without a marker.
 *
 * <p><b>The ambiguity is closed by the segment shape, not by ordering the routes.</b> A {@link
 * #SEGMENT} must begin with {@code @} or an alphanumeric, so a bare {@code -} is not a name segment
 * and {@code @qits/ui-components/-/1.0.0} cannot be read as a four-segment name however hard the
 * matcher tries. That is what lets {@link #PACKAGE} and {@link #DOCUMENT} coexist without either
 * shadowing the other, and it is the kind of property that stays true until someone loosens a
 * character class — so {@code SbomPathsTest} pins it.
 *
 * <p><b>A segment admits {@code :}, which {@code DocsPaths}' does not, and that is the one real
 * difference between the two grammars.</b> A maven {@code packageName} carries groupId AND
 * artifactId in a single value — {@code eu.wohlben.qits:qits-eventstream} — because that is how
 * {@code SoftwareRelease} travels it, and re-spelling it as two path segments here would make this
 * wire the one place the identity is written differently. The colon sits <em>inside</em> one
 * segment; it never separates two.
 *
 * <p><b>A name is accepted in two spellings, literal and percent-encoded, and they mean the same
 * name.</b> Matching runs against {@code normalizedPath()}, and in vertx-web that decodes only the
 * unreserved escapes — {@code %3A}, {@code %40} and {@code %2F} reach the regex exactly as sent. A
 * generated client builds its path with {@code encodeURIComponent}, so the landing app asks for
 * {@code maven/eu.wohlben.qits%3Aqits-eventstream} and {@code npm/%40qits%2Fui-components}, and a
 * grammar that admitted only the literal characters answered both with the catch-all 404. So
 * {@link #AT}, {@link #COLON} and {@link #SLASH} each take the character or its escape, either hex
 * case, and a request may mix them. Once a route has matched, {@code pathParam(…)} hands the handler
 * the group <em>decoded</em>, so the handler sees one name whichever spelling arrived and nothing
 * below the grammar changes. {@code NpmPaths} accepts {@code (?:/|%2[fF])} for the same reason.
 *
 * <p>The escapes are admitted in exactly the three places the characters are and nowhere else, so
 * the decoded name always satisfies the literal grammar: a segment still begins with {@code @} or an
 * alphanumeric however its separator was spelled, which keeps a bare {@code -} — and a {@code ..},
 * also when it arrives as {@code %2F..%2F} or as {@code %2F%2E%2E%2F}, which {@code normalizedPath()}
 * decodes to dots without collapsing — from ever being a name segment. The {@code /-/} separator is
 * literal only: an encoded {@code %2F-%2F} is no separator, and since a bare {@code -} is no segment
 * either, such a path matches no route at all.
 *
 * <p>The package type is matched loosely on purpose — see {@link #PACKAGE_TYPE}.
 */
final class SbomPaths {

  private SbomPaths() {}

  /**
   * The mount point — a literal in the code exactly as {@code /artifacts/npm}, {@code
   * /artifacts/maven}, {@code /artifacts/daemons} and {@code /artifacts/docs} are. No config key
   * moves it and no JAX-RS test would notice if it drifted, which is why {@code SbomRegistryTest}
   * spells its paths out absolutely.
   */
  static final String BASE = "/artifacts/sboms";

  /**
   * The declared artifact type — {@code npm}, {@code maven}, {@code docker} or {@code daemon}.
   *
   * <p><b>The set is validated in the handler, not here.</b> A grammar narrowed to the four names
   * would answer an unknown type with a 404, which reads as "no such route" and sends a publisher
   * looking for a mount point rather than at its own spelling. Matched loosely, the handler answers
   * {@code 400} naming {@code SbomRegistryService.PACKAGE_TYPES} — the answer a caller can act on,
   * and the one that stays right when a fifth type is declared.
   */
  private static final String PACKAGE_TYPE = "(?<packageType>[a-z]{2,16})";

  /** {@code @} or its escape — a scope marker, only ever a segment's optional first character. */
  private static final String AT = "(?:@|%40)";

  /** {@code :} or its escape, either hex case — legal inside a segment, never at its start. */
  private static final String COLON = "(?::|%3[aA])";

  /** {@code /} or its escape, either hex case — what separates two name segments. */
  private static final String SLASH = "(?:/|%2[fF])";

  /**
   * One component of a package name. The leading {@link #AT} is optional and the character after it
   * is not: that is the rule that makes a bare {@code -} and a {@code ..} unmatchable and the {@code
   * /-/} separator unambiguous.
   *
   * <p>{@link #COLON} is in the tail because a maven coordinate is one segment carrying two names. It
   * cannot re-open the separator question: the tail only says what may appear <em>after</em> the
   * mandatory alphanumeric or {@code @} first character.
   *
   * <p>The 128 bound counts <b>decoded characters</b>: an escape is one repetition of the tail, so
   * {@code %3A} costs what {@code :} costs, and a name is as long in either spelling.
   */
  private static final String SEGMENT =
      "(?:" + AT + "?[A-Za-z0-9](?:[A-Za-z0-9._~-]|" + COLON + "){0,127})";

  /**
   * {@code <packageName>} — {@code eu.wohlben.qits:qits-eventstream}, {@code @qits/ui-components},
   * {@code qits/qits-artifacts}, or a bare name — or the same name percent-encoded, which {@code
   * pathParam("name")} returns decoded.
   *
   * <p>Bounded at four segments, {@code DocsPaths.NAME}'s cap and for its reason: not a technical
   * limit, but a depth cap that keeps the matcher's work bounded on a hostile path, and four is
   * already deeper than any name qits-ci can declare. The cap counts segments, so an encoded {@link
   * #SLASH} spends it exactly as a literal one does.
   */
  private static final String NAME = "(?<name>" + SEGMENT + "(?:" + SLASH + SEGMENT + "){0,3})";

  /** The version. Wide enough for calver and for a semver prerelease, {@code DocsPaths}' shape. */
  private static final String VERSION = "(?<version>[A-Za-z0-9][A-Za-z0-9._+-]{0,127})";

  /**
   * {@code /artifacts/sboms/<packageType>/<packageName>} — every stored version of one package,
   * newest first.
   *
   * <p>Cannot collide with {@link #DOCUMENT}: that one needs a {@code /-/} after the name, and a
   * bare {@code -} is not a name segment.
   */
  static final String PACKAGE = route(PACKAGE_TYPE + "/" + NAME);

  /**
   * {@code /artifacts/sboms/<packageType>/<packageName>/-/<version>} — publish, and the document
   * itself.
   */
  static final String DOCUMENT = route(PACKAGE_TYPE + "/" + NAME + "/-/" + VERSION);

  /**
   * Builds a route regex under {@link #BASE}.
   *
   * <p>A method call rather than string concatenation, and that is not styling — the reason is
   * {@code DocsPaths.route}'s verbatim: a {@code static final String} initialised from a constant
   * expression is inlined by javac into every class that reads it, including the test, which would
   * then keep asserting against whatever the value was when it was last compiled.
   */
  private static String route(String suffix) {
    return BASE + "/" + suffix;
  }
}
