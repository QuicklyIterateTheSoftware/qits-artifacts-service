package eu.wohlben.qits.artifacts.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.ProviderResponse;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import eu.wohlben.qits.SuiteBearers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.hc.core5.http.HttpRequest;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * <p><b>The pinned pacts</b> are in {@code service/pom.xml}: qits-docs-service's (its {@code
 * DocsUpstream} reads of the docs sites), qits-ci-service's ({@code HttpArtifactPresence}'s
 * newest-content-hash read of a release), qits-bootstrap-cli's, qits-maintenance-service's,
 * qits-orchestrator-service's (the scheduled GC), qits-platform-access-cli's,
 * qits-deployments-service's and qits-landing-app's (the UI builder's reads of its routes
 * bundle, qits-1951). {@link
 * ClasspathPactLoader#REQUIRED} is true, so a classpath that lost them fails rather than verifying
 * nothing. A newly published consumer pact against this provider is pinned there the same way.
 *
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>, and
 * its interaction is reported as skipped with the reason — never answered by bending the provider
 * to it. The entry is for that version alone: the bump that carries the consumer's fix verifies the
 * interaction for real, and a bump that does not fix it goes red here, which is the point.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} synthetic user — exactly as {@link GoldenMasterRecordingTest}'s calls run. Every
 * {@code @State} method delegates to {@link ProviderStates}; {@link #target} fails an unknown state,
 * and an interaction without {@code comments.references.qits-call} or {@code qits-trigger}.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-artifacts-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /**
   * A pinned pact interaction this provider cannot honour, and why: the consumer must fix it. The
   * state is part of the name, since one description often covers both a found and a missing case.
   */
  record KnownWrong(
      String consumer, String version, String description, String state, String reason) {

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && description.equals(interaction.getDescription())
          && interaction.getProviderStates().stream().anyMatch(s -> state.equals(s.getName()))
          && String.valueOf(pact.getSource()).contains("/" + version + "/");
    }
  }

  static final List<KnownWrong> KNOWN_WRONG = List.of();

  @Inject ProviderStates states;

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
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
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
    context.setTarget(new CallerTarget(base.getHost(), base.getPort(), consumer));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  /**
   * The target, naming the caller of a bearer-less interaction.
   *
   * <p>The path goes out as pact-jvm writes it, {@code :} and {@code @} percent-encoded. Until
   * qits-2409 this target turned {@code %3A} and {@code %40} back into {@code :} and {@code @},
   * because the Vert.x wires matched only the literal spelling. That rewrite hid the very request
   * qits-landing-app's generated client sends ({@code eu.wohlben.qits%3Ax}, {@code %40qits%2Fx}),
   * so a 404 no pact caught reached the live store. The wires now take both spellings; the
   * literal one is pinned by the route tests ({@code SbomRegistryTest}, {@code DocsRegistryTest},
   * {@code ContentHashRoutesTest}), and the encoded one by this verification.
   */
  static final class CallerTarget extends HttpTestTarget {

    private final String consumer;

    CallerTarget(String host, int port, String consumer) {
      super(host, port);
      this.consumer = consumer;
    }

    @Override
    public ProviderResponse executeInteraction(Object client, Object request) {
      if (request instanceof HttpRequest http) {
        if (!http.containsHeader("Authorization")) {
          // An interaction without a bearer names no caller of its own, so the suite names the one
          // the consumer is (epic qits-543, qits-1358) rather than leaning on a forward-auth dev
          // user: a platform service sends its own qits:system bearer, anything else — the CLI,
          // the bootstrap — a signed-in operator's. The open wires never ask for either.
          http.setHeader("Authorization", callerOf(consumer));
        }
      }
      return super.executeInteraction(client, request);
    }
  }

  /** The bearer a consumer's bearer-less interaction stands for, by what kind of consumer it is. */
  static String callerOf(String consumer) {
    return consumer.endsWith("-service")
        ? SuiteBearers.bearer(consumer, "qits:system")
        : SuiteBearers.operator();
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

  @State(ProviderStates.A_DOCS_SITE_WITH_A_CONTENT_HASH)
  Map<String, String> aDocsSiteWithAContentHash() {
    return states.params(ProviderStates.A_DOCS_SITE_WITH_A_CONTENT_HASH);
  }

  @State(ProviderStates.A_DOCS_CATALOG_OF_EVERY_KIND)
  Map<String, String> aDocsCatalogOfEveryKind() {
    return states.params(ProviderStates.A_DOCS_CATALOG_OF_EVERY_KIND);
  }

  @State(ProviderStates.AN_EMPTY_DOCS_CATALOG)
  Map<String, String> anEmptyDocsCatalog() {
    return states.params(ProviderStates.AN_EMPTY_DOCS_CATALOG);
  }

  @State(ProviderStates.A_DOCS_CATALOG_NAMING_CONTRACT_REPOSITORIES)
  Map<String, String> aDocsCatalogNamingContractRepositories() {
    return states.params(ProviderStates.A_DOCS_CATALOG_NAMING_CONTRACT_REPOSITORIES);
  }

  @State(ProviderStates.A_MARKDOWN_DOCS_BUNDLE)
  Map<String, String> aMarkdownDocsBundle() {
    return states.params(ProviderStates.A_MARKDOWN_DOCS_BUNDLE);
  }

  @State(ProviderStates.A_MARKDOWN_DOCS_BUNDLE_IMAGE)
  Map<String, String> aMarkdownDocsBundleImage() {
    return states.params(ProviderStates.A_MARKDOWN_DOCS_BUNDLE_IMAGE);
  }

  @State(ProviderStates.AN_APIDOCS_BUNDLE)
  Map<String, String> anApidocsBundle() {
    return states.params(ProviderStates.AN_APIDOCS_BUNDLE);
  }

  @State(ProviderStates.A_ROUTES_BUNDLE)
  Map<String, String> aRoutesBundle() {
    return states.params(ProviderStates.A_ROUTES_BUNDLE);
  }

  @State(ProviderStates.A_ROUTES_BUNDLE_FREEZE)
  Map<String, String> aRoutesBundleFreeze() {
    return states.params(ProviderStates.A_ROUTES_BUNDLE_FREEZE);
  }

  @State(ProviderStates.A_ROUTES_BUNDLE_FONT)
  Map<String, String> aRoutesBundleFont() {
    return states.params(ProviderStates.A_ROUTES_BUNDLE_FONT);
  }

  @State(ProviderStates.A_ROUTES_BUNDLE_WITH_VIEWPORTS)
  Map<String, String> aRoutesBundleWithViewports() {
    return states.params(ProviderStates.A_ROUTES_BUNDLE_WITH_VIEWPORTS);
  }

  @State(ProviderStates.A_CONTRACTS_BUNDLE)
  Map<String, String> aContractsBundle() {
    return states.params(ProviderStates.A_CONTRACTS_BUNDLE);
  }

  @State(ProviderStates.A_CONTRACTS_BUNDLE_WITH_AN_INDEX)
  Map<String, String> aContractsBundleWithAnIndex() {
    return states.params(ProviderStates.A_CONTRACTS_BUNDLE_WITH_AN_INDEX);
  }

  @State(ProviderStates.A_CONTRACTS_BUNDLE_A_DEPENDENCY_POINTS_INTO)
  Map<String, String> aContractsBundleADependencyPointsInto() {
    return states.params(ProviderStates.A_CONTRACTS_BUNDLE_A_DEPENDENCY_POINTS_INTO);
  }

  @State(ProviderStates.A_CONTRACTS_BUNDLE_WITH_A_PROJECTION)
  Map<String, String> aContractsBundleWithAProjection() {
    return states.params(ProviderStates.A_CONTRACTS_BUNDLE_WITH_A_PROJECTION);
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

  @State(ProviderStates.A_REPOSITORY_WITH_UNPINNED_IDENTITIES)
  Map<String, String> aRepositoryWithUnpinnedIdentities() {
    return states.params(ProviderStates.A_REPOSITORY_WITH_UNPINNED_IDENTITIES);
  }

  @State(ProviderStates.PIN_SOURCES_SILENT)
  Map<String, String> pinSourcesSilent() {
    return states.params(ProviderStates.PIN_SOURCES_SILENT);
  }

  @State(ProviderStates.A_MAVEN_ARTIFACT)
  Map<String, String> aMavenArtifact() {
    return states.params(ProviderStates.A_MAVEN_ARTIFACT);
  }

  @State(ProviderStates.NO_MAVEN_ARTIFACT)
  Map<String, String> noMavenArtifact() {
    return states.params(ProviderStates.NO_MAVEN_ARTIFACT);
  }

  @State(ProviderStates.AN_NPM_PACKAGE)
  Map<String, String> anNpmPackage() {
    return states.params(ProviderStates.AN_NPM_PACKAGE);
  }

  @State(ProviderStates.NO_NPM_PACKAGE)
  Map<String, String> noNpmPackage() {
    return states.params(ProviderStates.NO_NPM_PACKAGE);
  }

  @State(ProviderStates.AN_IMAGE)
  Map<String, String> anImage() {
    return states.params(ProviderStates.AN_IMAGE);
  }

  @State(ProviderStates.NO_IMAGE)
  Map<String, String> noImage() {
    return states.params(ProviderStates.NO_IMAGE);
  }

  @State(ProviderStates.A_MAVEN_JAR)
  Map<String, String> aMavenJar() {
    return states.params(ProviderStates.A_MAVEN_JAR);
  }

  @State(ProviderStates.NO_MAVEN_JAR)
  Map<String, String> noMavenJar() {
    return states.params(ProviderStates.NO_MAVEN_JAR);
  }

  @State(ProviderStates.AN_NPM_CONTENT_HASH)
  Map<String, String> anNpmContentHash() {
    return states.params(ProviderStates.AN_NPM_CONTENT_HASH);
  }

  @State(ProviderStates.THE_CLI_AS_A_DAEMON)
  Map<String, String> theCliAsADaemon() {
    return states.params(ProviderStates.THE_CLI_AS_A_DAEMON);
  }

  @State(ProviderStates.AN_UNFILEABLE_PIN)
  Map<String, String> anUnfileablePin() {
    return states.params(ProviderStates.AN_UNFILEABLE_PIN);
  }

  @State(ProviderStates.AN_APPLICATION_WITH_PUBLISHED_ARTIFACTS)
  Map<String, String> anApplicationWithPublishedArtifacts() {
    return states.params(ProviderStates.AN_APPLICATION_WITH_PUBLISHED_ARTIFACTS);
  }

  @State(ProviderStates.A_STORE_HOLDING_EVERY_KIND)
  Map<String, String> aStoreHoldingEveryKind() {
    return states.params(ProviderStates.A_STORE_HOLDING_EVERY_KIND);
  }

  @State(ProviderStates.A_STORE_WITH_NOTHING_PUBLISHED)
  Map<String, String> aStoreWithNothingPublished() {
    return states.params(ProviderStates.A_STORE_WITH_NOTHING_PUBLISHED);
  }

  @State(ProviderStates.AN_IMAGE_WITH_AN_UNTAGGED_MANIFEST)
  Map<String, String> anImageWithAnUntaggedManifest() {
    return states.params(ProviderStates.AN_IMAGE_WITH_AN_UNTAGGED_MANIFEST);
  }

  @State(ProviderStates.A_MAVEN_PACKAGE_WITH_AN_SBOM)
  Map<String, String> aMavenPackageWithAnSbom() {
    return states.params(ProviderStates.A_MAVEN_PACKAGE_WITH_AN_SBOM);
  }

  @State(ProviderStates.A_SCOPED_NPM_PACKAGE_WITH_AN_SBOM)
  Map<String, String> aScopedNpmPackageWithAnSbom() {
    return states.params(ProviderStates.A_SCOPED_NPM_PACKAGE_WITH_AN_SBOM);
  }

  @State(ProviderStates.A_DOCS_SITE_PUBLISHED_BY_TWO_RELEASES)
  Map<String, String> aDocsSitePublishedByTwoReleases() {
    return states.params(ProviderStates.A_DOCS_SITE_PUBLISHED_BY_TWO_RELEASES);
  }
}
