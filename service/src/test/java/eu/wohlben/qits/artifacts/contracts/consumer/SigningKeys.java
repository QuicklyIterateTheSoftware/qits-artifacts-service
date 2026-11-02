package eu.wohlben.qits.artifacts.contracts.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>What the quarkus-oidc tenant does with the idp's discovery document and JWKS</b> (qits-1149,
 * qits-708), for the consumer rows that read them: the tenant is not this service's class, so the
 * rows' readers read both the same way — the document's {@code issuer} as the value every bearer's
 * {@code iss} is held to and its {@code jwks_uri} as an address, and every key an RSA signing key
 * from which a public key is built, picked by {@code kid}.
 */
final class SigningKeys {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SigningKeys() {}

  /**
   * The discovery document at {@code url}, as the members the tenant uses: {@code issuer} (never
   * blank: it is what {@code iss} must equal), and {@code jwks_uri} and {@code token_endpoint}, each
   * an absolute URI. Fails on a document the tenant could not use.
   */
  static Map<String, String> discovery(String url) {
    try {
      JsonNode document = MAPPER.readTree(get(url));
      Map<String, String> used = new LinkedHashMap<>();
      for (String member : List.of("issuer", "jwks_uri", "token_endpoint")) {
        String value = document.path(member).asText();
        if (value.isBlank()) {
          throw new IllegalStateException(url + " publishes no " + member);
        }
        if (!member.equals("issuer") && !URI.create(value).isAbsolute()) {
          throw new IllegalStateException(url + ": " + member + " " + value + " is not absolute");
        }
        used.put(member, value);
      }
      return used;
    } catch (IOException e) {
      throw new IllegalStateException(url, e);
    }
  }

  /** The keys at {@code url}, each as the members the tenant used; fails on a key it cannot use. */
  static List<Map<String, String>> read(String url) {
    try {
      List<Map<String, String>> keys = new ArrayList<>();
      for (JsonNode key : MAPPER.readTree(get(url)).path("keys")) {
        Base64.Decoder b64 = Base64.getUrlDecoder();
        KeyFactory.getInstance(key.path("kty").asText())
            .generatePublic(
                new RSAPublicKeySpec(
                    new BigInteger(1, b64.decode(key.path("n").asText())),
                    new BigInteger(1, b64.decode(key.path("e").asText()))));
        Map<String, String> used = new LinkedHashMap<>();
        for (String member : List.of("kid", "kty", "alg", "use", "n", "e")) {
          used.put(member, key.path(member).asText());
        }
        keys.add(used);
      }
      return keys;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(url, e);
    }
  }

  /** A plain GET, as the tenant's own reads are: the body of a 200, or a failure naming the status. */
  private static String get(String url) throws IOException {
    try {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                  HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(url + " answered " + response.statusCode());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(url, e);
    }
  }
}
