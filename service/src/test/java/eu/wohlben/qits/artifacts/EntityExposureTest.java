package eu.wohlben.qits.artifacts;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.EntityExposure;
import eu.wohlben.qits.archrules.EntityExposureRules;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * qits-1945's entity-exposure rules over this service: every {@code @Entity} reaches a JAX-RS
 * operation through a DTO, or says why not with {@code @Technical} or {@code @Projection}, or is
 * a known gap naming the ticket that will close it. {@code map} keeps the analysis as {@code
 * docs/entities.json}; rerun with {@code -Dgolden.update=true} to rewrite it.
 *
 * <p>The qits-registries jars ship their entities in this service's own package, {@code
 * eu.wohlben.qits.artifacts.entity} ({@code MavenArtifact}, {@code NpmVersion}, {@code
 * OciManifest}, ...). Those are an embedded library's entities, judged where they are written, so
 * {@link ExcludeRegistries} keeps them out of the import; this service's entities, resources and
 * DTOs are its own modules' classes.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.artifacts",
    importOptions = {ImportOption.DoNotIncludeTests.class, EntityExposureTest.ExcludeRegistries.class})
class EntityExposureTest {

  static final Set<String> KNOWN_GAPS = Set.of();

  @ArchTest
  static final ArchRule EXPOSED = EntityExposureRules.everyDomainEntityIsExposed(KNOWN_GAPS);

  @ArchTest static final ArchRule GAPS = EntityExposureRules.knownGapsAreReal(KNOWN_GAPS);

  @ArchTest static final ArchRule MARKERS = EntityExposureRules.markersAreWellFormed;

  @ArchTest
  static void map(JavaClasses classes) throws IOException {
    EntityExposure.verify(classes, "qits-artifacts", KNOWN_GAPS, Path.of("../docs/entities.json"));
  }

  /** Leaves out every class loaded from a qits-registries jar (qits-registries-maven, -npm, ...). */
  static final class ExcludeRegistries implements ImportOption {
    @Override
    public boolean includes(Location location) {
      return !location.contains("/qits-registries-");
    }
  }
}
