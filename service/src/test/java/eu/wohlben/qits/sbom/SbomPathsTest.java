package eu.wohlben.qits.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — no Quarkus, because this is a property of the regexes and the
 * cases that matter are cheap to be exhaustive about. {@code SbomRegistryTest} then proves the router
 * actually behaves this way over the wire, which is the half a regex test cannot reach.
 *
 * <p>Two load-bearing properties, and both are character-class facts — exactly the kind that stay
 * true until someone loosens one, so they are asserted rather than trusted:
 *
 * <ul>
 *   <li>{@link #theTwoRoutesDoNotOverlap} — a package name may be several segments deep, so {@code
 *       <name>/<version>} could be split more than one way, and what makes it unambiguous is that a
 *       bare {@code -} is not a legal name segment.
 *   <li>{@link #aMavenCoordinateIsOneNameSegment} — the colon that carries a maven groupId and
 *       artifactId in one value must land <em>inside</em> the name group, never split it.
 * </ul>
 */
class SbomPathsTest {

  @Test
  void aMavenCoordinateIsOneNameSegment() {
    // The difference between this grammar and DocsPaths'. SoftwareRelease travels a maven artifact
    // as "groupId:artifactId", so the colon has to be an ordinary character inside one segment —
    // and it must not turn into a second segment, or the name the store keys on stops matching the
    // name the release event announced.
    String path = "/artifacts/sboms/maven/eu.wohlben.qits:qits-eventstream/-/2026.901.120000";
    assertEquals("maven", group(SbomPaths.DOCUMENT, path, "packageType"));
    assertEquals("eu.wohlben.qits:qits-eventstream", group(SbomPaths.DOCUMENT, path, "name"));
    assertEquals("2026.901.120000", group(SbomPaths.DOCUMENT, path, "version"));

    assertEquals(
        "eu.wohlben.qits:qits-eventstream",
        group(
            SbomPaths.PACKAGE,
            "/artifacts/sboms/maven/eu.wohlben.qits:qits-eventstream",
            "name"),
        "the listing route reads the same one segment");
  }

  @Test
  void aPackageNameMayBeScopedOrNested() {
    assertEquals(
        "@qits/ui-components",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/@qits/ui-components", "name"));
    assertEquals(
        "qits/qits-artifacts",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/docker/qits/qits-artifacts", "name"));
    assertEquals(
        "qits-ci-daemon",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/daemon/qits-ci-daemon", "name"));
    assertEquals(
        "a/b/c/d", group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a/b/c/d", "name"), "four deep");
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a/b/c/d/e"), "five is past the cap");
  }

  @Test
  void thePackageTypeIsMatchedLooselyOnPurpose() {
    // The set is the handler's to enforce, so an unknown type REACHES a handler and gets a 400
    // naming the allowed set. Narrowing the grammar to the four names would answer 404 instead,
    // which reads as "no such route" and sends a publisher looking for a mount point.
    assertEquals("gem", group(SbomPaths.PACKAGE, "/artifacts/sboms/gem/sinatra", "packageType"));
    // What it still refuses: a type is one short lowercase word, never a path and never a name.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/NPM/left-pad"), "uppercase type");
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/n/left-pad"), "one character");
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm2/left-pad"), "digits");
  }

  @Test
  void aDocumentPathSplitsIntoNameAndVersion() {
    String scoped = "/artifacts/sboms/npm/@qits/ui-components/-/2026.807.0";
    assertEquals("@qits/ui-components", group(SbomPaths.DOCUMENT, scoped, "name"));
    assertEquals("2026.807.0", group(SbomPaths.DOCUMENT, scoped, "version"));

    // A prerelease with build metadata is a legal version, so the grammar must not be the thing
    // that decides an SBOM cannot be attached to one.
    String prerelease = "/artifacts/sboms/npm/ui-components/-/1.0.0-main.gab854a1";
    assertEquals("1.0.0-main.gab854a1", group(SbomPaths.DOCUMENT, prerelease, "version"));
  }

  @Test
  void theTwoRoutesDoNotOverlap() {
    // THE property the grammar rests on. A name segment must begin with @ or an alphanumeric, so a
    // bare `-` cannot be one and `@qits/ui-components/-/1.0.0` cannot be read as a four-segment name
    // however hard the matcher tries. Without that, PACKAGE would swallow every DOCUMENT path and
    // the version listing would answer a publish URL.
    String document = "/artifacts/sboms/npm/@qits/ui-components/-/1.0.0";
    assertFalse(matches(SbomPaths.PACKAGE, document), "PACKAGE must not swallow a document path");

    String pkg = "/artifacts/sboms/npm/@qits/ui-components";
    assertFalse(matches(SbomPaths.DOCUMENT, pkg), "a package path has no version");

    // And the colon does not re-open it: a maven coordinate is still one segment, so its document
    // path still splits exactly once.
    String maven = "/artifacts/sboms/maven/eu.wohlben.qits:qits-eventstream/-/1.0.0";
    assertFalse(matches(SbomPaths.PACKAGE, maven));
  }

  @Test
  void aBareSeparatorIsNotAPackageName() {
    // The character-class fact the test above depends on, asserted directly so a loosening shows up
    // here as well as there.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/-"));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/-/-"));
    assertFalse(matches(SbomPaths.DOCUMENT, "/artifacts/sboms/npm/-/-/1.0.0"));
    // A colon cannot stand in for it either — it is legal INSIDE a segment, never as its first
    // character, so it can never begin one.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/maven/:qits-eventstream"));
  }

  @Test
  void namesOutsideTheGrammarNeverReachAHandler() {
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/.hidden"), "leading dot");
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/_private"), "leading underscore");
    // A scope with nothing after it is not a name — the npm rule, and for the same reason.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/@qits/"));
    // The base alone, and a type alone, are not routes: both fall through to the catch-all 404.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms"));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm"));
    // Uppercase in a package name is legal: a name mirrors whatever the publishing project calls
    // itself, and refusing one here would be this grammar deciding that for them.
    assertTrue(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/JSONStream"));
  }

  // --- the percent-encoded spelling -------------------------------------------------------------

  @Test
  void anEncodedNameMatchesAndCapturesItsRawSpelling() {
    // normalizedPath() keeps %3A, %40 and %2F as received, and a generated client sends exactly
    // those. The group is the RAW spelling here — the decoding is pathParam's, which
    // SbomRegistryTest proves over the wire.
    String maven = "/artifacts/sboms/maven/eu.wohlben.qits%3Aqits-eventstream/-/2026.901.120000";
    assertEquals("eu.wohlben.qits%3Aqits-eventstream", group(SbomPaths.DOCUMENT, maven, "name"));
    assertEquals("2026.901.120000", group(SbomPaths.DOCUMENT, maven, "version"));
    assertEquals(
        "eu.wohlben.qits%3Aqits-eventstream",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/maven/eu.wohlben.qits%3Aqits-eventstream", "name"));

    assertEquals(
        "%40qits%2Fui-components",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/%40qits%2Fui-components", "name"));
    assertEquals(
        "%40qits%2Fui-components",
        group(SbomPaths.DOCUMENT, "/artifacts/sboms/npm/%40qits%2Fui-components/-/1.0.0", "name"));
  }

  @Test
  void spellingsMayBeMixedAndEitherHexCaseIsAccepted() {
    assertEquals(
        "%40qits/ui-components",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/%40qits/ui-components", "name"));
    assertEquals(
        "@qits%2fui-components",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/@qits%2fui-components", "name"));
    assertEquals(
        "eu.wohlben.qits%3aqits-eventstream",
        group(SbomPaths.PACKAGE, "/artifacts/sboms/maven/eu.wohlben.qits%3aqits-eventstream", "name"));
    assertEquals(
        "a%2Fb/c%2fd", group(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a%2Fb/c%2fd", "name"), "four deep");
    assertFalse(
        matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a%2Fb%2Fc%2Fd%2Fe"),
        "an encoded separator spends the four-segment cap like a literal one");
  }

  @Test
  void theSegmentBoundCountsDecodedCharacters() {
    // An escape is one repetition of the segment's tail, so a name is exactly as long in either
    // spelling: 128 characters is the ceiling, and the escaped colon does not cost three.
    String literal = "a" + ":".repeat(127);
    String encoded = "a" + "%3A".repeat(127);
    assertTrue(matches(SbomPaths.PACKAGE, "/artifacts/sboms/maven/" + literal));
    assertTrue(matches(SbomPaths.PACKAGE, "/artifacts/sboms/maven/" + encoded));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/maven/" + literal + ":"));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/maven/" + encoded + "%3A"));
  }

  @Test
  void theEncodedSpellingNeverMakesADotSegmentOrABareSeparatorAName() {
    // The safety structure, in the spelling normalizedPath() does NOT collapse: %2F is no separator
    // to it, so `..` between two of them reaches the regex intact. %2E%2E is decoded to `..` before
    // matching, which is why the dotted form is the one asserted.
    for (String name :
        new String[] {
          "a%2F..%2Fb", "a%2f..%2fb", "%2F..%2Fb", "a/..%2Fb", "a%2F..", "..", "%2E%2E", "a%2F%2E%2E",
          "%2F", "a%2F", "%40", "%40%2Fb", "-", "a%2F-", "%3Aqits"
        }) {
      assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/" + name), name);
      assertFalse(matches(SbomPaths.DOCUMENT, "/artifacts/sboms/npm/" + name + "/-/1.0.0"), name);
    }
    // The /-/ separator is literal only. Encoded, it is no separator, and the bare `-` it wraps is no
    // segment, so the path is no route at all rather than a name with a version folded into it.
    assertFalse(matches(SbomPaths.DOCUMENT, "/artifacts/sboms/npm/%40qits%2Fui%2F-%2F1.0.0"));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/%40qits%2Fui%2F-%2F1.0.0"));
    assertFalse(matches(SbomPaths.DOCUMENT, "/artifacts/sboms/npm/%40qits%2Fui/-%2F1.0.0"));
    // An escape outside the three admitted ones is no character of a name.
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a%20b"));
    assertFalse(matches(SbomPaths.PACKAGE, "/artifacts/sboms/npm/a%40b"), "@ only ever leads");
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // vertx-web compares Matcher.groupCount() against the named groups it scraped from the pattern
    // and falls back to positional param0..paramN when they disagree — so ONE bare (...) anywhere in
    // these patterns breaks pathParam("name") on that route, at runtime, silently. This grammar is
    // at risk of it for DocsPaths' reason: NAME nests a repeated group inside a named one.
    assertGroupsAllNamed(SbomPaths.PACKAGE, 2);
    assertGroupsAllNamed(SbomPaths.DOCUMENT, 3);
  }

  private static void assertGroupsAllNamed(String regex, int expectedNamed) {
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(expectedNamed, named, "named group count changed in: " + regex);
    assertEquals(
        expectedNamed,
        Pattern.compile(regex).matcher("").groupCount(),
        "a bare capturing group crept into: " + regex);
  }

  private static boolean matches(String regex, String path) {
    return Pattern.compile(regex).matcher(path).matches();
  }

  private static String group(String regex, String path, String group) {
    Matcher matcher = Pattern.compile(regex).matcher(path);
    assertTrue(matcher.matches(), regex + " did not match " + path);
    return matcher.group(group);
  }
}
