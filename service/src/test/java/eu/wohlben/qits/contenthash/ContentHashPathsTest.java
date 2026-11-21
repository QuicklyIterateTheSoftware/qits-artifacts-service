package eu.wohlben.qits.contenthash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The route grammar, as plain JUnit — {@code SbomPathsTest}'s shape for {@code SbomPaths}' grammar,
 * which this one repeats segment for segment. {@code ContentHashRoutesTest} then proves the router
 * behaves this way over the wire.
 */
class ContentHashPathsTest {

  @Test
  void bothSpellingsOfAMavenCoordinateMatchAndCaptureTheirRawSpelling() {
    String literal = "/artifacts/content-hashes/maven/eu.wohlben.qits:qits-foo/-/newest";
    assertEquals("maven", group(literal, "ecosystem"));
    assertEquals("eu.wohlben.qits:qits-foo", group(literal, "name"));
    assertEquals("newest", group(literal, "version"));

    // normalizedPath() keeps %3A as received; the decoding is pathParam's.
    String encoded = "/artifacts/content-hashes/maven/eu.wohlben.qits%3Aqits-foo/-/2026.1001.1";
    assertEquals("eu.wohlben.qits%3Aqits-foo", group(encoded, "name"));
    assertEquals("2026.1001.1", group(encoded, "version"));
    assertEquals(
        "eu.wohlben.qits%3aqits-foo",
        group("/artifacts/content-hashes/maven/eu.wohlben.qits%3aqits-foo/-/newest", "name"),
        "lowercase hex");
  }

  @Test
  void bothSpellingsOfAScopedNpmNameMatchAndMayBeMixed() {
    assertEquals("@qits/foo", group("/artifacts/content-hashes/npm/@qits/foo/-/newest", "name"));
    assertEquals(
        "%40qits%2Ffoo", group("/artifacts/content-hashes/npm/%40qits%2Ffoo/-/newest", "name"));
    assertEquals(
        "%40qits/foo", group("/artifacts/content-hashes/npm/%40qits/foo/-/newest", "name"));
    assertEquals(
        "@qits%2ffoo", group("/artifacts/content-hashes/npm/@qits%2ffoo/-/newest", "name"));
    assertFalse(
        matches("/artifacts/content-hashes/npm/a%2Fb%2Fc%2Fd%2Fe/-/newest"),
        "an encoded separator spends the four-segment cap like a literal one");
  }

  @Test
  void theEncodedSpellingNeverMakesADotSegmentOrABareSeparatorAName() {
    // %2F is no separator to normalizedPath(), so `..` between two of them reaches the regex intact,
    // and %2E%2E arrives as `..`. Neither may begin a segment.
    for (String name :
        new String[] {
          "a%2F..%2Fb", "a%2f..%2fb", "%2F..%2Fb", "a/..%2Fb", "a%2F..", "..", "%2E%2E", "a%2F%2E%2E",
          "%2F", "a%2F", "%40", "%40%2Fb", "-", "a%2F-", "%3Afoo", ":foo"
        }) {
      assertFalse(matches("/artifacts/content-hashes/npm/" + name + "/-/newest"), name);
    }
    // The /-/ separator is literal only.
    assertFalse(matches("/artifacts/content-hashes/npm/%40qits%2Ffoo%2F-%2Fnewest"));
    assertFalse(matches("/artifacts/content-hashes/npm/%40qits%2Ffoo/-%2Fnewest"));
    // And the ecosystem stays strict in any spelling.
    assertFalse(matches("/artifacts/content-hashes/docker/qits%2Fqits-artifacts/-/newest"));
  }

  @Test
  void everyGroupIsNamedOrNonCapturing() {
    // SbomPathsTest's reason: one bare (...) silently turns pathParam("name") into param0..paramN.
    String regex = ContentHashPaths.VERSIONED;
    long named = Pattern.compile("\\(\\?<[a-zA-Z][a-zA-Z0-9]*>").matcher(regex).results().count();
    assertEquals(3, named, "named group count changed in: " + regex);
    assertEquals(
        3, Pattern.compile(regex).matcher("").groupCount(), "a bare capturing group crept in");
  }

  private static boolean matches(String path) {
    return Pattern.compile(ContentHashPaths.VERSIONED).matcher(path).matches();
  }

  private static String group(String path, String group) {
    Matcher matcher = Pattern.compile(ContentHashPaths.VERSIONED).matcher(path);
    assertTrue(matcher.matches(), ContentHashPaths.VERSIONED + " did not match " + path);
    return matcher.group(group);
  }
}
