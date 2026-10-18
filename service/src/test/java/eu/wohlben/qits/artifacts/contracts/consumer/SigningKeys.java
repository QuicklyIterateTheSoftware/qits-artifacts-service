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
 * <b>What the quarkus-oidc tenant does with the idp's JWKS</b> (qits-1149), for the consumer row
 * that reads it: the tenant is not this service's class, so the row's reader reads the key set the
 * same way — every key an RSA signing key from which a public key is built, picked by {@code kid}.
 */
final class SigningKeys {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SigningKeys() {}

  /** The keys at {@code url}, each as the members the tenant used; fails on a key it cannot use. */
  static List<Map<String, String>> read(String url) {
    try {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
                  HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(url + " answered " + response.statusCode());
      }
      List<Map<String, String>> keys = new ArrayList<>();
      for (JsonNode key : MAPPER.readTree(response.body()).path("keys")) {
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(url, e);
    }
  }
}
