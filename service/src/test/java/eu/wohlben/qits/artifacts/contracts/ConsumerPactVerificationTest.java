package eu.wohlben.qits.artifacts.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.ProviderResponse;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.hc.core5.http.HttpRequest;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running provider</b> (qits-1149), the way
 * qits-projects-service does; a copy of qits-events-service's.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-artifacts-service.json} (repository names on both sides), this repo
 * pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer releases
 * a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>No consumer pins a pact yet</b>, so {@code @IgnoreNoPactsToVerify} lets an empty classpath
 * pass and the loader logs that nothing was verified. When the first consumer's pact jar is pinned,
 * drop the annotation and set {@link ClasspathPactLoader#REQUIRED} to true.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-artifacts-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // no pact to verify: @IgnoreNoPactsToVerify's single empty run
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-artifacts does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new RawPathTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  /**
   * The target, sending {@code :} and {@code @} in the path raw, as the consumers' own clients send
   * them.
   *
   * <p>pact-jvm percent-encodes both, and the Vert.x wires match the raw path: {@code %3A} in a
   * maven name ({@code eu.wohlben.qits:x}) or {@code %40} in {@code @changelog/<repository>} would
   * be a 404 no real caller ever gets. Both characters are legal in a path segment (RFC 3986 pchar),
   * and java.net.http, curl and the browsers send them unencoded.
   */
  static final class RawPathTarget extends HttpTestTarget {

    RawPathTarget(String host, int port) {
      super(host, port);
    }

    @Override
    public ProviderResponse executeInteraction(Object client, Object request) {
      if (request instanceof HttpRequest http) {
        http.setPath(
            http.getPath().replace("%3A", ":").replace("%3a", ":").replace("%40", "@"));
      }
      return super.executeInteraction(client, request);
    }
  }

  // --- the states: each one line into the registry -------------------------------------------

  @AfterEach
  void cleanUp() {
    states.cleanUp();
  }

  @State(ProviderStates.A_DAEMON_WITH_TWO_VERSIONS)
  Map<String, String> aDaemonWithTwoVersions() {
    return states.params(ProviderStates.A_DAEMON_WITH_TWO_VERSIONS);
  }

  @State(ProviderStates.A_DAEMON_NOTHING_PUBLISHED)
  Map<String, String> aDaemonNothingPublished() {
    return states.params(ProviderStates.A_DAEMON_NOTHING_PUBLISHED);
  }

  @State(ProviderStates.NO_SBOM)
  Map<String, String> noSbom() {
    return states.params(ProviderStates.NO_SBOM);
  }

  @State(ProviderStates.AN_SBOM)
  Map<String, String> anSbom() {
    return states.params(ProviderStates.AN_SBOM);
  }

  @State(ProviderStates.NO_DOCS)
  Map<String, String> noDocs() {
    return states.params(ProviderStates.NO_DOCS);
  }

  @State(ProviderStates.A_DOCS_SITE)
  Map<String, String> aDocsSite() {
    return states.params(ProviderStates.A_DOCS_SITE);
  }

  @State(ProviderStates.A_DOCS_SITE_FROM_TWO_BRANCHES)
  Map<String, String> aDocsSiteFromTwoBranches() {
    return states.params(ProviderStates.A_DOCS_SITE_FROM_TWO_BRANCHES);
  }

  @State(ProviderStates.CHANGELOGS)
  Map<String, String> changelogs() {
    return states.params(ProviderStates.CHANGELOGS);
  }

  @State(ProviderStates.NO_CHANGELOG)
  Map<String, String> noChangelog() {
    return states.params(ProviderStates.NO_CHANGELOG);
  }

  @State(ProviderStates.A_CONTENT_HASH)
  Map<String, String> aContentHash() {
    return states.params(ProviderStates.A_CONTENT_HASH);
  }

  @State(ProviderStates.A_STORE_WITH_CONTENT)
  Map<String, String> aStoreWithContent() {
    return states.params(ProviderStates.A_STORE_WITH_CONTENT);
  }

  @State(ProviderStates.UNPINNED_IDENTITIES)
  Map<String, String> unpinnedIdentities() {
    return states.params(ProviderStates.UNPINNED_IDENTITIES);
  }
}
