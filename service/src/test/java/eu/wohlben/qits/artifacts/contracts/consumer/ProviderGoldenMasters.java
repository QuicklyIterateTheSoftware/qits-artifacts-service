package eu.wohlben.qits.artifacts.contracts.consumer;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslJsonRootValue;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>One provider's recorded answers, as this service's consumer pacts read them</b> (qits-1149).
 *
 * <p>A provider records what it answers per provider state — {@code golden-masters/index.json} plus
 * one JSON per (state, operation) — and publishes the tree as {@code
 * eu.wohlben.qits:<app>-golden-masters}. Adapted from qits-maintenance-service's {@code
 * testing/contracts/GoldenMasters}, with two changes:
 *
 * <ul>
 *   <li><b>Several providers on one classpath.</b> This service reads seven providers, and every
 *       golden-master jar puts its index at the same {@code golden-masters/index.json}. So the
 *       reader asks the class loader for every such resource and keeps the one whose {@code
 *       provider} is the application asked for; each recording is read next to that index.
 *   <li><b>The pact binds only what this service reads.</b> Each interaction names the body paths
 *       its reader consumes ({@code $.pins[*].image}). The response body keeps only those paths of
 *       the recording, so a provider may add, rename or drop any other field without breaking this
 *       pact. Every named path must be in the recording; an array on a named path must record at
 *       least one element, or there is no template to match against. An array is matched with
 *       {@code eachLike}: any length, each element like the template, because no reader here counts.
 * </ul>
 *
 * <p>Leaf matchers follow the index's {@code frozen} lists, as in the original: {@code ids} a UUID
 * regex, {@code instants} an ISO-8601 regex, everything else a type match ({@code type OR null}
 * where some recorded element held null).
 */
public final class ProviderGoldenMasters {

  /** The consumer, as every pact names it: the repository name. */
  public static final String CONSUMER = "qits-artifacts-service";

  /** Where a golden-master jar puts its tree on the classpath. */
  public static final String ROOT = "golden-masters/";

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String application;
  private final String repository;
  private final URL indexUrl;
  private final JsonNode index;

  private ProviderGoldenMasters(String application, String repository, URL indexUrl, JsonNode index) {
    this.application = application;
    this.repository = repository;
    this.indexUrl = indexUrl;
    this.index = index;
  }

  /**
   * The golden masters of one provider.
   *
   * @param application the provider as its index names it: the application ({@code qits-ci})
   * @param repository the provider as a pact names it: the repository ({@code qits-ci-service})
   */
  public static ProviderGoldenMasters of(String application, String repository) {
    return of(ROOT, application, repository);
  }

  /** {@link #of(String, String)} under another classpath root, for the machinery test. */
  static ProviderGoldenMasters of(String root, String application, String repository) {
    List<String> seen = new ArrayList<>();
    try {
      Enumeration<URL> indexes = loader().getResources(root + "index.json");
      while (indexes.hasMoreElements()) {
        URL url = indexes.nextElement();
        JsonNode index = MAPPER.readTree(read(url));
        String provider = index.path("provider").asText();
        seen.add(provider);
        if (!application.equals(provider)) {
          continue;
        }
        if (index.path("formatVersion").asInt() != 1) {
          throw new IllegalStateException(
              url + " is formatVersion " + index.path("formatVersion") + "; this reads 1");
        }
        return new ProviderGoldenMasters(application, repository, url, index);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    throw new IllegalStateException(
        "No " + root + "index.json for " + application + " on the test classpath (found "
            + seen + ") — is eu.wohlben.qits:" + application
            + "-golden-masters a test dependency of this module?");
  }

  public String application() {
    return application;
  }

  public String repository() {
    return repository;
  }

  // --- the index --------------------------------------------------------------------------------

  /** One recorded (state, operation), as the index describes it. */
  public record Operation(
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants) {

    /** The path with every {@code {param}} replaced by the state's frozen example. */
    public String examplePath() {
      return substitute(path, params::get);
    }

    /** The path as a provider-state expression: {@code {param}} becomes {@code ${param}}. */
    public String expressionPath() {
      return substitute(path, name -> "${" + name + "}");
    }

    /**
     * Whether the path names a {@code {param}}. A constant path must never get a provider-state
     * generator: pact-jvm reads a string with no {@code ${...}} as a context KEY and resolves it
     * to {@code null}.
     */
    public boolean hasPathParams() {
      return PARAM.matcher(path).find();
    }

    private String substitute(String template, java.util.function.Function<String, String> value) {
      Matcher m = PARAM.matcher(template);
      StringBuilder out = new StringBuilder();
      while (m.find()) {
        String name = m.group(1);
        if (!params.containsKey(name)) {
          throw new IllegalStateException(
              "golden master " + state + "/" + operationId + ": path " + template + " names {"
                  + name + "}, which the state's params do not hold");
        }
        m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
      }
      m.appendTail(out);
      return out.toString();
    }
  }

  /** The state's frozen example params. */
  public Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    stateNode(state)
        .path("params")
        .fields()
        .forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
    return params;
  }

  /** The index entry for one (state, operation); fails naming both when the index has none. */
  public Operation operation(String state, String operationId) {
    for (JsonNode op : stateNode(state).path("operations")) {
      if (operationId.equals(op.path("operationId").asText())) {
        JsonNode frozen = op.path("frozen");
        return new Operation(
            state,
            params(state),
            operationId,
            op.path("method").asText(),
            op.path("path").asText(),
            op.path("status").asInt(),
            op.path("file").asText(),
            strings(frozen.path("ids")),
            strings(frozen.path("instants")));
      }
    }
    throw new IllegalArgumentException(
        application + "'s golden masters record no operation " + operationId + " in state '"
            + state + "'");
  }

  /** The recorded JSON for one (state, operation), parsed — a fresh tree each call. */
  public JsonNode json(String state, String operationId) {
    try {
      return MAPPER.readTree(read(new URL(indexUrl, operation(state, operationId).file())));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // --- the pact ---------------------------------------------------------------------------------

  /**
   * Adds the V4 HTTP interaction for one row: {@code given(state, params)}; method and path from
   * the index, each {@code {param}} a provider-state expression; the row's own request headers and
   * body; the recorded status; a response body holding only {@code consumes}, matched as the class
   * javadoc says; and the {@code comments.references} pair — {@code qits-call} and {@code
   * qits-trigger}.
   */
  public PactBuilder interaction(PactBuilder builder, ConsumerContract.Row row) {
    Objects.requireNonNull(row.trigger(), "trigger: every interaction names its entry point");
    Operation op = operation(row.state(), row.operationId());
    DslPart body = responseBody(op, row.consumes());
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", repository);
    call.put("operationId", row.operationId());
    references.put("qits-call", call);
    references.put("qits-trigger", row.trigger().reference());
    return builder.expectsToReceiveHttpInteraction(
        row.description(),
        http -> {
          http.state(row.state(), new LinkedHashMap<String, Object>(op.params()));
          http.withRequest(
              request -> {
                request.method(op.method());
                if (op.hasPathParams()) {
                  request.path(Matchers.fromProviderState(op.expressionPath(), op.examplePath()));
                } else {
                  request.path(op.examplePath());
                }
                row.request().accept(request, op.params());
                return request;
              });
          http.willRespondWith(
              response -> {
                response
                    .status(op.status())
                    .header(
                        "Content-Type", Matchers.regexp("application/json.*", "application/json"));
                return body == null ? response : response.body(body);
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /** The recording reduced to {@code consumes}, as a matcher body; null when it consumes nothing. */
  DslPart responseBody(Operation op, List<String> consumes) {
    if (consumes.isEmpty()) {
      return null;
    }
    JsonNode recorded = json(op.state(), op.operationId());
    if (!recorded.isObject()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId()
              + ": only an object body is supported, got " + recorded.getNodeType());
    }
    JsonNode projected = project(recorded, consumes, op);
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, Shape.of(projected), "$", op);
    return root;
  }

  /**
   * The recorded body with only the consumed paths left in it. Paths are {@code $.a.b}, an array
   * element {@code [*]}; naming a path keeps everything below it.
   *
   * @throws IllegalStateException a consumed path the recording does not hold — the provider state
   *     must record it, or the reader's need is not proven
   */
  static JsonNode project(JsonNode recorded, List<String> consumes, Operation op) {
    Set<String> wanted = new LinkedHashSet<>(consumes);
    Set<String> found = new TreeSet<>();
    JsonNode projected = project(recorded, "$", wanted, found);
    List<String> missing = new ArrayList<>(wanted);
    missing.removeAll(found);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId() + " does not record " + missing
              + " — an array on such a path needs at least one recorded element");
    }
    return projected;
  }

  private static JsonNode project(JsonNode node, String path, Set<String> wanted, Set<String> found) {
    if (wanted.contains(path)) {
      found.add(path);
      return node.deepCopy();
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String child = path + "." + field.getKey();
        if (leadsTo(child, wanted)) {
          out.set(field.getKey(), project(field.getValue(), child, wanted, found));
        }
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      String element = path + "[*]";
      for (JsonNode e : node) {
        out.add(project(e, element, wanted, found));
      }
      return out;
    }
    // A leaf (or null) on the way to a deeper consumed path: nothing below it to keep.
    return node.deepCopy();
  }

  private static boolean leadsTo(String path, Set<String> wanted) {
    for (String w : wanted) {
      if (w.equals(path) || w.startsWith(path + ".") || w.startsWith(path + "[")) {
        return true;
      }
    }
    return false;
  }

  // --- the body ---------------------------------------------------------------------------------

  private static void fillObject(PactDslJsonBody target, Shape shape, String path, Operation op) {
    for (Map.Entry<String, Shape> field : shape.fields.entrySet()) {
      String name = field.getKey();
      Shape child = field.getValue();
      String childPath = path + "." + name;
      switch (child.kind) {
        case NULL -> target.nullValue(name);
        case LEAF -> leaf(target, name, child, childPath, op);
        case OBJECT -> {
          if (child.nullable) {
            throw unsupported(op, childPath, "an object that is null in some elements");
          }
          PactDslJsonBody nested = target.object(name);
          fillObject(nested, child, childPath, op);
          nested.closeObject();
        }
        case ARRAY -> array(target, name, child, childPath, op);
      }
    }
  }

  private static void leaf(
      PactDslJsonBody target, String name, Shape leaf, String path, Operation op) {
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op);
      if (leaf.nullable) {
        target.or(
            name, example.asText(), new RegexMatcher(UUID_REGEX, example.asText()),
            NullMatcher.INSTANCE);
      } else {
        target.uuid(name, example.asText());
      }
    } else if (op.instants().contains(path)) {
      requireText(example, path, op);
      if (leaf.nullable) {
        target.or(
            name, example.asText(), new RegexMatcher(ISO_INSTANT, example.asText()),
            NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, example.asText());
      }
    } else if (leaf.nullable) {
      target.or(name, scalar(example), TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (example.isTextual()) {
      target.stringType(name, example.asText());
    } else if (example.isNumber()) {
      target.numberType(name, example.numberValue());
    } else if (example.isBoolean()) {
      target.booleanType(name, example.asBoolean());
    } else {
      throw unsupported(op, path, "a " + example.getNodeType() + " leaf");
    }
  }

  private static void array(
      PactDslJsonBody target, String name, Shape array, String path, Operation op) {
    if (array.nullable) {
      throw unsupported(op, path, "an array that is null in some elements");
    }
    if (array.length == 0) {
      throw unsupported(op, path, "an empty array, which gives no element to match against");
    }
    Shape element = array.element;
    String elementPath = path + "[*]";
    switch (element.kind) {
      case OBJECT -> {
        PactDslJsonBody template = target.eachLike(name, array.length);
        fillObject(template, element, elementPath, op);
        DslPart closed = template.closeObject();
        ((PactDslJsonArray) closed).closeArray();
      }
      case LEAF -> target.eachLike(name, rootLeaf(element, elementPath, op), array.length);
      default -> throw unsupported(op, elementPath, "an array of " + element.kind);
    }
  }

  private static PactDslJsonRootValue rootLeaf(Shape leaf, String path, Operation op) {
    if (leaf.nullable) {
      throw unsupported(op, path, "an array holding nulls");
    }
    JsonNode example = leaf.example;
    if (op.ids().contains(path)) {
      requireText(example, path, op);
      return PactDslJsonRootValue.uuid(example.asText());
    }
    if (op.instants().contains(path)) {
      requireText(example, path, op);
      return PactDslJsonRootValue.stringMatcher(ISO_INSTANT, example.asText());
    }
    if (example.isTextual()) {
      return PactDslJsonRootValue.stringType(example.asText());
    }
    if (example.isNumber()) {
      return PactDslJsonRootValue.numberType(example.numberValue());
    }
    if (example.isBoolean()) {
      return PactDslJsonRootValue.booleanType(example.asBoolean());
    }
    throw unsupported(op, path, "a " + example.getNodeType() + " array element");
  }

  private static Object scalar(JsonNode example) {
    if (example.isTextual()) {
      return example.asText();
    }
    if (example.isNumber()) {
      return example.numberValue();
    }
    if (example.isBoolean()) {
      return example.asBoolean();
    }
    throw new IllegalStateException("not a scalar: " + example);
  }

  private static void requireText(JsonNode example, String path, Operation op) {
    if (!example.isTextual()) {
      throw new IllegalStateException(
          "golden master " + op.state() + "/" + op.operationId() + ": frozen path " + path
              + " holds " + example.getNodeType() + ", not a string");
    }
  }

  private static IllegalStateException unsupported(Operation op, String path, String what) {
    return new IllegalStateException(
        "golden master " + op.state() + "/" + op.operationId() + ": " + path + " is " + what
            + ", which this reader cannot express as a pact matcher");
  }

  /**
   * The structure of a value, with an array's elements MERGED into one template: field union, a
   * leaf's first non-null example, and {@code nullable} wherever any element held null or lacked
   * the field.
   */
  private static final class Shape {
    enum Kind {
      NULL,
      LEAF,
      OBJECT,
      ARRAY
    }

    Kind kind;
    boolean nullable;
    JsonNode example;
    final LinkedHashMap<String, Shape> fields = new LinkedHashMap<>();
    Shape element;
    int length;

    static Shape of(JsonNode node) {
      Shape shape = new Shape();
      if (node == null || node.isNull() || node.isMissingNode()) {
        shape.kind = Kind.NULL;
        shape.nullable = true;
      } else if (node.isObject()) {
        shape.kind = Kind.OBJECT;
        node.fields().forEachRemaining(e -> shape.fields.put(e.getKey(), of(e.getValue())));
      } else if (node.isArray()) {
        shape.kind = Kind.ARRAY;
        shape.length = node.size();
        for (JsonNode e : node) {
          shape.element = shape.element == null ? of(e) : merge(shape.element, of(e));
        }
      } else {
        shape.kind = Kind.LEAF;
        shape.example = node;
      }
      return shape;
    }

    static Shape merge(Shape a, Shape b) {
      if (a.kind == Kind.NULL) {
        b.nullable = true;
        return b;
      }
      if (b.kind == Kind.NULL) {
        a.nullable = true;
        return a;
      }
      if (a.kind != b.kind) {
        throw new IllegalStateException(
            "golden master array elements disagree: " + a.kind + " and " + b.kind);
      }
      a.nullable |= b.nullable;
      switch (a.kind) {
        case OBJECT -> {
          List<String> keys = new ArrayList<>(a.fields.keySet());
          for (String key : b.fields.keySet()) {
            if (!keys.contains(key)) {
              keys.add(key);
            }
          }
          LinkedHashMap<String, Shape> merged = new LinkedHashMap<>();
          for (String key : keys) {
            Shape left = a.fields.get(key);
            Shape right = b.fields.get(key);
            merged.put(
                key,
                left == null
                    ? merge(of(null), right)
                    : right == null ? merge(left, of(null)) : merge(left, right));
          }
          a.fields.clear();
          a.fields.putAll(merged);
        }
        case ARRAY -> {
          a.length = Math.min(a.length, b.length);
          a.element =
              a.element == null
                  ? b.element
                  : b.element == null ? a.element : merge(a.element, b.element);
        }
        default -> {
          // LEAF: keep a's example; the matcher is a type match, so one example stands for all.
        }
      }
      return a;
    }
  }

  // --- reading ----------------------------------------------------------------------------------

  private JsonNode stateNode(String state) {
    for (JsonNode node : index.path("states")) {
      if (state.equals(node.path("name").asText())) {
        return node;
      }
    }
    throw new IllegalArgumentException(
        application + "'s golden masters record no state '" + state + "'");
  }

  private static Set<String> strings(JsonNode array) {
    Set<String> out = new LinkedHashSet<>();
    array.forEach(e -> out.add(e.asText()));
    return Collections.unmodifiableSet(out);
  }

  private static ClassLoader loader() {
    ClassLoader own = ProviderGoldenMasters.class.getClassLoader();
    return own != null ? own : Thread.currentThread().getContextClassLoader();
  }

  private static String read(URL url) throws IOException {
    try (InputStream in = url.openStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
