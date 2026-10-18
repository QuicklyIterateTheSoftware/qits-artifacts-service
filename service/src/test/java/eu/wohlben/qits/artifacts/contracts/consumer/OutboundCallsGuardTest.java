package eu.wohlben.qits.artifacts.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.api.RegistryTokenEndpoint;
import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.gc.GcPinSources;
import eu.wohlben.qits.http.QitsHttpClients;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound call to a qits service has a row</b> (qits-1149): the guard that keeps {@link
 * ConsumerContract} true as the code moves.
 *
 * <p>The calls are DERIVED from the code, not listed: every main class of this service's three
 * modules ({@code artifacts}, {@code gc}, {@code service}) that holds a {@link HttpClient} field or
 * is built from {@link QitsHttpClients} is an outbound client. Each must be in {@link
 * ConsumerContract#CLIENTS} with its provider, and each provider there must have a row. A new
 * client class with no row, or a row whose class is gone, fails here.
 *
 * <p>The quarkus-oidc tenant is not a class of this service's, so its call is derived from the
 * configuration: an {@code auth-server-url} with a {@code jwks-path} means a {@code getJwks} row,
 * discovery left on would mean a {@code getOpenIdConfiguration} row too, and a {@code
 * quarkus-oidc-client} on the classpath would mean the client-credentials mint ({@code issueToken}).
 *
 * <p><b>Excluded by name</b>, because none is a qits REST contract: the registries' upstream
 * clients (the maven, npm and OCI proxies, whose caches are qits-mirror's and are excluded from
 * bean discovery here) live in the registries jars, not in these modules, so the scan never sees
 * them; and the OpenTelemetry exporter's OTLP posts to qits-observability ({@code
 * quarkus.otel.exporter.otlp.endpoint}), a telemetry stream every service sends and no pact
 * describes. There is no other exclusion.
 */
class OutboundCallsGuardTest {

  /** The package root every main class of the three modules lives under. */
  private static final String ROOT = "eu/wohlben/qits/";

  @Test
  void everyOutboundClientClassHasItsProviderAndARow() {
    Set<String> clients = new TreeSet<>();
    for (Class<?> anchor :
        List.of(ArtifactsRepositorySeeder.class, GcPinSources.class, RegistryTokenEndpoint.class)) {
      for (String name : classNames(anchor)) {
        if (isOutboundClient(load(name))) {
          clients.add(name);
        }
      }
    }
    assertFalse(clients.isEmpty(), "the scan found no outbound client at all");
    assertEquals(
        new TreeSet<>(ConsumerContract.CLIENTS.keySet()),
        clients,
        "the outbound client classes in the code vs ConsumerContract.CLIENTS — a new client needs"
            + " its provider and a row; a removed one, its entry gone");
    for (var client : ConsumerContract.CLIENTS.entrySet()) {
      assertTrue(
          ConsumerContract.ROWS.stream().anyMatch(r -> r.provider().equals(client.getValue())),
          client.getKey() + " calls " + client.getValue().repository() + ", which has no row");
    }
  }

  @Test
  void theOidcTenantsCallsHaveRows() throws IOException {
    // The service module's own application.properties, beside its classes: the test resources
    // carry one too, and the classpath would answer with whichever comes first.
    String classes =
        RegistryTokenEndpoint.class.getProtectionDomain().getCodeSource().getLocation().toString();
    String resource =
        classes.endsWith(".jar")
            ? "jar:" + classes + "!/application.properties"
            : classes + (classes.endsWith("/") ? "" : "/") + "application.properties";
    Properties config = new Properties();
    try (InputStream in = java.net.URI.create(resource).toURL().openStream()) {
      config.load(in);
    }
    assertTrue(
        config.getProperty("quarkus.oidc.auth-server-url", "").contains("idp"),
        "the tenant validates against qits-idp");
    if (config.getProperty("quarkus.oidc.jwks-path") != null) {
      assertTrue(hasRow("getJwks"), "the tenant reads the idp's JWKS: a getJwks row");
    }
    if (!"false".equals(config.getProperty("quarkus.oidc.discovery-enabled"))) {
      assertTrue(
          hasRow("getOpenIdConfiguration"),
          "discovery is on: the tenant reads the discovery document, a getOpenIdConfiguration row");
    }
    if (present("io.quarkus.oidc.client.OidcClient")) {
      assertTrue(
          ConsumerContract.ROWS.stream()
              .anyMatch(r -> r.operationId().equals("issueToken")
                          && !r.trigger().equals(ConsumerContract.REGISTRY_TOKEN)),
          "quarkus-oidc-client mints a client-credentials token at the idp: an issueToken row");
    }
  }

  private static boolean hasRow(String operationId) {
    return ConsumerContract.ROWS.stream()
        .anyMatch(r -> r.provider().equals(ConsumerContract.IDP) && r.operationId().equals(operationId));
  }

  /** A class holding an {@link HttpClient}, or one built from {@link QitsHttpClients}. */
  static boolean isOutboundClient(Class<?> type) {
    for (Field field : type.getDeclaredFields()) {
      if (HttpClient.class.isAssignableFrom(field.getType())
          || QitsHttpClients.class.equals(field.getType())) {
        return true;
      }
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (Arrays.asList(constructor.getParameterTypes()).contains(QitsHttpClients.class)) {
        return true;
      }
    }
    return false;
  }

  /** Every top-level and nested class name under {@link #ROOT} in the code source of {@code anchor}. */
  private static List<String> classNames(Class<?> anchor) {
    try {
      Path source = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isDirectory(source)) {
        Path root = source.resolve(ROOT);
        try (Stream<Path> files = Files.walk(root)) {
          return files
              .map(f -> source.relativize(f).toString().replace('\\', '/'))
              .filter(f -> f.endsWith(".class"))
              .map(OutboundCallsGuardTest::className)
              .toList();
        }
      }
      try (JarFile jar = new JarFile(source.toFile())) {
        return jar.stream()
            .map(e -> e.getName())
            .filter(f -> f.startsWith(ROOT) && f.endsWith(".class"))
            .map(OutboundCallsGuardTest::className)
            .toList();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String className(String file) {
    return file.substring(0, file.length() - ".class".length()).replace('/', '.');
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name, false, OutboundCallsGuardTest.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IllegalStateException("cannot load " + name, e);
    }
  }

  private static boolean present(String name) {
    try {
      Class.forName(name, false, OutboundCallsGuardTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
