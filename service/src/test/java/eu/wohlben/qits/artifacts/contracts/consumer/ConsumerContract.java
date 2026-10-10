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
 * <p><b>Seven calls, seven providers, one row each.</b> Six are the GC pin readers in the {@code gc}
 * module, one GET each, made by {@code GcPinSources.fetch()} at the start of every plan and sweep;
 * the seventh is {@code RegistryTokenEndpoint}'s relay of a docker login to the idp's token door.
 *
 * <p><b>One trigger per GC row, not five.</b> The five GC routes ({@code GET /gc/plan}, {@code GET
 * /gc/repositories}, {@code GET /gc/repositories/{repository}/plan}, and the two sweeps when no
 * pins are supplied) all reach the identical {@code GcPinSources.fetch()}, so the rows name the
 * plan route that the others are built on. A call with pins supplied in the body makes no request.
 *
 * <p><b>No provider records these answers yet.</b> Each row names the provider state and the
 * operationId it needs; the tests that run the rows are {@code @Disabled} with that reason, and
 * the pact files are written the day the provider's golden masters are pinned here.
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
  public static final Provider CONFIGURATION =
      new Provider("qits-configuration", "qits-configuration-service");
  public static final Provider WORKSPACES =
      new Provider("qits-workspaces", "qits-workspaces-service");
  public static final Provider PROJECTS = new Provider("qits-projects", "qits-projects-service");
  public static final Provider IDP = new Provider("qits-idp", "qits-idp-service");

  /**
   * What made this service make the call — the {@code qits-trigger} reference. Always this
   * service's own operation here: no reader runs on a schedule or an event.
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
   */
  public record Row(
      Provider provider,
      Trigger trigger,
      String state,
      String operationId,
      List<String> consumes,
      Request request,
      Function<String, Object> read,
      String readAt) {

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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final List<Row> ROWS =
      List.of(
          new Row(
              DEPLOYMENTS,
              GC_PLAN,
              "deployments that pin images",
              "listDeploymentPins",
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
              CONFIGURATION,
              GC_PLAN,
              "configured image pins",
              "listImagePins",
              List.of(
                  "$.pins[*].image",
                  "$.pins[*].version",
                  "$.pins[*].application",
                  "$.pins[*].key"),
              Request.NONE,
              base -> ContractPinReaders.configuration(base, MAPPER).pins(),
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
              "configured agent images",
              "listLaunchPins",
              List.of("$.pins[*].image", "$.pins[*].version", "$.pins[*].launches"),
              Request.NONE,
              base -> ContractPinReaders.projects(base, MAPPER).pins(),
              "$.pins"),
          // The relay forwards the caller's Basic header verbatim and asks for client_credentials
          // with no audience. The credential is the provider state's: `basicCredential` is the
          // base64 of a client id and secret the state registers.
          new Row(
              IDP,
              REGISTRY_TOKEN,
              "a registered machine client",
              "issueToken",
              List.of("$.access_token", "$.expires_in"),
              (request, params) ->
                  request
                      .header(
                          "Authorization",
                          Matchers.fromProviderState(
                              "Basic ${basicCredential}", "Basic " + params.get("basicCredential")))
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .header("Accept", "application/json")
                      .body("grant_type=client_credentials", "application/x-www-form-urlencoded"),
              null,
              "$"));

  private ConsumerContract() {}

  /** The rows made against one provider, in table order. */
  public static List<Row> rows(Provider provider) {
    return ROWS.stream().filter(r -> r.provider().equals(provider)).toList();
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
