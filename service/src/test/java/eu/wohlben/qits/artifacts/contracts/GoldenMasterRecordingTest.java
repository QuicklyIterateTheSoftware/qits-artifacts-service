package eu.wohlben.qits.artifacts.contracts;

import static io.restassured.RestAssured.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-artifacts' provider golden masters</b> (qits-1149, a copy of
 * qits-events-service's) — {@code golden-masters/} at the repository root, the source of the
 * published golden-master artifact consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the endpoint through REST-assured as the {@code %test} dev user, keeps
 * only the list entries the state created, freezes ids, instants and unique tokens ({@link
 * Freezer}) and renders {@code golden-masters/<state-slug>/<operationId>.json}; then it renders
 * {@code golden-masters/index.json} describing all of them.
 *
 * <p>What an index operation holds, beyond the route:
 *
 * <ul>
 *   <li>{@code query} — the query, apart from the {@code path}, when the call sends one.
 *   <li>{@code contentType} and {@code body} — the request body of a write that takes one: a JSON
 *       value for a JSON media type, a string for any other text. A binary body (a docs bundle) is
 *       {@code bodyBase64} instead. A write that takes no body records none.
 *   <li>{@code file} — only when the answer is a JSON document. A HEAD, a 404 (plain text) and a
 *       docs file (raw bytes) record no file: the status, and {@code headers}, are the answer.
 *   <li>{@code headers} — the response headers consumers read ({@code Docker-Content-Digest},
 *       {@code ETag}, {@code Content-Type}, {@code Content-Length}).
 *   <li>{@code frozen.volatile} — the paths whose value depends on the whole shared store rather
 *       than on the state (the store figures, the GC counts). The recording holds {@code 0} or
 *       {@code ""} there; a consumer matches them by type, as it does every unfrozen leaf.
 * </ul>
 *
 * <p>Every recorded operationId must be declared in the served OpenAPI document under the same
 * method, and the recording sends a body exactly when that operation declares a {@code
 * requestBody}.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** Declared first: {@link #INTERACTIONS} parses the request bodies with it. */
  private static final ObjectMapper JSON = new ObjectMapper();
  static final String PROVIDER = "qits-artifacts";

  /**
   * One recorded interaction. Build it with {@link #op}.
   *
   * @param listFilteredTo the array (a {@code $.a.b} path) reduced to the entries the state created,
   *     or null — the index's {@code frozen.listFilteredTo}
   * @param sortedBy for an array the provider answers in no guaranteed order: {@code
   *     <$.path-to-array>:<field.path in each entry>}, sorted by that field's value before freezing.
   *     Null when the order is the provider's own.
   * @param dropped top-level members removed from the answer: lists whose length and content
   *     depend on what the whole shared store holds, which no consumer reads
   * @param zeroNumbers every number in the answer depends on the whole store: record {@code 0}
   * @param blanked string paths ({@code $.a.b}) that depend on the whole store: record {@code ""}
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      Map<String, String> query,
      String contentType,
      JsonNode jsonBody,
      String textBody,
      byte[] binaryBody,
      int status,
      boolean recordsAnswer,
      List<String> headers,
      String listFilteredTo,
      String sortedBy,
      List<String> dropped,
      boolean zeroNumbers,
      List<String> blanked) {

    boolean sendsBody() {
      return jsonBody != null || textBody != null || binaryBody != null;
    }
  }

  /** A mutable builder of one {@link Interaction}. */
  static final class Op {
    private final String state;
    private final String operationId;
    private final String method;
    private final String path;
    private final int status;
    private final Map<String, String> query = new LinkedHashMap<>();
    private String contentType;
    private JsonNode jsonBody;
    private String textBody;
    private byte[] binaryBody;
    private boolean recordsAnswer;
    private final List<String> headers = new ArrayList<>();
    private String listFilteredTo;
    private final List<String> dropped = new ArrayList<>();
    private boolean zeroNumbers;
    private final List<String> blanked = new ArrayList<>();

    private Op(String state, String operationId, String method, String path, int status) {
      this.state = state;
      this.operationId = operationId;
      this.method = method;
      this.path = path;
      this.status = status;
      // A JSON answer by default; a HEAD and a refusal (plain text) have none.
      this.recordsAnswer = !"HEAD".equals(method) && status < 300;
    }

    Op query(String name, String value) {
      query.put(name, value);
      return this;
    }

    Op json(String mediaType, String body) {
      contentType = mediaType;
      try {
        jsonBody = JSON.readTree(body);
      } catch (IOException e) {
        throw new IllegalArgumentException(body, e);
      }
      return this;
    }

    Op text(String mediaType, String body) {
      contentType = mediaType;
      textBody = body;
      return this;
    }

    Op binary(String mediaType, byte[] body) {
      contentType = mediaType;
      binaryBody = body;
      return this;
    }

    /** The answer is not a JSON document (raw file bytes): record the status and headers only. */
    Op raw() {
      recordsAnswer = false;
      return this;
    }

    Op headers(String... names) {
      headers.addAll(List.of(names));
      return this;
    }

    Op filtered(String array) {
      listFilteredTo = array;
      return this;
    }

    Op drop(String... members) {
      dropped.addAll(List.of(members));
      return this;
    }

    Op zeroNumbers() {
      zeroNumbers = true;
      return this;
    }

    Op blank(String... paths) {
      blanked.addAll(List.of(paths));
      return this;
    }

    Interaction build() {
      return new Interaction(
          state,
          operationId,
          method,
          path,
          Map.copyOf(query),
          contentType,
          jsonBody,
          textBody,
          binaryBody,
          status,
          recordsAnswer,
          List.copyOf(headers),
          listFilteredTo,
          null,
          List.copyOf(dropped),
          zeroNumbers,
          List.copyOf(blanked));
    }
  }

  static Op op(String state, String operationId, String method, String path, int status) {
    return new Op(state, operationId, method, path, status);
  }

  private static final String DAEMON_VERSIONS =
      "/artifacts/api/repositories/{repository}/daemons/{daemon}/versions";
  private static final String DAEMON = "/artifacts/daemons/{daemon}/{version}";
  private static final String SBOM = "/artifacts/sboms/{packageType}/{packageName}/-/{version}";
  private static final String DOCS_SITES = "/artifacts/docs/docs";
  private static final String DOCS_SITE = "/artifacts/docs/docs/{site}";
  private static final String DOCS_VERSION = "/artifacts/docs/docs/{site}/-/{version}";
  private static final String DOCS_FILE = "/artifacts/docs/docs/{site}/-/{version}/{path}";
  private static final String CHANGELOG_SITE = "/artifacts/docs/docs/@changelog/{repository}";
  private static final String CHANGELOG_FILE =
      "/artifacts/docs/docs/@changelog/{repository}/-/{version}/CHANGELOG.md";
  private static final String CONTENT_HASH = "/artifacts/content-hashes/{type}/{name}/-/{version}";
  private static final String NEWEST_CONTENT_HASH =
      "/artifacts/content-hashes/{type}/{name}/-/newest";

  /** The headers a stored binary answers with, which the CLI and the bootstrap read. */
  private static final String[] DIGEST_HEADERS = {
    "Content-Length", "Docker-Content-Digest", "ETag"
  };

  /** The GC answers' members that list the whole store, which no consumer reads. */
  private static final String[] STORE_WIDE_LISTS = {
    "configuration", "repositories", "types", "untouchable"
  };

  /**
   * Every recorded interaction. The consumers each one serves are in {@code
   * /tmp/claude-1000/pact-inventory/round2/qits-artifacts-service-states.md} (qits-1149).
   */
  static final List<Interaction> INTERACTIONS =
      List.of(
          // --- daemons: qits-maintenance (ArtifactPresence), qits-bootstrap, the qits CLI
          op(ProviderStates.A_DAEMON_WITH_TWO_VERSIONS, "listDaemonVersions", "GET", DAEMON_VERSIONS, 200)
              .build(),
          op(ProviderStates.A_DAEMON_WITH_TWO_VERSIONS, "headDaemon", "HEAD", DAEMON, 200)
              .headers(DIGEST_HEADERS)
              .build(),
          op(ProviderStates.A_DAEMON_NOTHING_PUBLISHED, "listDaemonVersions", "GET", DAEMON_VERSIONS, 200)
              .build(),
          op(ProviderStates.A_DAEMON_NOTHING_PUBLISHED, "headDaemon", "HEAD", DAEMON, 404).build(),
          op(ProviderStates.A_DAEMON_NOTHING_PUBLISHED, "putDaemon", "PUT", DAEMON, 201)
              .text("application/octet-stream", ProviderStates.DAEMON_BYTES)
              .build(),
          // --- sboms: the qits CLI, qits-maintenance (SbomClient)
          op(ProviderStates.NO_SBOM, "putSbom", "PUT", SBOM, 201)
              .json(ProviderStates.CYCLONEDX_JSON, ProviderStates.SBOM_DOCUMENT)
              .build(),
          op(ProviderStates.NO_SBOM, "getSbom", "GET", SBOM, 404).build(),
          op(ProviderStates.NO_SBOM, "headSbom", "HEAD", SBOM, 404).build(),
          op(ProviderStates.AN_SBOM, "putSbom", "PUT", SBOM, 200)
              .json(ProviderStates.CYCLONEDX_JSON, ProviderStates.SBOM_DOCUMENT)
              .build(),
          op(ProviderStates.AN_SBOM, "getSbom", "GET", SBOM, 200)
              .headers("Content-Type", "Docker-Content-Digest", "ETag")
              .build(),
          op(ProviderStates.AN_SBOM, "headSbom", "HEAD", SBOM, 200).headers(DIGEST_HEADERS).build(),
          // --- docs: qits-docs (DocsUpstream), the qits CLI
          op(ProviderStates.NO_DOCS, "putDocs", "PUT", DOCS_VERSION, 201)
              .binary("application/gzip", ProviderStates.DOCS_BUNDLE)
              .build(),
          op(ProviderStates.NO_DOCS, "listDocsVersions", "GET", DOCS_SITE, 404).build(),
          op(ProviderStates.NO_DOCS, "getDocsVersion", "GET", DOCS_VERSION, 404).build(),
          op(ProviderStates.NO_DOCS, "getDocsFile", "GET", DOCS_FILE, 404).build(),
          op(ProviderStates.A_DOCS_SITE, "listDocsSites", "GET", DOCS_SITES, 200)
              .filtered("$.sites")
              .build(),
          op(ProviderStates.A_DOCS_SITE, "listDocsVersions", "GET", DOCS_SITE, 200).build(),
          op(ProviderStates.A_DOCS_SITE, "getDocsVersion", "GET", DOCS_VERSION, 200).build(),
          op(ProviderStates.A_DOCS_SITE, "getDocsFile", "GET", DOCS_FILE, 200)
              .raw()
              .headers("Content-Length", "Content-Type", "ETag")
              .build(),
          op(ProviderStates.A_DOCS_SITE_FROM_TWO_BRANCHES, "listDocsVersions", "GET", DOCS_SITE, 200)
              .query("meta.git.branch.name", "main")
              .build(),
          // --- changelogs: qits-maintenance (ChangelogClient), the qits CLI (bump-message)
          op(ProviderStates.CHANGELOGS, "listDocsVersions", "GET", CHANGELOG_SITE, 200).build(),
          op(ProviderStates.CHANGELOGS, "getDocsFile", "GET", CHANGELOG_FILE, 200)
              .raw()
              .headers("Content-Length", "Content-Type", "ETag")
              .build(),
          op(ProviderStates.NO_CHANGELOG, "listDocsVersions", "GET", CHANGELOG_SITE, 404).build(),
          // --- content hashes: the qits CLI
          op(ProviderStates.A_CONTENT_HASH, "getContentHash", "GET", CONTENT_HASH, 200).build(),
          op(ProviderStates.A_CONTENT_HASH, "getNewestContentHash", "GET", NEWEST_CONTENT_HASH, 200)
              .build(),
          // --- the store and its GC: qits-orchestrator
          op(ProviderStates.A_STORE_WITH_CONTENT, "getStoreSummary", "GET", "/artifacts/api/store/summary", 200)
              .zeroNumbers()
              .build(),
          op(ProviderStates.UNPINNED_IDENTITIES, "planGcWithSuppliedPins", "POST", "/artifacts/api/gc/plan", 200)
              .json("application/json", ProviderStates.GC_PINS)
              .drop(STORE_WIDE_LISTS)
              .drop("sweep")
              .zeroNumbers()
              .blank("$.summary.headline", "$.summary.reclaimable", "$.summary.types[*]")
              .build(),
          op(ProviderStates.UNPINNED_IDENTITIES, "sweepGc", "POST", "/artifacts/api/gc/sweep", 200)
              .json("application/json", ProviderStates.GC_PINS)
              .drop(STORE_WIDE_LISTS)
              .zeroNumbers()
              .build());

  private static final Pattern TEMPLATE_PARAM = Pattern.compile("\\{([^}]+)}");

  @Inject ProviderStates states;

  @Test
  void goldenMastersMatchTheProvider() throws IOException {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();
    Map<String, Map<String, Boolean>> declared = declaredOperations();

    // slug -> recorded state, sorted by slug; operations sorted by operationId below
    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    for (Interaction interaction : INTERACTIONS) {
      checkDeclared(interaction, declared, failures);

      Recorded recorded = record(interaction);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", recorded.params());
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(recorded.params())) {
        failures.add("State '" + interaction.state() + "' froze to different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      operation.put("path", interaction.path());
      if (!interaction.query().isEmpty()) {
        ObjectNode query = operation.putObject("query");
        new TreeMap<>(interaction.query()).forEach(query::put);
      }
      if (interaction.sendsBody()) {
        operation.put("contentType", interaction.contentType());
        if (interaction.jsonBody() != null) {
          operation.set("body", interaction.jsonBody());
        } else if (interaction.textBody() != null) {
          operation.put("body", interaction.textBody());
        } else {
          operation.put(
              "bodyBase64", Base64.getEncoder().encodeToString(interaction.binaryBody()));
        }
      }
      operation.put("status", interaction.status());
      if (interaction.recordsAnswer()) {
        operation.put("file", file);
      }
      if (!recorded.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        recorded.headers().forEach(headers::put);
      }
      ObjectNode frozen = operation.putObject("frozen");
      frozen.set("ids", strings(recorded.freezer().idPaths()));
      frozen.set("instants", strings(recorded.freezer().instantPaths()));
      frozen.set("strings", strings(recorded.freezer().stringPaths()));
      if (!recorded.volatilePaths().isEmpty()) {
        frozen.set("volatile", strings(recorded.volatilePaths()));
      }
      if (interaction.listFilteredTo() == null) {
        frozen.putNull("listFilteredTo");
      } else {
        frozen.put("listFilteredTo", interaction.listFilteredTo());
      }
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      if (interaction.recordsAnswer()) {
        written.add(file);
        check(dir.resolve(file), GoldenJson.render(recorded.body()), update, failures);
      }
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /**
   * The served document's operations: operationId → method (lower case) → whether it declares a
   * request body.
   */
  private static Map<String, Map<String, Boolean>> declaredOperations() throws IOException {
    JsonNode document =
        JSON.readTree(
            given().queryParam("format", "json").get("/artifacts/q/openapi").then().statusCode(200)
                .extract().asString());
    Map<String, Map<String, Boolean>> declared = new HashMap<>();
    document
        .path("paths")
        .properties()
        .forEach(
            path ->
                path.getValue()
                    .properties()
                    .forEach(
                        method -> {
                          JsonNode operation = method.getValue();
                          if (operation.has("operationId")) {
                            declared
                                .computeIfAbsent(
                                    operation.get("operationId").asText(), k -> new HashMap<>())
                                .put(method.getKey(), operation.has("requestBody"));
                          }
                        }));
    return declared;
  }

  private static void checkDeclared(
      Interaction interaction, Map<String, Map<String, Boolean>> declared, List<String> failures) {
    Map<String, Boolean> methods = declared.get(interaction.operationId());
    String method = interaction.method().toLowerCase(Locale.ROOT);
    if (methods == null || !methods.containsKey(method)) {
      failures.add(
          interaction.method()
              + " "
              + interaction.operationId()
              + " is not declared in the served OpenAPI document (/artifacts/q/openapi).");
      return;
    }
    if (methods.get(method) != interaction.sendsBody()) {
      failures.add(
          interaction.operationId()
              + (interaction.sendsBody()
                  ? " takes no request body, but the recording sends one."
                  : " takes a request body, but the recording sends none."));
    }
  }

  /**
   * One interaction's frozen answer (null when it records none), its frozen params, what was frozen
   * where, the response headers it records and the paths it made store-independent.
   */
  record Recorded(
      JsonNode body,
      ObjectNode params,
      Freezer freezer,
      Map<String, String> headers,
      List<String> volatilePaths) {}

  private Recorded record(Interaction interaction) throws IOException {
    ProviderStates.Setup setup = states.setUp(interaction.state());
    try {
      return recordIn(interaction, setup);
    } finally {
      states.cleanUp();
    }
  }

  private Recorded recordIn(Interaction interaction, ProviderStates.Setup setup) throws IOException {
    Map<String, String> params = setup.params();

    RequestSpecification request = given().urlEncodingEnabled(false).queryParams(interaction.query());
    if (interaction.jsonBody() != null) {
      request =
          request
              .contentType(interaction.contentType())
              .body(interaction.jsonBody().toString().getBytes(StandardCharsets.UTF_8));
    } else if (interaction.textBody() != null) {
      request =
          request
              .contentType(interaction.contentType())
              .body(interaction.textBody().getBytes(StandardCharsets.UTF_8));
    } else if (interaction.binaryBody() != null) {
      request = request.contentType(interaction.contentType()).body(interaction.binaryBody());
    } else {
      // As a browser sends a body-less call: RestAssured would otherwise add a form content type.
      request = request.noContentType();
    }
    Response response =
        request.when().request(interaction.method(), expand(interaction.path(), params));
    String raw = response.asString();
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + interaction.path()
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + raw);
    }

    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : interaction.headers()) {
      String value = response.getHeader(name);
      if (value == null) {
        throw new AssertionError(
            interaction.operationId() + " in state '" + interaction.state() + "' sent no " + name);
      }
      headers.put(name, value);
    }

    Freezer freezer = new Freezer().seed(params.values()).uniqueTokens(setup.uniqueTokens());
    ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
    params.forEach((k, v) -> frozenParams.put(k, freezer.freezeParam(v)));

    if (!interaction.recordsAnswer()) {
      return new Recorded(null, frozenParams, freezer, headers, List.of());
    }
    JsonNode body = JSON.readTree(raw);
    if (body.isObject()) {
      interaction.dropped().forEach(((ObjectNode) body)::remove);
    }
    body = recordable(body, interaction, params.values(), setup.uniqueTokens());
    List<String> volatilePaths = new ArrayList<>();
    body = storeIndependent(body, interaction, volatilePaths);
    return new Recorded(freezer.freeze(body), frozenParams, freezer, headers, volatilePaths);
  }

  /**
   * The answer reduced to what the state controls: the {@code listFilteredTo} array keeps only the
   * entries mentioning an id the state created (its param values), and a {@code sortedBy} array is
   * put in seed order — by the field's value with the state's unique tokens blanked out, since a
   * random token would otherwise decide where its entry sorts. Package-private for the machinery
   * test.
   */
  static JsonNode recordable(
      JsonNode body,
      Interaction interaction,
      Collection<String> createdIds,
      Collection<String> uniqueTokens) {
    JsonNode out = body.deepCopy();
    if (interaction.listFilteredTo() != null) {
      ArrayNode list = array(out, interaction.listFilteredTo());
      ArrayNode kept = JsonNodeFactory.instance.arrayNode();
      for (JsonNode entry : list) {
        String text = entry.toString();
        if (createdIds.stream().anyMatch(text::contains)) {
          kept.add(entry);
        }
      }
      list.removeAll();
      list.addAll(kept);
    }
    if (interaction.sortedBy() != null) {
      String[] parts = interaction.sortedBy().split(":", 2);
      ArrayNode list = array(out, parts[0]);
      List<JsonNode> entries = new ArrayList<>();
      list.forEach(entries::add);
      String[] field = parts[1].split("\\.");
      entries.sort(
          Comparator.comparing(
              entry -> {
                JsonNode node = entry;
                for (String f : field) {
                  node = node.path(f);
                }
                String key = node.asText();
                for (String token : uniqueTokens) {
                  key = key.replace(token, "");
                }
                return key;
              }));
      list.removeAll();
      list.addAll(entries);
    }
    return out;
  }

  /**
   * The answer with what depends on the whole shared store replaced: every number by {@code 0}
   * ({@code zeroNumbers}) and the {@code blanked} strings by {@code ""}. Each changed path is added
   * to {@code changed}, de-duplicated, {@code [*]} for array elements.
   */
  static JsonNode storeIndependent(JsonNode body, Interaction interaction, List<String> changed) {
    Set<String> paths = new LinkedHashSet<>();
    JsonNode out = body.deepCopy();
    for (String path : interaction.blanked()) {
      if (blank(out, path.substring(2).split("\\."), 0) == 0) {
        throw new IllegalStateException(path + " names no string in " + body);
      }
      paths.add(path);
    }
    if (interaction.zeroNumbers()) {
      out = zero(out, "$", paths);
    }
    changed.addAll(paths);
    return out;
  }

  /**
   * Sets the strings at {@code segments} (from {@code at} on) to {@code ""}; a segment ending in
   * {@code [*]} names every element of that array. Answers how many it set.
   */
  private static int blank(JsonNode node, String[] segments, int at) {
    String segment = segments[at];
    boolean each = segment.endsWith("[*]");
    String key = each ? segment.substring(0, segment.length() - 3) : segment;
    if (!(node instanceof ObjectNode object) || !object.has(key)) {
      return 0;
    }
    JsonNode child = object.get(key);
    boolean last = at == segments.length - 1;
    if (!each) {
      if (!last) {
        return blank(child, segments, at + 1);
      }
      if (!child.isTextual()) {
        return 0;
      }
      object.set(key, TextNode.valueOf(""));
      return 1;
    }
    if (!(child instanceof ArrayNode array)) {
      return 0;
    }
    int count = 0;
    for (int i = 0; i < array.size(); i++) {
      if (!last) {
        count += blank(array.get(i), segments, at + 1);
      } else if (array.get(i).isTextual()) {
        array.set(i, TextNode.valueOf(""));
        count++;
      }
    }
    return count;
  }

  private static JsonNode zero(JsonNode node, String path, Set<String> paths) {
    if (node.isNumber()) {
      paths.add(path);
      return IntNode.valueOf(0);
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      node.forEach(element -> out.add(zero(element, path + "[*]", paths)));
      return out;
    }
    if (node.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        out.set(field.getKey(), zero(field.getValue(), path + "." + field.getKey(), paths));
      }
      return out;
    }
    return node;
  }

  /** The array at a {@code $.a.b} path — the only JSONPath shape the table uses. */
  private static ArrayNode array(JsonNode root, String path) {
    if (!path.startsWith("$.")) {
      throw new IllegalArgumentException("Only $.a.b paths are supported: " + path);
    }
    JsonNode node = root;
    for (String segment : path.substring(2).split("\\.")) {
      node = node.path(segment);
    }
    if (!node.isArray()) {
      throw new IllegalStateException(path + " is not an array in " + root);
    }
    return (ArrayNode) node;
  }

  private static String expand(String template, Map<String, String> params) {
    Matcher m = TEMPLATE_PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = params.get(m.group(1));
      if (value == null) {
        throw new IllegalStateException(
            "Path " + template + " names {" + m.group(1) + "}, which the state does not return");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    values.forEach(out::add);
    return out;
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
