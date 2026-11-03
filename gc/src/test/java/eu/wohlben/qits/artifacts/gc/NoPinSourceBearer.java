package eu.wohlben.qits.artifacts.gc;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/**
 * The {@link PinSourceBearer} this module's own suite runs with: none. The real one is the {@code
 * service} module's, minted by its named oidc client, and this module has no identity to mint with;
 * its pin peers are closed ports anyway, so nothing would carry the token anywhere.
 */
@ApplicationScoped
public class NoPinSourceBearer implements PinSourceBearer {

  @Override
  public Optional<String> token() {
    return Optional.empty();
  }
}
