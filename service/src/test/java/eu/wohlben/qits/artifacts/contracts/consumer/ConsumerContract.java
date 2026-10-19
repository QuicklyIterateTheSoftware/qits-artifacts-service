package eu.wohlben.qits.artifacts.contracts.consumer;

import au.com.dius.pact.consumer.dsl.HttpRequestBuilder;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.gc.ContractPinReaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * <b>Every REST call qits-artifacts-service makes to another qits service, and what it reads from
 * each answer</b> (qits-1149) — the one table the consumer pact tests and the pact file tests are
 * built from, so a committed pact and the verified behaviour cannot drift apart.
 *
 * <p><b>Seven calls, six providers.</b> Five are the GC pin readers in the {@code gc} module, one
 * GET each, made by {@code GcPinSources.fetch()} at the start of every plan and sweep; the sixth is
 * {@code RegistryTokenEndpoint}'s relay of a docker login to the idp's token door; the seventh is
 * the quarkus-oidc tenant's read of the idp's signing keys. {@link #CLIENTS} names the class that
 * makes each, and {@code OutboundCallsGuardTest} holds that list to the code.
 *
 * <p><b>One trigger per GC row, not five.</b> The five GC routes ({@code GET /gc/plan}, {@code GET
 * /gc/repositories}, {@code GET /gc/repositories/{repository}/plan}, and the two sweeps when no
 * pins are supplied) all reach the identical {@code GcPinSources.fetch()}, so the rows name the
 * plan route that the others are built on. A call with pins supplied in the body makes no request.
 *
 * <p><b>Every row is bound to a recording</b>: the provider's {@code <app>-golden-masters}, pinned
 * in the pom, records the state and the operationId each row names. A row whose state the pinned
 * recording does not hold yet is {@link Row#pending() pending}: it stays in the table, naming what
 * it waits for, but no pact holds it and no test runs it, and {@code PendingRowsTest} fails the day
 * the pinned recording gains that state, so it is switched on rather than forgotten.
 */
public final class ConsumerContract {

  /** One provider: as its golden-master index names it, and as a pact names it. */
  public record Provider(String application, String repository) {

    /** The pact file for this provider, under {@code pacts/}. */
    public String pactFile() {
      return ProviderGoldenMasters.CONSUMER + "_" + repository + ".json";
    }
  }

  public static final Provider DEPLOYMENTS =
      new Provider("qits-deployments", "qits-deployments-service");
  public static final Provider CI = new Provider("qits-ci", "qits-ci-service");
  public static final Provider MAINTENANCE =
      new Provider("qits-maintenance", "qits-maintenance-service");
  public static final Provider WORKSPACES =
      new Provider("qits-workspaces", "qits-workspaces-service");
  public static final Provider PROJECTS = new Provider("qits-projects", "qits-projects-service");
  public static final Provider IDP = new Provider("qits-idp", "qits-idp-service");

  /**
   * What made this service make the call — the {@code qits-trigger} reference: this service's own
   * operation, or (the quarkus-oidc key read) the startup event.
   */
  public record Trigger(String kind, String app, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(app, "app");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger operation(String operationId) {
      return new Trigger("operation", ProviderGoldenMasters.CONSUMER, "operationId", operationId);
    }

    /** A call made when the application observes {@code event}, not on a request. */
    public static Trigger event(String event) {
      return new Trigger("event", ProviderGoldenMasters.CONSUMER, "event", event);
    }

    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", app);
      ref.put(key, value);
      return ref;
    }
  }

  /** The request headers and body a row sends, beyond method and path. */
  @FunctionalInterface
  public interface Request extends BiConsumer<HttpRequestBuilder, Map<String, String>> {
    Request NONE = (request, params) -> {};
  }

  /**
   * One (trigger, call).
   *
   * @param consumes the body paths the reader reads; the pact binds these and nothing else
   * @param read runs the real reader against a base url and returns what it made of the answer,
   *     for the consumer test to compare with the recording; null when the call cannot run outside
   *     the application (the token relay, see {@code IdpConsumerPactTest})
   * @param readAt where in the recording {@code read}'s result sits: {@code $} or {@code $.pins}
   * @param pending null for a live row; otherwise why it waits — the provider records the state
   *     on a branch that has not released, so the pinned golden masters do not hold it yet
   */
  public record Row(
      Provider provider,
      Trigger trigger,
      String state,
      String operationId,
      List<String> consumes,
      Request request,
      Function<String, Object> read,
      String readAt,
      String pending) {

    Row(
        Provider provider,
        Trigger trigger,
        String state,
        String operationId,
        List<String> consumes,
        Request request,
        Function<String, Object> read,
        String readAt) {
      this(provider, trigger, state, operationId, consumes, request, read, readAt, null);
    }

    /** Whether the row waits for its provider to publish the state it names. */
    public boolean isPending() {
      return pending != null;
    }

    /** The trigger first, so (description, state) stays unique. */
    public String description() {
      return trigger.value() + ": " + operationId;
    }

    /** Why the row cannot run yet: the provider has not recorded the state it needs. */
    public String waitingFor() {
      return "needs provider state '" + state + "' for " + operationId + " in "
          + provider.repository();
    }
  }

  // --- the GC pin readers -------------------------------------------------------------------------

  /** The consumer operation every GC row is made from: the plan, which the other routes build on. */
  static final Trigger GC_PLAN = Trigger.operation("getGcPlan");

  /** {@code RegistryTokenEndpoint}: a docker client trading its stored login for a bearer. */
  static final Trigger REGISTRY_TOKEN = Trigger.operation("getRegistryToken");

  /**
   * The quarkus-oidc tenant: it reads the idp's JWKS when it starts (discovery is off, so the key
   * set is the one document), and again when a token names a key it does not hold.
   */
  static final Trigger STARTUP = Trigger.event("StartupEvent");

  /**
   * The class that makes each outbound call, and the provider it calls. {@code
   * OutboundCallsGuardTest} derives the classes that hold an outbound client from the code and
   * fails when one is missing here, and fails when a provider here has no row. The quarkus-oidc
   * tenant is no class of this service's; the guard derives it from the configuration instead.
   */
  public static final Map<String, Provider> CLIENTS =
      Map.of(
          "eu.wohlben.qits.artifacts.gc.CdHttpDeploymentPins", DEPLOYMENTS,
          "eu.wohlben.qits.artifacts.gc.CiHttpDaemonPins", CI,
          "eu.wohlben.qits.artifacts.gc.MaintenanceHttpDependencyPins", MAINTENANCE,
          "eu.wohlben.qits.artifacts.gc.WorkspacesHttpLaunchPins", WORKSPACES,
          "eu.wohlben.qits.artifacts.gc.ProjectsHttpLaunchPins", PROJECTS,
          "eu.wohlben.qits.artifacts.api.RegistryTokenEndpoint", IDP);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** What the relay sends the idp's token door, apart from the path. */
  private static final Request IDP_TOKEN_REQUEST =
      (request, params) ->
          request
              .header(
                  "Authorization",
                  Matchers.fromProviderState("${authorization}", params.get("authorization")))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("Accept", "application/json")
              .body("grant_type=client_credentials", "application/x-www-form-urlencoded");

  static final List<Row> ROWS =
      List.of(
          new Row(
              DEPLOYMENTS,
              GC_PLAN,
              "an application deployed in an environment",
              "listPins",
              List.of("$.pins[*].applicationName", "$.pins[*].shas[*]"),
              Request.NONE,
              base -> ContractPinReaders.deployments(base, MAPPER).pins(),
              "$.pins"),
          new Row(
              CI,
              GC_PLAN,
              "a pinned daemon",
              "getDaemonPin",
              List.of("$.daemonName", "$.daemonVersion", "$.previousDaemonVersion", "$.source"),
              Request.NONE,
              base -> ContractPinReaders.ci(base, MAPPER).daemonPin(),
              "$"),
          new Row(
              MAINTENANCE,
              GC_PLAN,
              "manifests that pin artifacts",
              "listDependencyPins",
              List.of(
                  "$.pins[*].ecosystem",
                  "$.pins[*].name",
                  "$.pins[*].version",
                  "$.pins[*].repository",
                  "$.pins[*].manifestPath"),
              Request.NONE,
              base -> ContractPinReaders.maintenance(base, MAPPER).pins(),
              "$.pins"),
          new Row(
              WORKSPACES,
              GC_PLAN,
              "a configured workspace image",
              "listLaunchPins",
              List.of("$.pins[*].image", "$.pins[*].version", "$.pins[*].launches"),
              Request.NONE,
              base -> ContractPinReaders.workspaces(base, MAPPER).pins(),
              "$.pins"),
          new Row(
              PROJECTS,
              GC_PLAN,
              "an agent launch image in use",
              "listLaunchPins",
              List.of("$.pins[*].image", "$.pins[*].version", "$.pins[*].launches"),
              Request.NONE,
              base -> ContractPinReaders.projects(base, MAPPER).pins(),
              "$.pins"),
          // The relay forwards the caller's Basic header verbatim and asks for client_credentials
          // with no audience. The credential is the provider state's: `authorization` is the whole
          // `Basic <base64 id:secret>` value of a client the state registers.
          new Row(
              IDP,
              REGISTRY_TOKEN,
              "a service client with the system role",
              "issueToken",
              List.of("$.access_token", "$.expires_in"),
              IDP_TOKEN_REQUEST,
              null,
              "$"),
          // The tenant builds an RSA public key from each entry; kid picks the key for a token.
          new Row(
              IDP,
              STARTUP,
              "a published signing key",
              "getJwks",
              List.of(
                  "$.keys[*].kid",
                  "$.keys[*].kty",
                  "$.keys[*].alg",
                  "$.keys[*].use",
                  "$.keys[*].n",
                  "$.keys[*].e"),
              Request.NONE,
              base -> SigningKeys.read(base + "/idp/jwks"),
              "$.keys"),
          // A refused credential: idp answers 401, and the relay hands the docker client a 401 of
          // its own and mints nothing. A status, so it reads no body.
          new Row(
              IDP,
              REGISTRY_TOKEN,
              "a service client presenting a wrong secret",
              "issueToken",
              List.of(),
              IDP_TOKEN_REQUEST,
              null,
              "$"));

  private ConsumerContract() {}

  /** The live rows made against one provider, in table order: the ones its pact holds. */
  public static List<Row> rows(Provider provider) {
    return ROWS.stream().filter(r -> r.provider().equals(provider) && !r.isPending()).toList();
  }

  /** Every pending row, against any provider. */
  public static List<Row> pending() {
    return ROWS.stream().filter(Row::isPending).toList();
  }

  /** Every provider the table names, in table order. */
  public static List<Provider> providers() {
    return ROWS.stream().map(Row::provider).distinct().toList();
  }

  /** The whole pact against one provider. Needs that provider's golden masters on the classpath. */
  public static V4Pact pact(Provider provider) {
    return pact(provider, rows(provider));
  }

  /** A pact holding only {@code rows}, all against {@code provider} — one mock server per row. */
  public static V4Pact pact(Provider provider, List<Row> rows) {
    ProviderGoldenMasters masters =
        ProviderGoldenMasters.of(provider.application(), provider.repository());
    PactBuilder builder =
        new PactBuilder(ProviderGoldenMasters.CONSUMER, provider.repository(), PactSpecVersion.V4);
    for (Row row : rows) {
      masters.interaction(builder, row);
    }
    return builder.toPact();
  }
}
