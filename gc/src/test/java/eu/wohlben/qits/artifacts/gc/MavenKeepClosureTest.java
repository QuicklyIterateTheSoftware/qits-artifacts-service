package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The walk itself, over an in-memory store: which edges {@link MavenKeepClosure} follows, which it
 * does not, and every way it refuses to answer with a partial closure.
 *
 * <p>Plain JUnit, because nothing here needs a database: the closure reads a store only through
 * {@link MavenKeepClosure.Documents}, and a map of bytes is that store. {@code
 * MavenPackagesGcAdapterTest} proves the same rules through the real rows, blobs and engine.
 */
class MavenKeepClosureTest {

  private static final String G = "eu.wohlben.qits";

  private final Map<String, byte[]> poms = new HashMap<>();
  private final Map<String, byte[]> sboms = new HashMap<>();
  private final Set<String> unreadable = new HashSet<>();
  private final Set<String> hosted = new HashSet<>();

  @Test
  void aVersionNamedInASeedsSbomIsReachedAndAPomDependencyBesideItIsNot() {
    // With a document, the document IS the dependency set — transitive, and exact. The pom's own
    // <dependencies> are the fallback for a coordinate that has none, so following them here too
    // would keep what the build that published this coordinate did not resolve.
    hosted("app:1.0.0", "lib:1.0.0", "lib:0.9.0");
    pom("app:1.0.0", dependency("lib", "0.9.0", null));
    sbom("app:1.0.0", purl("lib", "1.0.0"), purl("external", "4.2"));
    pom("lib:1.0.0", "");

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    assertEquals(Map.of(c("lib:1.0.0"), MavenKeepClosure.NAMED_BY_SBOM + c("app:1.0.0")), reached);
  }

  @Test
  void theParentOfACoordinateWithAnSbomIsStillFollowed() {
    // qits-githost-events:2026.910.103045 names qits-githost:2026.910.103045 as its parent, and no
    // SBOM lists a parent: it is an input of the pom, not a dependency of the artifact.
    hosted("qits-githost-events:2026.910.103045", "qits-githost:2026.910.103045");
    pom("qits-githost-events:2026.910.103045", parent("qits-githost", "2026.910.103045"));
    sbom("qits-githost-events:2026.910.103045");
    pom("qits-githost:2026.910.103045", "");

    Map<String, String> reached = closed(Set.of(c("qits-githost-events:2026.910.103045")));

    assertEquals(
        MavenKeepClosure.PARENT_OF + c("qits-githost-events:2026.910.103045"),
        reached.get(c("qits-githost:2026.910.103045")));
  }

  @Test
  void anImportScopedBomIsFollowedAndAPlainManagedVersionIsNot() {
    hosted("app:1.0.0", "bom:1.0.0", "managed:1.0.0");
    pom(
        "app:1.0.0",
        "<dependencyManagement><dependencies>"
            + dependency("bom", "1.0.0", "import").replace("</dependency>", "<type>pom</type></dependency>")
            + dependency("managed", "1.0.0", null)
            + "</dependencies></dependencyManagement>");
    sbom("app:1.0.0");
    pom("bom:1.0.0", "");

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    assertEquals(Map.of(c("bom:1.0.0"), MavenKeepClosure.IMPORTED_BY + c("app:1.0.0")), reached);
  }

  @Test
  void aCoordinateWithNoSbomFollowsItsNonTestPomDependencies() {
    hosted(
        "app:1.0.0", "compiled:1.0.0", "runtime:1.0.0", "provided:1.0.0", "tested:1.0.0",
        "managed:2.0.0");
    pom(
        "app:1.0.0",
        "<dependencyManagement><dependencies>"
            + dependency("managed", "2.0.0", null)
            + "</dependencies></dependencyManagement><dependencies>"
            + dependency("compiled", "1.0.0", null)
            + dependency("runtime", "1.0.0", "runtime")
            + dependency("provided", "1.0.0", "provided")
            + dependency("tested", "1.0.0", "test")
            + "<dependency><groupId>" + G + "</groupId><artifactId>managed</artifactId></dependency>"
            + "</dependencies>");
    for (String leaf : List.of("compiled", "runtime", "provided", "managed")) {
      pom(leaf + (leaf.equals("managed") ? ":2.0.0" : ":1.0.0"), "");
    }

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    String rule = MavenKeepClosure.DEPENDENCY_OF + c("app:1.0.0") + MavenKeepClosure.WHICH_HAS_NO_SBOM;
    assertEquals(rule, reached.get(c("compiled:1.0.0")));
    assertEquals(rule, reached.get(c("runtime:1.0.0")));
    assertEquals(rule, reached.get(c("provided:1.0.0")));
    assertEquals(rule, reached.get(c("managed:2.0.0")), "a version the same pom manages");
    assertFalse(reached.containsKey(c("tested:1.0.0")), "a test dependency is not a build input");
  }

  @Test
  void theWalkRunsToAFixpointAcrossTwoHopsAndThroughACycle() {
    // app's SBOM names lib; lib has no SBOM, so its pom names deep; deep's parent is app again.
    hosted("app:1.0.0", "lib:1.0.0", "deep:1.0.0");
    pom("app:1.0.0", "");
    sbom("app:1.0.0", purl("lib", "1.0.0"));
    pom("lib:1.0.0", "<dependencies>" + dependency("deep", "1.0.0", null) + "</dependencies>");
    pom("deep:1.0.0", parent("app", "1.0.0"));

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    assertEquals(MavenKeepClosure.NAMED_BY_SBOM + c("app:1.0.0"), reached.get(c("lib:1.0.0")));
    assertEquals(
        MavenKeepClosure.DEPENDENCY_OF + c("lib:1.0.0") + MavenKeepClosure.WHICH_HAS_NO_SBOM,
        reached.get(c("deep:1.0.0")));
    assertEquals(
        MavenKeepClosure.PARENT_OF + c("deep:1.0.0"),
        reached.get(c("app:1.0.0")),
        "a seed reached by an edge reports the edge, the stronger reason");
  }

  @Test
  void projectVersionAndTheOwnPropertiesResolveAndAnExternalPropertyIsNoGap() {
    hosted("app:1.0.0", "sibling:1.0.0", "lib:3.0.0");
    pom(
        "app:1.0.0",
        "<properties><lib.version>3.0.0</lib.version></properties><dependencies>"
            + dependency("sibling", "${project.version}", null)
            + dependency("lib", "${lib.version}", null)
            + "<dependency><groupId>org.external</groupId><artifactId>x</artifactId>"
            + "<version>${inherited.version}</version></dependency>"
            + "</dependencies>");
    pom("sibling:1.0.0", "");
    pom("lib:3.0.0", "");

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    assertEquals(Set.of(c("sibling:1.0.0"), c("lib:3.0.0")), reached.keySet());
  }

  @Test
  void externalCoordinatesFallOutAndAnAbsentVersionOfAHostedArtifactIsSimplyNotKept() {
    hosted("app:1.0.0", "lib:2.0.0");
    pom("app:1.0.0", "");
    sbom("app:1.0.0", purl("lib", "1.0.0"), "pkg:maven/org.external/thing@1.0");

    assertEquals(Map.of(), closed(Set.of(c("app:1.0.0"))));
  }

  @Test
  void aVersionlessDependencyTakesTheVersionItsStoredParentManagesThroughAParentProperty() {
    // The live shape, from before poms were flattened: qits-githost-events declares
    // qits-eventstream with no version, and its parent qits-githost manages it as
    // ${qits.eventstream.version}, a property the parent defines. Maven reads the child's effective
    // model; so does this.
    hosted(
        "qits-githost-events:2026.910.103045",
        "qits-githost:2026.910.103045",
        "qits-eventstream:2026.910.64632",
        "qits-eventstream:2026.920.1");
    pom(
        "qits-githost-events:2026.910.103045",
        parent("qits-githost", "2026.910.103045")
            + "<dependencies><dependency><groupId>${project.groupId}</groupId>"
            + "<artifactId>qits-eventstream</artifactId></dependency></dependencies>");
    pom(
        "qits-githost:2026.910.103045",
        "<properties><qits.eventstream.version>2026.910.64632</qits.eventstream.version>"
            + "</properties><dependencyManagement><dependencies>"
            + dependency("qits-eventstream", "${qits.eventstream.version}", null)
            + dependency("qits-githost-events", "${project.version}", null)
            + "</dependencies></dependencyManagement>");
    pom("qits-eventstream:2026.910.64632", "");

    Map<String, String> reached = closed(Set.of(c("qits-githost-events:2026.910.103045")));

    assertEquals(
        MavenKeepClosure.DEPENDENCY_OF
            + c("qits-githost-events:2026.910.103045")
            + MavenKeepClosure.WHICH_HAS_NO_SBOM,
        reached.get(c("qits-eventstream:2026.910.64632")));
    assertFalse(reached.containsKey(c("qits-eventstream:2026.920.1")));
  }

  @Test
  void aChildPropertyOverridesTheParentsAndAnInheritedProjectVersionIsTheChilds() {
    hosted("child:2.0.0", "parent:1.0.0", "lib:9.0.0", "lib:8.0.0", "sibling:2.0.0", "sibling:1.0.0");
    pom(
        "child:2.0.0",
        parent("parent", "1.0.0")
            + "<properties><lib.version>9.0.0</lib.version></properties><dependencies>"
            + "<dependency><groupId>" + G + "</groupId><artifactId>lib</artifactId></dependency>"
            + "<dependency><groupId>" + G + "</groupId><artifactId>sibling</artifactId></dependency>"
            + "</dependencies>");
    pom(
        "parent:1.0.0",
        "<properties><lib.version>8.0.0</lib.version></properties><dependencyManagement>"
            + "<dependencies>"
            + dependency("lib", "${lib.version}", null)
            + dependency("sibling", "${project.version}", null)
            + "</dependencies></dependencyManagement>");
    pom("lib:9.0.0", "");
    pom("sibling:2.0.0", "");

    Map<String, String> reached = closed(Set.of(c("child:2.0.0")));

    assertTrue(reached.containsKey(c("lib:9.0.0")), "the child's property wins: " + reached);
    assertTrue(reached.containsKey(c("sibling:2.0.0")), "the child's project.version: " + reached);
    assertFalse(reached.containsKey(c("lib:8.0.0")));
    assertFalse(reached.containsKey(c("sibling:1.0.0")));
  }

  @Test
  void aVersionlessDependencyCanBeManagedByAStoredImportedBom() {
    hosted("app:1.0.0", "bom:1.0.0", "lib:3.0.0");
    pom(
        "app:1.0.0",
        "<dependencyManagement><dependencies>"
            + dependency("bom", "1.0.0", "import")
            + "</dependencies></dependencyManagement><dependencies>"
            + "<dependency><groupId>" + G + "</groupId><artifactId>lib</artifactId></dependency>"
            + "</dependencies>");
    pom(
        "bom:1.0.0",
        "<dependencyManagement><dependencies>"
            + dependency("lib", "3.0.0", null)
            + "</dependencies></dependencyManagement>");
    pom("lib:3.0.0", "");

    Map<String, String> reached = closed(Set.of(c("app:1.0.0")));

    assertEquals(MavenKeepClosure.IMPORTED_BY + c("app:1.0.0"), reached.get(c("bom:1.0.0")));
    assertEquals(
        MavenKeepClosure.DEPENDENCY_OF + c("app:1.0.0") + MavenKeepClosure.WHICH_HAS_NO_SBOM,
        reached.get(c("lib:3.0.0")));
  }

  @Test
  void aVersionlessHostedDependencyWhoseParentIsNotStoredIsIncomplete() {
    // The parent artifact is hosted but that version of it is gone, so nothing here can say what
    // it managed: unknown, never guessed.
    hosted("app:1.0.0", "parent:2.0.0", "lib:1.0.0");
    pom(
        "app:1.0.0",
        parent("parent", "1.0.0")
            + "<dependencies><dependency><groupId>" + G + "</groupId><artifactId>lib</artifactId>"
            + "</dependency></dependencies>");

    MavenKeepClosure.Incomplete incomplete = incomplete(Set.of(c("app:1.0.0")));

    assertEquals(c("app:1.0.0"), incomplete.coordinate());
    assertTrue(incomplete.reason().contains("(no version)"), incomplete.reason());
  }

  @Test
  void anUnparseableParentIsIncompleteAtTheParent() {
    hosted("app:1.0.0", "parent:1.0.0");
    pom("app:1.0.0", parent("parent", "1.0.0"));
    poms.put(c("parent:1.0.0"), "x".getBytes(StandardCharsets.UTF_8));

    MavenKeepClosure.Incomplete incomplete = incomplete(Set.of(c("app:1.0.0")));

    assertEquals(c("parent:1.0.0"), incomplete.coordinate());
    assertTrue(incomplete.reason().contains("does not parse"), incomplete.reason());
  }

  // --- fail closed -------------------------------------------------------------------------------

  @Test
  void anUnreadablePomStopsTheWalkAtTheCoordinateItBelongsTo() {
    hosted("app:1.0.0", "lib:1.0.0");
    pom("app:1.0.0", "");
    sbom("app:1.0.0", purl("lib", "1.0.0"));
    unreadable.add(c("lib:1.0.0"));

    MavenKeepClosure.Incomplete incomplete = incomplete(Set.of(c("app:1.0.0")));

    assertEquals(c("lib:1.0.0"), incomplete.coordinate());
    assertTrue(incomplete.reason().contains("could not be read"), incomplete.reason());
  }

  @Test
  void aPomThatIsNotXmlAndACoordinateWithNoPomAreBothIncomplete() {
    hosted("app:1.0.0");
    poms.put(c("app:1.0.0"), "not a pom".getBytes(StandardCharsets.UTF_8));
    assertTrue(incomplete(Set.of(c("app:1.0.0"))).reason().contains("does not parse"));

    poms.clear();
    assertTrue(incomplete(Set.of(c("app:1.0.0"))).reason().contains("no pom"));
  }

  @Test
  void anUnresolvableParentVersionOfAHostedArtifactIsIncompleteAndOfAnExternalOneIsNot() {
    hosted("app:1.0.0", "parent:1.0.0");
    pom("app:1.0.0", parent("parent", "${revision}"));
    MavenKeepClosure.Incomplete incomplete = incomplete(Set.of(c("app:1.0.0")));
    assertEquals(c("app:1.0.0"), incomplete.coordinate());
    assertTrue(incomplete.reason().contains("${revision}"), incomplete.reason());

    pom(
        "app:1.0.0",
        "<parent><groupId>org.external</groupId><artifactId>p</artifactId>"
            + "<version>${revision}</version></parent>");
    assertEquals(Map.of(), closed(Set.of(c("app:1.0.0"))));
  }

  @Test
  void anUnresolvableDependencyOfANoSbomCoordinateIsIncomplete() {
    hosted("app:1.0.0", "lib:1.0.0");
    pom("app:1.0.0", "<dependencies>" + dependency("lib", "${lib.version}", null) + "</dependencies>");
    assertTrue(incomplete(Set.of(c("app:1.0.0"))).reason().contains("dependency"));

    pom("app:1.0.0", "<dependencies>" + dependency("lib", "[1.0,2.0)", null) + "</dependencies>");
    assertTrue(incomplete(Set.of(c("app:1.0.0"))).reason().contains("cannot resolve"));

    // The same reference beside an SBOM is never read, so it cannot be a gap.
    sbom("app:1.0.0");
    assertEquals(Map.of(), closed(Set.of(c("app:1.0.0"))));
  }

  @Test
  void anSbomThatDoesNotParseIsIncomplete() {
    hosted("app:1.0.0");
    pom("app:1.0.0", "");
    sboms.put(c("app:1.0.0"), "{".getBytes(StandardCharsets.UTF_8));

    assertTrue(incomplete(Set.of(c("app:1.0.0"))).reason().contains("SBOM"));
  }

  // --- the SBOM reading --------------------------------------------------------------------------

  @Test
  void aPurlIsReadWithQualifiersAndEncodingAndOnlyWhenItIsMaven() throws Exception {
    assertEquals(
        "eu.wohlben.qits:qits-blobstore:2026.901.1",
        MavenKeepClosure.fromPurl("pkg:maven/eu.wohlben.qits/qits-blobstore@2026.901.1?type=jar"));
    assertEquals("g:a:1.0+build", MavenKeepClosure.fromPurl("pkg:maven/g/a@1.0%2Bbuild#sub"));
    assertNull(MavenKeepClosure.fromPurl("pkg:npm/%40qits/angular@1.0.0"));

    Set<String> named =
        MavenKeepClosure.sbomComponents(
            ("{\"components\":["
                    + "{\"purl\":\"pkg:npm/left-pad@1.0.0\",\"group\":\"g\",\"name\":\"left-pad\","
                    + "\"version\":\"1.0.0\"},"
                    + "{\"group\":\"g\",\"name\":\"no-purl\",\"version\":\"2.0\",\"components\":["
                    + "{\"purl\":\"pkg:maven/g/nested@3.0\"}]}]}")
                .getBytes(StandardCharsets.UTF_8));
    assertEquals(Set.of("g:no-purl:2.0", "g:nested:3.0"), named);
  }

  // --- fixture -----------------------------------------------------------------------------------

  private Map<String, String> closed(Set<String> seeds) {
    return assertInstanceOf(MavenKeepClosure.Closed.class, walk(seeds)).reached();
  }

  private MavenKeepClosure.Incomplete incomplete(Set<String> seeds) {
    return assertInstanceOf(MavenKeepClosure.Incomplete.class, walk(seeds));
  }

  private MavenKeepClosure.Result walk(Set<String> seeds) {
    return MavenKeepClosure.from(
        seeds,
        hosted,
        new MavenKeepClosure.Documents() {
          @Override
          public byte[] pom(String coordinate) throws IOException {
            if (unreadable.contains(coordinate)) {
              throw new IOException("blob missing");
            }
            return poms.get(coordinate);
          }

          @Override
          public byte[] sbom(String coordinate) {
            return sboms.get(coordinate);
          }
        });
  }

  /** {@code artifact:version} in the fixture's one group. */
  private static String c(String artifactAndVersion) {
    return G + ":" + artifactAndVersion;
  }

  private void hosted(String... coordinates) {
    for (String coordinate : coordinates) {
      hosted.add(c(coordinate));
    }
  }

  private void pom(String coordinate, String body) {
    String[] parts = coordinate.split(":");
    poms.put(
        c(coordinate),
        ("<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<groupId>" + G + "</groupId><artifactId>" + parts[0] + "</artifactId>"
                + "<version>" + parts[1] + "</version>" + body + "</project>")
            .getBytes(StandardCharsets.UTF_8));
  }

  private void sbom(String coordinate, String... purls) {
    StringBuilder components = new StringBuilder();
    for (String purl : purls) {
      components.append(components.length() == 0 ? "" : ",").append("{\"purl\":\"").append(purl).append("\"}");
    }
    sboms.put(
        c(coordinate),
        ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":[" + components + "]}")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String purl(String artifact, String version) {
    return "pkg:maven/" + G + "/" + artifact + "@" + version + "?type=jar";
  }

  private static String parent(String artifact, String version) {
    return "<parent><groupId>" + G + "</groupId><artifactId>" + artifact + "</artifactId><version>"
        + version + "</version></parent>";
  }

  private static String dependency(String artifact, String version, String scope) {
    return "<dependency><groupId>" + G + "</groupId><artifactId>" + artifact + "</artifactId>"
        + "<version>" + version + "</version>"
        + (scope == null ? "" : "<scope>" + scope + "</scope>")
        + "</dependency>";
  }
}
