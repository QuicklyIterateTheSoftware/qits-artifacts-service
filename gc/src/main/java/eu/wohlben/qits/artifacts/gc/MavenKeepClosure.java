package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * What the kept maven coordinates need to resolve — {@code maven-packages}' closure keep, and the
 * rule that lets this type collect a published release again without repeating 2026-09-05.
 *
 * <h2>What a reference is</h2>
 *
 * <p>The walk starts from the seeds {@link MavenPackagesGcAdapter#pinnedBy} hands it — the manifest
 * pins, the release belt and the snapshot belt — and, for every coordinate it reaches that this
 * store holds, follows three kinds of edge:
 *
 * <ol>
 *   <li><b>The coordinate's SBOM</b>, when one is stored: every component with a maven purl, or
 *       with a group, name and version and no purl at all. A CycloneDX document is the
 *       <em>transitive</em> dependency set of the build that published it, so one document answers
 *       what used to take a walk through every pom below it.
 *   <li><b>The pom's {@code <parent>} and its {@code import}-scoped managed dependencies</b>,
 *       always. No SBOM lists either — they are build inputs of the pom, not dependencies of the
 *       artifact — and a resolve reads both files. {@code qits-githost-events}' parent {@code
 *       qits-githost} is the live case.
 *   <li><b>The pom's {@code <dependencies>}</b>, test scope excepted — <b>only when the coordinate
 *       has no SBOM</b>. A coordinate without a document is never a reason to delete what it
 *       uses; its direct dependencies stand in, and the walk supplies the transitive half by
 *       reaching each of them in turn.
 * </ol>
 *
 * <p><b>Internal means existing in this store.</b> A referenced coordinate this store does not hold
 * is maven central's problem, through the mirror, and simply falls out; one it does hold is one a
 * resolve would come here for. No group prefix is configured, and none must be.
 *
 * <h2>Versions are resolved the way maven resolves them, as far as this store can see</h2>
 *
 * <p>Published poms are flattened since qits-620, but most of the store predates that: on
 * 2026-10-03, 400 of 630 stored poms named a parent and 323 declared a dependency with no version
 * of its own. So a reference's version is read from the pom's <b>effective</b> model, built the way
 * maven builds it: properties and {@code dependencyManagement} are inherited down the {@code
 * <parent>} chain wherever the parent pom is stored here, the child's own entries win, and inherited
 * entries are interpolated in the child's context ({@code ${project.version}} is the child's).
 * A dependency with no version takes the first managed entry for its {@code groupId:artifactId}, and
 * after those, the managed entries of the BOMs the effective model imports, each read in its own
 * context. Nothing is guessed: a value this cannot derive from stored poms stays unresolved.
 *
 * <h2>Fail closed</h2>
 *
 * <p>The closure is a keep-set, so an incomplete one is a deletion of whatever the missing part
 * would have named. Every way of not knowing therefore stops the walk and answers {@link
 * Incomplete} instead of a smaller map: a reached coordinate with no pom, a pom (its own or one it
 * inherits from) or a document whose bytes cannot be read, an SBOM that does not parse, and a
 * version that stays unresolved
 * on a reference whose {@code groupId:artifactId} this store holds — a {@code ${property}} no
 * stored pom in the chain defines, a range, or a dependency nothing manages. An unresolvable version
 * on a reference this store does not host cannot name anything here, so it is not a gap. The caller
 * turns {@link Incomplete} into "keep everything", which is the only honest answer to a closure it
 * could not finish.
 *
 * <p><b>A pom whose bytes were read and are not XML is a dead end, not a gap.</b> Maven cannot
 * resolve such a coordinate itself, so nothing builds through it and nothing it would name is a
 * build input anyone relies on: there is nothing missing to be unsure about. The coordinate stays
 * kept by whatever kept it, the walk follows no edge from it (its SBOM included), and {@link
 * Closed#deadEnds} carries it so the receipt says so ({@link #NOT_XML}). The live case was {@code
 * eu:probe:1}, a leftover publish probe whose pom is the byte {@code x} — under the stricter rule
 * it was a belt release on every run, and kept the whole type uncollected for good. As a parent or
 * an imported BOM it contributes no inherited value, so a version that needed one stays unresolved
 * and fails closed as above.
 *
 * <h2>Bounds</h2>
 *
 * <p>A breadth-first fixpoint over a visited set, so a cycle terminates on the second visit; each
 * pom is parsed and each effective model built once per walk. {@link #MAX_COORDINATES_READ} and
 * {@link #MAX_CHAIN_DEPTH} are the belts over that, and stopping at either is {@link Incomplete} too.
 */
final class MavenKeepClosure {

  /** A bound on a bug, not on the rule: the live store holds several hundred coordinates. */
  static final int MAX_COORDINATES_READ = 20_000;

  /** How deep a parent or imported-BOM chain may go before it is treated as a cycle. */
  static final int MAX_CHAIN_DEPTH = 32;

  /** How much of one pom or SBOM is read. A document past this is not one this rule understands. */
  static final int MAX_DOCUMENT_BYTES = 32 << 20;

  static final String NAMED_BY_SBOM = "named by the SBOM of ";
  static final String PARENT_OF = "parent pom of ";
  static final String IMPORTED_BY = "BOM imported by the pom of ";
  static final String DEPENDENCY_OF = "dependency in the pom of ";
  static final String WHICH_HAS_NO_SBOM = " (which has no SBOM)";

  private static final ObjectMapper JSON = new ObjectMapper();

  private MavenKeepClosure() {}

  /** Reads one hosted coordinate's documents. Null is "this coordinate has none". */
  interface Documents {

    /** The {@code .pom} bytes, or null when the coordinate has no pom row. Throws when unreadable. */
    byte[] pom(String coordinate) throws Exception;

    /** The stored SBOM's bytes, or null when no document exists for it. Throws when unreadable. */
    byte[] sbom(String coordinate) throws Exception;
  }

  /** What a walk answers. */
  sealed interface Result permits Closed, Incomplete {}

  /**
   * Every hosted coordinate an edge reached, mapped to the rule naming the first edge that did — a
   * seed appears only if some other coordinate's edge reached it too — and the reached coordinates
   * whose pom is not XML, which the walk kept but followed nothing from.
   */
  record Closed(Map<String, String> reached, Set<String> deadEnds) implements Result {}

  /** What a dead end's receipt line adds to the rule that kept it. */
  static final String NOT_XML =
      "pom is not XML — maven cannot resolve through it, so the closure follows nothing from it";

  /** The walk stopped at {@code coordinate} for {@code reason}; nothing it found may be trusted. */
  record Incomplete(String coordinate, String reason) implements Result {}

  /**
   * The closure over {@code seeds}.
   *
   * @param seeds coordinates already kept for a reason of their own
   * @param hosted every {@code groupId:artifactId:version} this store holds — the definition of
   *     internal
   * @param documents how a hosted coordinate's pom and SBOM are read
   */
  static Result from(Set<String> seeds, Set<String> hosted, Documents documents) {
    return new Walk(hosted, documents).run(seeds);
  }

  /** {@code groupId:artifactId} of a {@code groupId:artifactId:version}. */
  static String artifactOf(String coordinate) {
    int colon = coordinate.lastIndexOf(':');
    return colon < 0 ? coordinate : coordinate.substring(0, colon);
  }

  private record Edge(String target, String rule) {}

  /** Why the walk stopped, raised wherever it is found and turned into {@link Incomplete} once. */
  private static final class Gap extends Exception {
    private final String coordinate;

    private Gap(String coordinate, String reason) {
      super(reason, null, false, false);
      this.coordinate = coordinate;
    }
  }

  /** One walk's state: the store's view, and the poms and models already built. */
  private static final class Walk {

    private final Set<String> hosted;
    private final Set<String> hostedArtifacts = new HashSet<>();
    private final Documents documents;
    private final Map<String, Pom> poms = new HashMap<>();
    private final Map<String, Model> models = new HashMap<>();
    private final Set<String> deadEnds = new TreeSet<>();

    private Walk(Set<String> hosted, Documents documents) {
      this.hosted = hosted;
      this.documents = documents;
      for (String coordinate : hosted) {
        hostedArtifacts.add(artifactOf(coordinate));
      }
    }

    private Result run(Set<String> seeds) {
      Map<String, String> reached = new LinkedHashMap<>();
      // Sorted, so two runs over one store walk in one order and name the same first referrer.
      Set<String> visited = new TreeSet<>();
      Deque<String> pending = new ArrayDeque<>();
      for (String seed : new TreeSet<>(seeds)) {
        if (hosted.contains(seed) && visited.add(seed)) {
          pending.add(seed);
        }
      }
      int read = 0;
      while (!pending.isEmpty()) {
        if (++read > MAX_COORDINATES_READ) {
          return new Incomplete(
              pending.peek(), "the walk passed " + MAX_COORDINATES_READ + " coordinates");
        }
        String referrer = pending.poll();
        List<Edge> edges;
        try {
          edges = edgesOf(referrer);
        } catch (Gap gap) {
          return new Incomplete(gap.coordinate, gap.getMessage());
        }
        for (Edge edge : edges) {
          if (!hosted.contains(edge.target()) || edge.target().equals(referrer)) {
            continue;
          }
          reached.putIfAbsent(edge.target(), edge.rule());
          if (visited.add(edge.target())) {
            pending.add(edge.target());
          }
        }
      }
      return new Closed(Map.copyOf(reached), Set.copyOf(deadEnds));
    }

    /** One coordinate's outgoing edges. */
    private List<Edge> edgesOf(String coordinate) throws Gap {
      List<Edge> edges = new ArrayList<>();
      Model model = model(coordinate, 0);
      if (model == null) {
        // A pom that was read and is not XML: maven cannot resolve this coordinate, so nothing
        // builds through it and nothing it would name is anybody's build input.
        deadEnds.add(coordinate);
        return edges;
      }
      byte[] sbomBytes;
      try {
        sbomBytes = documents.sbom(coordinate);
      } catch (Exception unreadable) {
        throw new Gap(coordinate, "its SBOM could not be read (" + bounded(unreadable.toString()) + ")");
      }
      if (sbomBytes != null) {
        Set<String> components;
        try {
          components = sbomComponents(sbomBytes);
        } catch (Exception unparseable) {
          throw new Gap(
              coordinate,
              "its SBOM does not parse as CycloneDX JSON (" + bounded(unparseable.toString()) + ")");
        }
        for (String component : components) {
          edges.add(new Edge(component, NAMED_BY_SBOM + coordinate));
        }
      }

      String parent = model.parent();
      if (parent != null) {
        edges.add(new Edge(parent, PARENT_OF + coordinate));
      }
      for (RawRef imported : model.pom().imports()) {
        String target = coordinateOf(model, imported, "imported BOM", 0);
        if (target != null) {
          edges.add(new Edge(target, IMPORTED_BY + coordinate));
        }
      }
      if (sbomBytes == null) {
        for (RawRef dependency : model.pom().dependencies()) {
          String target = coordinateOf(model, dependency, "dependency", 0);
          if (target != null) {
            edges.add(new Edge(target, DEPENDENCY_OF + coordinate + WHICH_HAS_NO_SBOM));
          }
        }
      }
      return edges;
    }

    /**
     * A reference as a hosted-or-not coordinate, resolved in {@code model}'s context; null when its
     * {@code groupId:artifactId} is not hosted here. Unresolvable on a hosted one is a {@link Gap}.
     */
    private String coordinateOf(Model model, RawRef reference, String what, int depth) throws Gap {
      String groupId = resolve(reference.groupId(), model.properties());
      String artifactId = resolve(reference.artifactId(), model.properties());
      if (groupId == null || artifactId == null) {
        throw new Gap(
            model.coordinate(),
            "its " + what + " " + reference + " has an unresolvable groupId or artifactId");
      }
      String artifact = groupId + ":" + artifactId;
      if (!hostedArtifacts.contains(artifact)) {
        return null;
      }
      String version =
          reference.version() == null
              ? managedVersion(model, groupId, artifactId, depth)
              : resolve(reference.version(), model.properties());
      if (version == null || isRange(version)) {
        throw new Gap(
            model.coordinate(),
            "its " + what + " " + reference + " has a version this rule cannot resolve");
      }
      return artifact + ":" + version;
    }

    /**
     * The version {@code model}'s effective {@code dependencyManagement} gives {@code
     * groupId:artifactId}: its own and inherited entries first, child before parent, then the
     * managed entries of the hosted BOMs it imports, each in the BOM's own context. Null when none
     * does.
     */
    private String managedVersion(Model model, String groupId, String artifactId, int depth)
        throws Gap {
      if (depth > MAX_CHAIN_DEPTH) {
        throw new Gap(model.coordinate(), "its imported BOMs nest deeper than " + MAX_CHAIN_DEPTH);
      }
      for (RawRef managed : model.managed()) {
        if (!managed.isImport()
            && groupId.equals(resolve(managed.groupId(), model.properties()))
            && artifactId.equals(resolve(managed.artifactId(), model.properties()))) {
          return managed.version() == null ? null : resolve(managed.version(), model.properties());
        }
      }
      for (RawRef imported : model.managed()) {
        if (!imported.isImport()) {
          continue;
        }
        String bom = coordinateOfImport(model, imported);
        if (bom != null) {
          Model bomModel = model(bom, depth + 1);
          String version =
              bomModel == null ? null : managedVersion(bomModel, groupId, artifactId, depth + 1);
          if (version != null) {
            return version;
          }
        }
      }
      return null;
    }

    /** An imported BOM's coordinate when it is stored here and resolvable, else null. */
    private String coordinateOfImport(Model model, RawRef imported) {
      String groupId = resolve(imported.groupId(), model.properties());
      String artifactId = resolve(imported.artifactId(), model.properties());
      String version = resolve(imported.version(), model.properties());
      if (groupId == null || artifactId == null || version == null) {
        return null;
      }
      String coordinate = groupId + ":" + artifactId + ":" + version;
      return hosted.contains(coordinate) ? coordinate : null;
    }

    /**
     * The effective model of a stored coordinate's pom: its properties and managed entries with the
     * hosted parent chain's underneath, and its parent's coordinate. Null when the pom is not XML: a
     * parent or BOM like that contributes nothing, and a version that needed it stays unresolved.
     */
    private Model model(String coordinate, int depth) throws Gap {
      Model known = models.get(coordinate);
      if (known != null) {
        return known;
      }
      if (depth > MAX_CHAIN_DEPTH) {
        throw new Gap(coordinate, "its parent chain is deeper than " + MAX_CHAIN_DEPTH);
      }
      Pom pom = pom(coordinate);
      if (pom == null) {
        return null;
      }

      // The parent's own coordinates are read with the pom's literal values only, as maven reads
      // them: a parent's version cannot come from properties the parent itself would supply.
      Map<String, String> own = new HashMap<>(pom.properties());
      builtins(own, pom);
      String parent = null;
      Model inherited = null;
      if (pom.parent() != null) {
        String groupId = resolve(pom.parent().groupId(), own);
        String artifactId = resolve(pom.parent().artifactId(), own);
        String version = resolve(pom.parent().version(), own);
        if (groupId == null || artifactId == null) {
          throw new Gap(
              coordinate, "its parent " + pom.parent() + " has an unresolvable groupId or artifactId");
        }
        if (hostedArtifacts.contains(groupId + ":" + artifactId)) {
          if (version == null || isRange(version)) {
            throw new Gap(
                coordinate, "its parent " + pom.parent() + " has a version this rule cannot resolve");
          }
          parent = groupId + ":" + artifactId + ":" + version;
          if (hosted.contains(parent)) {
            inherited = model(parent, depth + 1);
          }
        }
      }

      Map<String, String> properties = new HashMap<>();
      if (inherited != null) {
        properties.putAll(inherited.properties());
      }
      properties.putAll(pom.properties());
      builtins(properties, pom);
      List<RawRef> managed = new ArrayList<>(pom.managed());
      if (inherited != null) {
        managed.addAll(inherited.managed());
      }
      Model model =
          new Model(coordinate, pom, Map.copyOf(properties), List.copyOf(managed), parent);
      models.put(coordinate, model);
      return model;
    }

    /**
     * One stored coordinate's parsed pom, or null when its bytes were read and are not XML. Missing
     * and unreadable are a gap: those say nothing about what the pom names, where bytes that are
     * not a pom say that it names nothing maven could use.
     */
    private Pom pom(String coordinate) throws Gap {
      if (poms.containsKey(coordinate)) {
        return poms.get(coordinate);
      }
      byte[] bytes;
      try {
        bytes = documents.pom(coordinate);
      } catch (Exception unreadable) {
        throw new Gap(coordinate, "its pom could not be read (" + bounded(unreadable.toString()) + ")");
      }
      if (bytes == null) {
        throw new Gap(
            coordinate,
            "it has no pom in this store, so its parent and imported BOMs cannot be followed");
      }
      Pom pom = Pom.parse(bytes);
      poms.put(coordinate, pom);
      return pom;
    }
  }

  /** {@code project.*} and its aliases, from the pom's own coordinates with the parent's beneath. */
  private static void builtins(Map<String, String> properties, Pom pom) {
    put(properties, pom.groupId(), "project.groupId", "pom.groupId", "groupId");
    put(properties, pom.version(), "project.version", "pom.version", "version");
    put(properties, pom.artifactId(), "project.artifactId", "pom.artifactId", "artifactId");
    if (pom.parent() != null) {
      put(properties, pom.parent().groupId(), "project.parent.groupId", "parent.groupId");
      put(properties, pom.parent().version(), "project.parent.version", "parent.version");
    }
  }

  private static void put(Map<String, String> properties, String value, String... names) {
    if (value == null || value.contains("${")) {
      return;
    }
    for (String name : names) {
      properties.put(name, value);
    }
  }

  /** A version range names no one version, so a keep cannot be derived from it. */
  private static boolean isRange(String version) {
    return version.indexOf('[') >= 0 || version.indexOf('(') >= 0 || version.indexOf(',') >= 0;
  }

  /**
   * A value with every {@code ${…}} replaced from {@code properties}, repeatedly, so a property
   * defined in terms of another resolves; null if any name is unknown or the expansion does not
   * settle.
   */
  static String resolve(String value, Map<String, String> properties) {
    if (value == null) {
      return null;
    }
    String current = value;
    for (int round = 0; round < 16; round++) {
      if (current.indexOf("${") < 0) {
        String resolved = current.trim();
        return resolved.isEmpty() ? null : resolved;
      }
      StringBuilder out = new StringBuilder();
      int from = 0;
      while (true) {
        int open = current.indexOf("${", from);
        if (open < 0) {
          out.append(current, from, current.length());
          break;
        }
        int close = current.indexOf('}', open);
        if (close < 0) {
          return null;
        }
        String replacement = properties.get(current.substring(open + 2, close));
        if (replacement == null) {
          return null;
        }
        out.append(current, from, open).append(replacement);
        from = close + 1;
      }
      current = out.toString();
    }
    return null;
  }

  private static String bounded(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "…";
  }

  // --- the SBOM ----------------------------------------------------------------------------------

  /**
   * Every maven coordinate a CycloneDX JSON document names, nested components included.
   *
   * <p>A component with a purl is read from the purl and only if it is {@code pkg:maven}: an npm
   * component carries a group and a name too, and must not be read as a maven coordinate. A
   * component with no purl at all counts when it has a group, a name and a version.
   */
  static Set<String> sbomComponents(byte[] document) throws Exception {
    JsonNode root = JSON.readTree(document);
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("not a JSON object");
    }
    Set<String> found = new LinkedHashSet<>();
    collect(root.path("components"), found);
    return found;
  }

  private static void collect(JsonNode components, Set<String> found) {
    if (!components.isArray()) {
      return;
    }
    for (JsonNode component : components) {
      String purl = text(component, "purl");
      String coordinate =
          purl != null
              ? fromPurl(purl)
              : join(text(component, "group"), text(component, "name"), text(component, "version"));
      if (coordinate != null) {
        found.add(coordinate);
      }
      collect(component.path("components"), found);
    }
  }

  /** {@code pkg:maven/<group>/<name>@<version>[?…][#…]} as {@code group:name:version}, else null. */
  static String fromPurl(String purl) {
    if (!purl.startsWith("pkg:maven/")) {
      return null;
    }
    String rest = purl.substring("pkg:maven/".length());
    int cut = indexOfAny(rest, '?', '#');
    if (cut >= 0) {
      rest = rest.substring(0, cut);
    }
    int at = rest.lastIndexOf('@');
    if (at < 0) {
      return null;
    }
    String path = rest.substring(0, at);
    int slash = path.lastIndexOf('/');
    if (slash < 0) {
      return null;
    }
    return join(
        percentDecoded(path.substring(0, slash)),
        percentDecoded(path.substring(slash + 1)),
        percentDecoded(rest.substring(at + 1)));
  }

  private static int indexOfAny(String text, char first, char second) {
    int a = text.indexOf(first);
    int b = text.indexOf(second);
    return a < 0 ? b : b < 0 ? a : Math.min(a, b);
  }

  /** Percent-decoding as a purl spells it — and unlike {@code URLDecoder}, a {@code +} stays one. */
  private static String percentDecoded(String text) {
    if (text.indexOf('%') < 0) {
      return text;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int hex = c == '%' && i + 2 < text.length() ? hexByte(text, i + 1) : -1;
      if (hex >= 0) {
        out.write(hex);
        i += 2;
      } else {
        out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
      }
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static int hexByte(String text, int at) {
    int high = Character.digit(text.charAt(at), 16);
    int low = Character.digit(text.charAt(at + 1), 16);
    return high < 0 || low < 0 ? -1 : high * 16 + low;
  }

  private static String join(String groupId, String artifactId, String version) {
    if (blank(groupId) || blank(artifactId) || blank(version)) {
      return null;
    }
    return groupId + ":" + artifactId + ":" + version;
  }

  private static boolean blank(String text) {
    return text == null || text.isBlank();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || !value.isTextual() ? null : value.asText().trim();
  }

  // --- the pom -----------------------------------------------------------------------------------

  /**
   * One {@code <parent>} or {@code <dependency>} element as written — trimmed text, nothing
   * resolved, because what a value means depends on the model it is read in.
   */
  record RawRef(String groupId, String artifactId, String version, String scope) {

    boolean isImport() {
      return "import".equals(scope);
    }

    @Override
    public String toString() {
      return groupId + ":" + artifactId + ":" + (version == null ? "(no version)" : version);
    }
  }

  /**
   * A pom as written: its own coordinates (the parent's beneath where it omits them), its parent,
   * its literal {@code <properties>}, its {@code dependencyManagement} entries (imports included),
   * and its non-test dependencies.
   */
  record Pom(
      String groupId,
      String artifactId,
      String version,
      RawRef parent,
      Map<String, String> properties,
      List<RawRef> managed,
      List<RawRef> dependencies) {

    /** The {@code import}-scoped managed entries — the BOMs this pom itself imports. */
    List<RawRef> imports() {
      return managed.stream().filter(RawRef::isImport).toList();
    }

    /** The parsed pom, or null when the bytes are not an XML {@code <project>}. */
    static Pom parse(byte[] bytes) {
      Element project = root(bytes);
      if (project == null) {
        return null;
      }
      Element parentElement = child(project, "parent");
      RawRef parent = parentElement == null ? null : raw(parentElement);
      Map<String, String> properties = new HashMap<>();
      for (Element declared : children(child(project, "properties"), null)) {
        String value = trimmed(textOf(declared));
        if (value != null) {
          properties.putIfAbsent(declared.getNodeName(), value);
        }
      }
      List<RawRef> managed = new ArrayList<>();
      for (Element block : children(child(project, "dependencyManagement"), "dependencies")) {
        for (Element dependency : children(block, "dependency")) {
          managed.add(raw(dependency));
        }
      }
      List<RawRef> dependencies = new ArrayList<>();
      for (Element block : children(project, "dependencies")) {
        for (Element dependency : children(block, "dependency")) {
          RawRef reference = raw(dependency);
          if (!"test".equals(reference.scope())) {
            dependencies.add(reference);
          }
        }
      }
      return new Pom(
          or(childText(project, "groupId"), parent == null ? null : parent.groupId()),
          trimmed(childText(project, "artifactId")),
          or(childText(project, "version"), parent == null ? null : parent.version()),
          parent,
          Map.copyOf(properties),
          List.copyOf(managed),
          List.copyOf(dependencies));
    }
  }

  /**
   * A stored pom's effective model: the properties and managed entries a reference in it is read
   * against, and its parent's coordinate when the parent is an artifact this store hosts.
   */
  private record Model(
      String coordinate,
      Pom pom,
      Map<String, String> properties,
      List<RawRef> managed,
      String parent) {}

  private static RawRef raw(Element element) {
    return new RawRef(
        trimmed(childText(element, "groupId")),
        trimmed(childText(element, "artifactId")),
        trimmed(childText(element, "version")),
        trimmed(childText(element, "scope")));
  }

  private static String or(String first, String second) {
    String text = trimmed(first);
    return text == null ? trimmed(second) : text;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    return text.isEmpty() ? null : text;
  }

  // --- the little DOM, with the settings a document off a wire needs ------------------------------

  /**
   * The document's root element, or null when it does not parse.
   *
   * <p>The JDK's own parser, so this adds no dependency and nothing for the native image to be told.
   * External entities and the DOCTYPE are off: these bytes were pushed to this registry by whoever
   * could reach it. Namespace-unaware on purpose, so elements are found by their plain names.
   */
  private static Element root(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(SILENT);
      Element root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
      return root != null && "project".equals(root.getNodeName()) ? root : null;
    } catch (Exception unreadable) {
      return null;
    }
  }

  /** A parse failure is an answer here, so it is thrown to the caller rather than printed. */
  private static final ErrorHandler SILENT =
      new ErrorHandler() {
        @Override
        public void warning(SAXParseException ignored) {}

        @Override
        public void error(SAXParseException ignored) {}

        @Override
        public void fatalError(SAXParseException fatal) throws SAXException {
          throw fatal;
        }
      };

  private static Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  /** Every DIRECT child element of that name, or of any name when {@code name} is null. */
  private static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    if (parent == null) {
      return found;
    }
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE
          && (name == null || node.getNodeName().equals(name))) {
        found.add((Element) node);
      }
    }
    return found;
  }

  private static String childText(Element parent, String name) {
    Element element = child(parent, name);
    return element == null ? null : textOf(element);
  }

  private static String textOf(Element element) {
    StringBuilder text = new StringBuilder();
    NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
        text.append(node.getNodeValue());
      }
    }
    return text.toString();
  }
}
