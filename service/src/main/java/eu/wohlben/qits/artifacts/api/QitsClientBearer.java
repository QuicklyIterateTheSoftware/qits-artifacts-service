package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.artifacts.gc.PinSourceBearer;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The bearer every GC pin read presents: this service's own {@code qits:system} token, minted by
 * the named oidc client {@code qits} from the deployer's {@code idp:client} resource (qits-1410; the
 * qits-docs pattern of qits-1419, itself qits-githost's of qits-1330).
 *
 * <p><b>A token this cannot mint is empty rather than an exception</b> — qits-maintenance's {@code
 * PeerTokens} stance: the read then goes out bare, the edge answers 401, and the pin source's own
 * refusal aborts the run closed and names the source, which is more useful than a mint failure one
 * layer earlier. Off (the extension's own {@code quarkus.oidc-client.qits.client-enabled}, false
 * under %dev and %test) it answers empty without dialling anything.
 */
@ApplicationScoped
public class QitsClientBearer implements PinSourceBearer {

  private static final Logger LOG = Logger.getLogger(QitsClientBearer.class);

  /** The mint is not the read: this bounds the hop to idp, not the hop to the pin source. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @Inject
  @NamedOidcClient("qits")
  OidcClient qits;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled", defaultValue = "false")
  boolean enabled;

  /** Caches and refreshes the token, so the five reads of one plan are one token request. */
  private final TokensHelper helper = new TokensHelper();

  @Override
  public Optional<String> token() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              helper.getTokens(qits).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for the GC pin sources: %s", e.toString());
      return Optional.empty();
    }
  }
}
