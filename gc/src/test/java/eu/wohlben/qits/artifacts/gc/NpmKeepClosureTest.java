package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.NpmSemver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The walk itself, over an in-memory registry: which edges {@link NpmKeepClosure} follows, how a
 * range picks its target, and every way it refuses to answer with a partial closure.
 *
 * <p>Plain JUnit, because the closure reads a store only through {@link NpmKeepClosure.Documents}
 * and a map of bytes is that store. {@code NpmPackagesGcAdapterTest} proves the rules through the
 * real rows, blobs and engine.
 */
class NpmKeepClosureTest {

  private final Map<String, String> manifests = new HashMap<>();
  private final Map<String, String> sboms = new HashMap<>();
  private final Map<String, String> tags = new HashMap<>();
  private final Set<String> unreadable = new HashSet<>();
  private final Set<String> hosted = new HashSet<>();

  @Test
  void theWalkRunsToAFixpointAcrossTwoHopsAndNamesEachReferrer() {
    hosted("@qits/app@1.0.0", "@qits/lib@1.0.0", "@qits/deep@1.0.0", "@qits/deep@0.9.0");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/lib", "1.0.0");
    manifest("@qits/lib@1.0.0", "dependencies", "@qits/deep", "1.0.0");
    manifest("@qits/deep@1.0.0");
    manifest("@qits/deep@0.9.0");

    Map<String, String> reached = closed(Set.of("@qits/app@1.0.0"));

    assertEquals(
        Map.of(
            "@qits/lib@1.0.0", dependencyOf("@qits/app@1.0.0"),
            "@qits/deep@1.0.0", dependencyOf("@qits/lib@1.0.0")),
        reached);
  }

  @Test
  void aCycleTerminatesAndBothSidesAreReached() {
    hosted("@qits/a@1.0.0", "@qits/b@1.0.0");
    manifest("@qits/a@1.0.0", "dependencies", "@qits/b", "1.0.0");
    manifest("@qits/b@1.0.0", "dependencies", "@qits/a", "^1.0.0");

    Map<String, String> reached = closed(Set.of("@qits/a@1.0.0"));

    assertEquals(dependencyOf("@qits/a@1.0.0"), reached.get("@qits/b@1.0.0"));
    assertEquals(dependencyOf("@qits/b@1.0.0"), reached.get("@qits/a@1.0.0"));
  }

  @Test
  void aCaretRangeResolvesToTheHighestHostedMatchAndNothingElse() {
    hosted(
        "@qits/app@1.0.0",
        "@qits/ui@2026.801.63140",
        "@qits/ui@2026.904.202810",
        "@qits/ui@2027.101.1",
        "@qits/ui@2026.905.1-main.gabc1234");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/ui", "^2026.801.63140");
    for (String v : new String[] {"2026.801.63140", "2026.904.202810", "2027.101.1"}) {
      manifest("@qits/ui@" + v);
    }
    manifest("@qits/ui@2026.905.1-main.gabc1234");

    Map<String, String> reached = closed(Set.of("@qits/app@1.0.0"));

    assertEquals(Set.of("@qits/ui@2026.904.202810"), reached.keySet(), "not the next major, and"
        + " not a prerelease the range does not name");
  }

  @Test
  void aTildeRangeStaysInsideItsMinorAndAnExactVersionPicksItself() {
    hosted(
        "@qits/app@1.0.0",
        "@qits/t@1.2.3",
        "@qits/t@1.2.9",
        "@qits/t@1.3.0",
        "@qits/e@1.0.0",
        "@qits/e@1.5.0");
    manifest(
        "@qits/app@1.0.0",
        "dependencies",
        "@qits/t",
        "~1.2.3",
        "@qits/e",
        "1.0.0");
    for (String coordinate : hosted) {
      if (!coordinate.equals("@qits/app@1.0.0")) {
        manifest(coordinate);
      }
    }

    Map<String, String> reached = closed(Set.of("@qits/app@1.0.0"));

    assertEquals(Set.of("@qits/t@1.2.9", "@qits/e@1.0.0"), reached.keySet());
  }

  @Test
  void aPeerAndAnOptionalDependencyAreFollowedAndADevDependencyIsNot() {
    hosted("@qits/app@1.0.0", "@qits/peer@1.0.0", "@qits/opt@1.0.0", "@qits/dev@1.0.0");
    manifests.put(
        "@qits/app@1.0.0",
        "{\"peerDependencies\":{\"@qits/peer\":\">=1.0.0 <2\"},"
            + "\"optionalDependencies\":{\"@qits/opt\":\"1.x\"},"
            + "\"devDependencies\":{\"@qits/dev\":\"1.0.0\"}}");
    manifest("@qits/peer@1.0.0");
    manifest("@qits/opt@1.0.0");
    manifest("@qits/dev@1.0.0");

    Map<String, String> reached = closed(Set.of("@qits/app@1.0.0"));

    assertEquals(
        Map.of(
            "@qits/peer@1.0.0",
                NpmKeepClosure.PEER_DEPENDENCY_OF + "@qits/app@1.0.0" + NpmKeepClosure.WHICH_IS_KEPT,
            "@qits/opt@1.0.0",
                NpmKeepClosure.OPTIONAL_DEPENDENCY_OF
                    + "@qits/app@1.0.0"
                    + NpmKeepClosure.WHICH_IS_KEPT),
        reached);
  }

  @Test
  void anSbomPurlNamingAnExactHostedVersionIsFollowedScopeEncodedOrNot() {
    // The range in the manifest picks the newest; the SBOM names what the build actually locked —
    // an older version — and that one is kept too.
    hosted("@qits/app@1.0.0", "@qits/ui@1.0.0", "@qits/ui@1.1.0", "plain@2.0.0");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/ui", "^1.0.0");
    sboms.put(
        "@qits/app@1.0.0",
        "{\"bomFormat\":\"CycloneDX\",\"components\":["
            + "{\"purl\":\"pkg:npm/%40qits/ui@1.0.0\"},"
            + "{\"purl\":\"pkg:maven/eu.wohlben.qits/x@1.0.0\"},"
            + "{\"purl\":\"pkg:npm/lodash@4.17.21\"},"
            + "{\"purl\":\"pkg:npm/zone.js@1.0.0\",\"components\":"
            + "[{\"purl\":\"pkg:npm/plain@2.0.0?vcs_url=x\"}]}]}");
    manifest("@qits/ui@1.0.0");
    manifest("@qits/ui@1.1.0");
    manifest("plain@2.0.0");

    Map<String, String> reached = closed(Set.of("@qits/app@1.0.0"));

    String bySbom = NpmKeepClosure.NAMED_BY_SBOM + "@qits/app@1.0.0" + NpmKeepClosure.WHICH_IS_KEPT;
    assertEquals(dependencyOf("@qits/app@1.0.0"), reached.get("@qits/ui@1.1.0"));
    assertEquals(bySbom, reached.get("@qits/ui@1.0.0"));
    assertEquals(bySbom, reached.get("plain@2.0.0"), "nested components count");
    assertEquals(3, reached.size());
  }

  @Test
  void aDependencyOnAPackageThisStoreDoesNotHostIsIgnoredWhateverItsSpec() {
    hosted("@qits/app@1.0.0");
    manifest(
        "@qits/app@1.0.0",
        "dependencies",
        "rxjs",
        "~7.8.0",
        "local",
        "file:../local",
        "git",
        "github:owner/repo");

    assertEquals(Map.of(), closed(Set.of("@qits/app@1.0.0")));
  }

  @Test
  void aRangeNoHostedVersionSatisfiesIsNoEdgeRatherThanAGap() {
    hosted("@qits/app@1.0.0", "@qits/ui@2.0.0");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/ui", "^1.0.0");
    manifest("@qits/ui@2.0.0");

    assertEquals(Map.of(), closed(Set.of("@qits/app@1.0.0")));
  }

  @Test
  void aDistTagSpecResolvesThroughTheTagAndAnAliasThroughItsTarget() {
    hosted("@qits/app@1.0.0", "@qits/ui@1.0.0", "@qits/ui@1.1.0", "@qits/real@3.0.0");
    manifest(
        "@qits/app@1.0.0",
        "dependencies",
        "@qits/ui",
        "latest",
        "alias",
        "npm:@qits/real@^3.0.0");
    tags.put("@qits/ui latest", "1.0.0");
    manifest("@qits/ui@1.0.0");
    manifest("@qits/ui@1.1.0");
    manifest("@qits/real@3.0.0");

    assertEquals(
        Set.of("@qits/ui@1.0.0", "@qits/real@3.0.0"), closed(Set.of("@qits/app@1.0.0")).keySet());
  }

  @Test
  void anUnreadableManifestIsIncompleteNamingTheVersion() {
    hosted("@qits/app@1.0.0", "@qits/lib@1.0.0");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/lib", "1.0.0");
    unreadable.add("@qits/lib@1.0.0");

    NpmKeepClosure.Incomplete gap = incomplete(Set.of("@qits/app@1.0.0"));

    assertEquals("@qits/lib@1.0.0", gap.coordinate());
    assertTrue(gap.reason().contains("could not be read"), gap.reason());
  }

  @Test
  void aManifestThatIsNotJsonAndAMissingManifestAreBothIncomplete() {
    hosted("@qits/a@1.0.0", "@qits/b@1.0.0");
    manifests.put("@qits/a@1.0.0", "{not json");

    assertEquals("@qits/a@1.0.0", incomplete(Set.of("@qits/a@1.0.0")).coordinate());
    assertEquals("@qits/b@1.0.0", incomplete(Set.of("@qits/b@1.0.0")).coordinate());
  }

  @Test
  void anSbomThatDoesNotParseIsIncomplete() {
    hosted("@qits/a@1.0.0");
    manifest("@qits/a@1.0.0");
    sboms.put("@qits/a@1.0.0", "[");

    NpmKeepClosure.Incomplete gap = incomplete(Set.of("@qits/a@1.0.0"));

    assertTrue(gap.reason().contains("SBOM"), gap.reason());
  }

  @Test
  void anUnparseableSpecOnAHostedPackageIsIncomplete() {
    hosted("@qits/app@1.0.0", "@qits/ui@1.0.0");
    manifest("@qits/app@1.0.0", "dependencies", "@qits/ui", "file:../ui");
    manifest("@qits/ui@1.0.0");

    NpmKeepClosure.Incomplete gap = incomplete(Set.of("@qits/app@1.0.0"));

    assertEquals("@qits/app@1.0.0", gap.coordinate());
    assertTrue(gap.reason().contains("@qits/ui@file:../ui"), gap.reason());
  }

  @Test
  void aSeedThisStoreDoesNotHostIsIgnored() {
    assertEquals(Map.of(), closed(Set.of("@qits/gone@1.0.0")));
  }

  @Test
  void rangesDesugarTheWayNodeSemverDoes() {
    assertTrue(matches("^1.2.3", "1.9.9"));
    assertFalse(matches("^1.2.3", "2.0.0"));
    assertFalse(matches("^1.2.3", "1.2.2"));
    assertTrue(matches("^0.2.3", "0.2.9"));
    assertFalse(matches("^0.2.3", "0.3.0"));
    assertFalse(matches("^0.0.3", "0.0.4"));
    assertTrue(matches("~1.2", "1.2.7"));
    assertFalse(matches("~1.2", "1.3.0"));
    assertTrue(matches("~1", "1.9.0"));
    assertTrue(matches("1.x", "1.4.0"));
    assertFalse(matches("1.x", "2.0.0"));
    assertTrue(matches("*", "2026.1003.1"));
    assertTrue(matches("", "0.0.1"));
    assertTrue(matches(">= 1.0.0 < 2", "1.5.0"));
    assertFalse(matches(">=1.0.0 <2", "2.0.0"));
    assertTrue(matches("<=1.2", "1.2.9"));
    assertFalse(matches(">1.2", "1.2.9"));
    assertTrue(matches(">1.2", "1.3.0"));
    assertTrue(matches("1.2.3 - 2.3", "2.3.9"));
    assertFalse(matches("1.2.3 - 2.3.4", "2.3.5"));
    assertTrue(matches("^1.0.0 || ^3.0.0", "3.1.0"));
    assertFalse(matches("^1.0.0 || ^3.0.0", "2.1.0"));
    assertFalse(matches("^1.0.0", "1.2.0-rc.1"), "a prerelease the range does not name");
    assertTrue(matches("^1.2.0-rc.1", "1.2.0-rc.2"), "one of the same core version it does");
    assertTrue(matches("^2026.801.63140", "2026.904.202810"), "calver majors");
    assertThrows(IllegalArgumentException.class, () -> NpmKeepClosure.Range.parse("latest"));
    assertThrows(IllegalArgumentException.class, () -> NpmKeepClosure.Range.parse("01.2.3"));
    assertThrows(IllegalArgumentException.class, () -> NpmKeepClosure.Range.parse("file:x"));
  }

  // --- fixture ---------------------------------------------------------------------------------

  private static boolean matches(String range, String version) {
    return NpmKeepClosure.Range.parse(range).test(NpmSemver.parse(version).orElseThrow());
  }

  private static String dependencyOf(String coordinate) {
    return NpmKeepClosure.DEPENDENCY_OF + coordinate + NpmKeepClosure.WHICH_IS_KEPT;
  }

  private void hosted(String... coordinates) {
    hosted.addAll(Set.of(coordinates));
  }

  /** A manifest with one field of {@code name, spec} pairs, or an empty one. */
  private void manifest(String coordinate, String... fieldAndPairs) {
    if (fieldAndPairs.length == 0) {
      manifests.put(coordinate, "{\"name\":\"" + NpmKeepClosure.packageOf(coordinate) + "\"}");
      return;
    }
    StringBuilder entries = new StringBuilder();
    for (int i = 1; i < fieldAndPairs.length; i += 2) {
      entries
          .append(entries.length() == 0 ? "" : ",")
          .append('"')
          .append(fieldAndPairs[i])
          .append("\":\"")
          .append(fieldAndPairs[i + 1])
          .append('"');
    }
    manifests.put(coordinate, "{\"" + fieldAndPairs[0] + "\":{" + entries + "}}");
  }

  private NpmKeepClosure.Documents documents() {
    return new NpmKeepClosure.Documents() {
      @Override
      public byte[] manifest(String coordinate) throws IOException {
        if (unreadable.contains(coordinate)) {
          throw new IOException("blob gone");
        }
        String json = manifests.get(coordinate);
        return json == null ? null : json.getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public byte[] sbom(String coordinate) {
        String json = sboms.get(coordinate);
        return json == null ? null : json.getBytes(StandardCharsets.UTF_8);
      }

      @Override
      public String distTag(String packageName, String tag) {
        return tags.get(packageName + " " + tag);
      }
    };
  }

  private Map<String, String> closed(Set<String> seeds) {
    NpmKeepClosure.Result result = NpmKeepClosure.from(seeds, hosted, documents());
    return assertInstanceOf(NpmKeepClosure.Closed.class, result, result.toString()).reached();
  }

  private NpmKeepClosure.Incomplete incomplete(Set<String> seeds) {
    NpmKeepClosure.Result result = NpmKeepClosure.from(seeds, hosted, documents());
    return assertInstanceOf(NpmKeepClosure.Incomplete.class, result, result.toString());
  }
}
