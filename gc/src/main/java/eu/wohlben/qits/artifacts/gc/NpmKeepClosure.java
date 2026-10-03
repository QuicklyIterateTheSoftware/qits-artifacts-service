package eu.wohlben.qits.artifacts.gc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.control.NpmSemver;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the kept npm versions need to install — {@code npm-packages}' closure keep, and the rule that
 * lets this type collect a published release again without repeating 2026-09-05 (qits-740).
 *
 * <p>The npm half of what {@link MavenKeepClosure} is for maven, shaped the same way on purpose: a
 * pure walk over a {@link Documents} seam, a {@link Closed} map naming the referrer of every version
 * it reached, and {@link Incomplete} for every way of not knowing.
 *
 * <h2>What a reference is</h2>
 *
 * <p>The walk starts from the seeds {@link NpmPackagesGcAdapter#pinnedBy} hands it — the manifest
 * pins, the newest releases of every package and every dist-tag target — and, for every version it
 * reaches that this store hosts, follows two kinds of edge:
 *
 * <ol>
 *   <li><b>The version's manifest</b>: its {@code dependencies}, {@code peerDependencies} and
 *       {@code optionalDependencies}. Those are what an install of the version resolves; {@code
 *       devDependencies} are not, because a consumer never installs a package's dev tree. A range
 *       is resolved to the <b>highest hosted version satisfying it</b>, which is what a fresh
 *       resolve would pick — a consumer whose lockfile pins something older is a manifest pin of
 *       its own, and is kept as one.
 *   <li><b>The version's SBOM</b>, when one is stored: every component whose purl is {@code
 *       pkg:npm/…@version} and names a hosted version exactly. A CycloneDX document is the
 *       <em>transitive</em> set the publishing build resolved, so it names the exact versions a
 *       lockfile at that build pinned, which a range alone cannot.
 * </ol>
 *
 * <p><b>Internal means existing in this store.</b> A dependency on a package this store does not
 * host is the proxy's problem and simply falls out; no scope is configured, and none must be.
 *
 * <h2>Ranges, as far as this rule reads them</h2>
 *
 * <p>The node-semver grammar the platform's manifests actually write: exact versions, {@code ^},
 * {@code ~}, {@code x}/{@code *} wildcards and partial versions, the comparators {@code < <= > >=
 * =}, hyphen ranges and {@code ||}, plus an {@code npm:<name>@<range>} alias and a dist-tag name. A
 * prerelease satisfies a range only when the range itself names a prerelease of the same {@code
 * major.minor.patch}, as npm has it. A range that names no hosted version is no edge — there is
 * nothing here an install could be sent to. A spec this cannot read at all ({@code file:}, a git
 * URL, an unknown tag) on a package this store hosts is a gap, below.
 *
 * <h2>Fail closed</h2>
 *
 * <p>The closure is a keep-set, so an incomplete one is a deletion of whatever the missing part
 * would have named. A reached version with no manifest, a manifest or SBOM whose bytes cannot be
 * read or do not parse, and an unreadable spec on a hosted dependency all stop the walk and answer
 * {@link Incomplete}; the caller keeps every npm identity that run. Unlike a maven pom, a stored npm
 * manifest is always a JSON object the publish route itself serialised, so there is no "bytes that
 * are not a manifest" dead end to carve out.
 *
 * <h2>Bounds</h2>
 *
 * <p>A breadth-first fixpoint over a visited set, so a cycle terminates on the second visit. {@link
 * #MAX_COORDINATES_READ} and {@link #MAX_DOCUMENT_BYTES} are the belts over that, and stopping at
 * either is {@link Incomplete} too.
 */
final class NpmKeepClosure {

  /** A bound on a bug, not on the rule: the live registry holds a few hundred versions. */
  static final int MAX_COORDINATES_READ = 20_000;

  /** How much of one manifest or SBOM is read. A document past this is not one this rule reads. */
  static final int MAX_DOCUMENT_BYTES = 32 << 20;

  static final String DEPENDENCY_OF = "a dependency of ";
  static final String PEER_DEPENDENCY_OF = "a peer dependency of ";
  static final String OPTIONAL_DEPENDENCY_OF = "an optional dependency of ";
  static final String NAMED_BY_SBOM = "named by the SBOM of ";
  static final String WHICH_IS_KEPT = ", which is kept";

  /** The manifest fields an install of a version resolves, each with the rule prefix it earns. */
  private static final Map<String, String> FIELDS = new LinkedHashMap<>();

  static {
    FIELDS.put("dependencies", DEPENDENCY_OF);
    FIELDS.put("peerDependencies", PEER_DEPENDENCY_OF);
    FIELDS.put("optionalDependencies", OPTIONAL_DEPENDENCY_OF);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private NpmKeepClosure() {}

  /** Reads one hosted version's documents. */
  interface Documents {

    /** The version's stored manifest bytes, or null when it has none. Throws when unreadable. */
    byte[] manifest(String coordinate) throws Exception;

    /** The stored SBOM's bytes, or null when no document exists for it. Throws when unreadable. */
    byte[] sbom(String coordinate) throws Exception;

    /** The version a dist-tag of a hosted package names, or null when it names none. */
    String distTag(String packageName, String tag);
  }

  /** What a walk answers. */
  sealed interface Result permits Closed, Incomplete {}

  /**
   * Every hosted version an edge reached, mapped to the rule naming the first edge that did — a seed
   * appears only if some other version's edge reached it too.
   */
  record Closed(Map<String, String> reached) implements Result {}

  /** The walk stopped at {@code coordinate} for {@code reason}; nothing it found may be trusted. */
  record Incomplete(String coordinate, String reason) implements Result {}

  /**
   * The closure over {@code seeds}.
   *
   * @param seeds versions already kept for a reason of their own, as {@code name@version}
   * @param hosted every {@code name@version} this store holds — the definition of internal
   * @param documents how a hosted version's manifest, SBOM and dist-tags are read
   */
  static Result from(Set<String> seeds, Set<String> hosted, Documents documents) {
    return new Walk(hosted, documents).run(seeds);
  }

  /** The package half of {@code name@version}; a scoped name starts with {@code @}, so the LAST. */
  static String packageOf(String coordinate) {
    int at = coordinate.lastIndexOf('@');
    return at <= 0 ? coordinate : coordinate.substring(0, at);
  }

  /** The version half of {@code name@version}. */
  static String versionOf(String coordinate) {
    int at = coordinate.lastIndexOf('@');
    return at <= 0 ? "" : coordinate.substring(at + 1);
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

  private record Hosted(String version, NpmSemver parsed) {}

  private static final class Walk {

    private final Set<String> hosted;
    private final Documents documents;
    private final Map<String, List<Hosted>> byPackage = new HashMap<>();

    private Walk(Set<String> hosted, Documents documents) {
      this.hosted = hosted;
      this.documents = documents;
      for (String coordinate : hosted) {
        String version = versionOf(coordinate);
        byPackage
            .computeIfAbsent(packageOf(coordinate), name -> new ArrayList<>())
            .add(new Hosted(version, NpmSemver.parse(version).orElse(null)));
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
              pending.peek(), "the walk passed " + MAX_COORDINATES_READ + " versions");
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
      return new Closed(Map.copyOf(reached));
    }

    private List<Edge> edgesOf(String coordinate) throws Gap {
      List<Edge> edges = new ArrayList<>();
      byte[] manifestBytes;
      try {
        manifestBytes = documents.manifest(coordinate);
      } catch (Exception unreadable) {
        throw new Gap(
            coordinate, "its manifest could not be read (" + bounded(unreadable.toString()) + ")");
      }
      if (manifestBytes == null) {
        throw new Gap(coordinate, "it has no manifest in this store, so its dependencies are unknown");
      }
      JsonNode manifest;
      try {
        manifest = JSON.readTree(manifestBytes);
      } catch (Exception unparseable) {
        manifest = null;
      }
      if (manifest == null || !manifest.isObject()) {
        throw new Gap(coordinate, "its manifest does not parse as a JSON object");
      }
      for (Map.Entry<String, String> field : FIELDS.entrySet()) {
        JsonNode declared = manifest.get(field.getKey());
        if (declared == null || declared.isNull()) {
          continue;
        }
        if (!declared.isObject()) {
          throw new Gap(coordinate, "its manifest's " + field.getKey() + " is not an object");
        }
        Iterator<Map.Entry<String, JsonNode>> entries = declared.fields();
        while (entries.hasNext()) {
          Map.Entry<String, JsonNode> entry = entries.next();
          String target = resolve(coordinate, field.getKey(), entry.getKey(), entry.getValue());
          if (target != null) {
            edges.add(new Edge(target, field.getValue() + coordinate + WHICH_IS_KEPT));
          }
        }
      }

      byte[] sbomBytes;
      try {
        sbomBytes = documents.sbom(coordinate);
      } catch (Exception unreadable) {
        throw new Gap(
            coordinate, "its SBOM could not be read (" + bounded(unreadable.toString()) + ")");
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
          edges.add(new Edge(component, NAMED_BY_SBOM + coordinate + WHICH_IS_KEPT));
        }
      }
      return edges;
    }

    /**
     * The hosted version one manifest entry resolves to, or null when it names nothing hosted. An
     * entry on a hosted package whose spec this cannot read is a {@link Gap}.
     */
    private String resolve(String referrer, String field, String name, JsonNode value) throws Gap {
      String packageName = name;
      String spec = value != null && value.isTextual() ? value.asText().trim() : null;
      if (spec != null && spec.startsWith("npm:")) {
        // An alias installs another package under this name: the target is what is downloaded.
        String aliased = spec.substring("npm:".length());
        int at = aliased.lastIndexOf('@');
        if (at > 0) {
          packageName = aliased.substring(0, at);
          spec = aliased.substring(at + 1).trim();
        } else {
          packageName = aliased;
          spec = "";
        }
      }
      List<Hosted> versions = byPackage.get(packageName);
      if (versions == null) {
        return null;
      }
      if (spec == null) {
        throw new Gap(
            referrer, "its " + field + " entry for " + packageName + " is not a string");
      }
      if (hosted.contains(packageName + "@" + spec)) {
        return packageName + "@" + spec;
      }
      Range range;
      try {
        range = Range.parse(spec);
      } catch (IllegalArgumentException unparseable) {
        String tagged = TAG.matcher(spec).matches() ? documents.distTag(packageName, spec) : null;
        if (tagged != null) {
          return packageName + "@" + tagged;
        }
        throw new Gap(
            referrer,
            "its "
                + field
                + " entry "
                + packageName
                + "@"
                + bounded(spec)
                + " is not a range or dist-tag this rule can resolve");
      }
      Hosted best = null;
      for (Hosted candidate : versions) {
        if (candidate.parsed() != null
            && range.test(candidate.parsed())
            && (best == null || candidate.parsed().compareTo(best.parsed()) > 0)) {
          best = candidate;
        }
      }
      return best == null ? null : packageName + "@" + best.version();
    }
  }

  /** What a dist-tag name may look like: not a range, not a URL, not a path. */
  private static final Pattern TAG = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");

  private static String bounded(String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "…";
  }

  // --- the SBOM ----------------------------------------------------------------------------------

  /**
   * Every npm coordinate a CycloneDX JSON document's components name by purl, nested components
   * included, as {@code name@version}. Components with another purl type, or no purl, name nothing
   * here: a group and a name without a purl type cannot be told apart from a maven artifact.
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
      JsonNode purl = component.get("purl");
      if (purl != null && purl.isTextual()) {
        String coordinate = fromPurl(purl.asText().trim());
        if (coordinate != null) {
          found.add(coordinate);
        }
      }
      collect(component.path("components"), found);
    }
  }

  /**
   * {@code pkg:npm/[<namespace>/]<name>@<version>[?…][#…]} as {@code [@scope/]name@version}, else
   * null. The scope's {@code @} is spelled {@code %40} by the spec and bare by some generators; both
   * read the same.
   */
  static String fromPurl(String purl) {
    if (!purl.startsWith("pkg:npm/")) {
      return null;
    }
    String rest = purl.substring("pkg:npm/".length());
    int cut = indexOfAny(rest, '?', '#');
    if (cut >= 0) {
      rest = rest.substring(0, cut);
    }
    int at = rest.lastIndexOf('@');
    if (at <= 0) {
      return null;
    }
    String name = percentDecoded(rest.substring(0, at));
    String version = percentDecoded(rest.substring(at + 1));
    if (name.isBlank() || version.isBlank() || name.startsWith("/") || name.endsWith("/")) {
      return null;
    }
    return name + "@" + version;
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

  // --- ranges ------------------------------------------------------------------------------------

  /**
   * A node-semver range, desugared to sets of primitive comparators the way node-semver does it: a
   * version satisfies the range when it satisfies every comparator of some set. {@link NpmSemver}
   * stays ordering only; this is the matching it deliberately leaves out, written here because this
   * rule is its only reader.
   */
  static final class Range {

    private record Comparator(String operator, NpmSemver version) {
      boolean test(NpmSemver candidate) {
        int order = candidate.compareTo(version);
        return switch (operator) {
          case "<" -> order < 0;
          case "<=" -> order <= 0;
          case ">" -> order > 0;
          case ">=" -> order >= 0;
          default -> order == 0;
        };
      }
    }

    /** One comparator as written: an operator and a partial version, wildcards allowed. */
    private static final Pattern PART =
        Pattern.compile(
            "^(<=|>=|<|>|=|~>|~|\\^)?v?(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?"
                + "(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private static final Pattern HYPHEN = Pattern.compile("^(\\S+)\\s+-\\s+(\\S+)$");

    private final List<List<Comparator>> sets;

    private Range(List<List<Comparator>> sets) {
      this.sets = sets;
    }

    /** The parsed range; throws {@link IllegalArgumentException} for anything it cannot read. */
    static Range parse(String spec) {
      List<List<Comparator>> sets = new ArrayList<>();
      for (String alternative : spec.split("\\|\\|", -1)) {
        // Operators may be spaced off their version (">= 1.2.3"); node-semver joins them back.
        String text = alternative.trim().replaceAll("(<=|>=|<|>|=|~>|~|\\^)\\s+", "$1");
        List<Comparator> set = new ArrayList<>();
        Matcher hyphen = HYPHEN.matcher(text);
        if (hyphen.matches()) {
          Partial low = Partial.of(hyphen.group(1), "");
          Partial high = Partial.of(hyphen.group(2), "");
          lowerBound(low, set);
          upperBoundInclusive(high, set);
        } else if (!text.isEmpty()) {
          for (String token : text.split("\\s+")) {
            desugar(token, set);
          }
        }
        sets.add(set);
      }
      return new Range(sets);
    }

    /** Whether {@code candidate} satisfies the range, npm's prerelease rule included. */
    boolean test(NpmSemver candidate) {
      for (List<Comparator> set : sets) {
        if (testSet(set, candidate)) {
          return true;
        }
      }
      return false;
    }

    private static boolean testSet(List<Comparator> set, NpmSemver candidate) {
      for (Comparator comparator : set) {
        if (!comparator.test(candidate)) {
          return false;
        }
      }
      if (!candidate.isPrerelease()) {
        return true;
      }
      // A prerelease is only in range when the range names a prerelease of the same core version.
      for (Comparator comparator : set) {
        NpmSemver bound = comparator.version();
        if (bound.isPrerelease()
            && bound.major().equals(candidate.major())
            && bound.minor().equals(candidate.minor())
            && bound.patch().equals(candidate.patch())) {
          return true;
        }
      }
      return false;
    }

    /** One version as written, with nulls for the parts a wildcard or an omission leaves open. */
    private record Partial(String major, String minor, String patch, String prerelease) {

      static Partial of(String token, String operatorAllowed) {
        Matcher m = PART.matcher(token);
        if (!m.matches() || (m.group(1) != null && operatorAllowed.isEmpty())) {
          throw new IllegalArgumentException("not a version: " + token);
        }
        String major = wild(m.group(2));
        String minor = major == null ? null : wild(m.group(3));
        String patch = minor == null ? null : wild(m.group(4));
        String prerelease = patch == null ? null : m.group(5);
        if (patch == null && m.group(5) != null) {
          throw new IllegalArgumentException("a prerelease on a partial version: " + token);
        }
        return new Partial(major, minor, patch, prerelease);
      }

      private static String wild(String part) {
        return part == null || part.equalsIgnoreCase("x") || part.equals("*") ? null : part;
      }

      NpmSemver floor() {
        return version(
            or(major), or(minor), or(patch), prerelease == null ? "" : "-" + prerelease);
      }

      private static String or(String part) {
        return part == null ? "0" : part;
      }
    }

    private static void desugar(String token, List<Comparator> set) {
      Matcher m = PART.matcher(token);
      if (!m.matches()) {
        throw new IllegalArgumentException("not a comparator: " + token);
      }
      String operator = m.group(1) == null ? "" : m.group(1);
      Partial p = Partial.of(token.substring(operator.length()), "");
      switch (operator) {
        case "^" -> caret(p, set);
        case "~", "~>" -> tilde(p, set);
        case "", "=" -> {
          if (p.patch() != null) {
            set.add(new Comparator("=", p.floor()));
          } else {
            lowerBound(p, set);
            upperBoundExclusiveOfPartial(p, set);
          }
        }
        case ">=" -> lowerBound(p, set);
        case "<=" -> upperBoundInclusive(p, set);
        case ">" -> {
          if (p.major() == null) {
            set.add(new Comparator("<", version("0", "0", "0", "-0")));
          } else if (p.patch() != null) {
            set.add(new Comparator(">", p.floor()));
          } else {
            set.add(new Comparator(">=", nextAfter(p)));
          }
        }
        case "<" -> {
          if (p.major() == null) {
            set.add(new Comparator("<", version("0", "0", "0", "-0")));
          } else if (p.patch() != null) {
            set.add(new Comparator("<", p.floor()));
          } else {
            set.add(new Comparator("<", withZeroPrerelease(p.floor())));
          }
        }
        default -> throw new IllegalArgumentException("unknown operator: " + operator);
      }
    }

    private static void caret(Partial p, List<Comparator> set) {
      if (p.major() == null) {
        return;
      }
      set.add(new Comparator(">=", p.floor()));
      NpmSemver upper;
      if (!p.major().equals("0") || p.minor() == null) {
        upper = version(inc(p.major()), "0", "0", "-0");
      } else if (!p.minor().equals("0") || p.patch() == null) {
        upper = version("0", inc(p.minor()), "0", "-0");
      } else {
        upper = version("0", "0", inc(p.patch()), "-0");
      }
      set.add(new Comparator("<", upper));
    }

    private static void tilde(Partial p, List<Comparator> set) {
      if (p.major() == null) {
        return;
      }
      set.add(new Comparator(">=", p.floor()));
      NpmSemver upper =
          p.minor() == null
              ? version(inc(p.major()), "0", "0", "-0")
              : version(p.major(), inc(p.minor()), "0", "-0");
      set.add(new Comparator("<", upper));
    }

    private static void lowerBound(Partial p, List<Comparator> set) {
      if (p.major() != null) {
        set.add(new Comparator(">=", p.floor()));
      }
    }

    private static void upperBoundInclusive(Partial p, List<Comparator> set) {
      if (p.major() == null) {
        return;
      }
      if (p.patch() != null) {
        set.add(new Comparator("<=", p.floor()));
      } else {
        upperBoundExclusiveOfPartial(p, set);
      }
    }

    /** The exclusive top of a partial: {@code 1} → {@code <2.0.0-0}, {@code 1.2} → {@code <1.3.0-0}. */
    private static void upperBoundExclusiveOfPartial(Partial p, List<Comparator> set) {
      if (p.major() == null) {
        return;
      }
      set.add(new Comparator("<", withZeroPrerelease(nextAfter(p))));
    }

    /** The first version above every version a partial covers: {@code 1} → 2.0.0, 1.2 → 1.3.0. */
    private static NpmSemver nextAfter(Partial p) {
      return p.minor() == null
          ? version(inc(p.major()), "0", "0", "")
          : version(p.major(), inc(p.minor()), "0", "");
    }

    private static NpmSemver withZeroPrerelease(NpmSemver version) {
      return version(version.major(), version.minor(), version.patch(), "-0");
    }

    private static NpmSemver version(String major, String minor, String patch, String suffix) {
      String text = major + "." + minor + "." + patch + suffix;
      Optional<NpmSemver> parsed = NpmSemver.parse(text);
      if (parsed.isEmpty()) {
        throw new IllegalArgumentException("not a version: " + text);
      }
      return parsed.get();
    }

    /** A digit string plus one, without assuming it fits a long (calver majors are four digits). */
    private static String inc(String digits) {
      return new java.math.BigInteger(digits).add(java.math.BigInteger.ONE).toString();
    }
  }
}
