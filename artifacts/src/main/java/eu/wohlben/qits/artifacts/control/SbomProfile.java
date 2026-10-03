package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Published <b>software bills of materials</b> — one CycloneDX document per released artifact,
 * served at {@code /artifacts/sboms/} by {@code eu.wohlben.qits.sbom}. The third type this service
 * contributes to the open registration.
 *
 * <p>A protocol profile on {@link DaemonBinariesProfile}'s pattern: a document arrives as one JSON
 * body on a streaming {@code PUT} and its bytes go straight to {@code BlobStore}, so nothing here
 * flows through {@code BlobService} and there is no media type to sniff. The real cap is {@code
 * qits.artifacts.sbom.max-size}.
 *
 * <p><b>It names a role, not a file format.</b> The three questions a type has to answer: <b>who
 * publishes</b> — the release pipeline of every repository, in its last step after the publish
 * command and before the run goes green, so the document exists before the {@code SoftwareRelease}
 * event names the version; <b>what GC keeps</b> — the last released documents of every {@code
 * (packageType, packageName)}, plus every released document whose artifact is still in this store
 * ({@code SbomGcAdapter.pinnedBy}); <b>what pins an entry</b> — nothing: an SBOM describes an
 * artifact and pins nothing.
 *
 * <p><b>The identity is the {@code SoftwareRelease} identity</b> — {@code (packageType, packageName,
 * version)} verbatim — which is what lets a consumer of that event fetch the document with no
 * translation step, and what makes this one shared attachment mechanism for maven, npm, docker and
 * daemon artifacts alike where each of those wires could carry no metadata of its own.
 *
 * <p>Documents are immutable in the first-write-wins sense: a re-PUT of an existing identity answers
 * {@code 200 alreadyPublished} and stores nothing, so a replayed release build converges instead of
 * conflicting — see {@code SbomRegistryService.publish} for why this differs from the daemon 409.
 */
@ApplicationScoped
public class SbomProfile implements RepositoryTypeProfile {

  /**
   * The stored key, verbatim as {@code artifact_repository.type} carries it and as V3's {@code
   * ck_artifact_repository_type} lists it. A column value, not a name this class is free to change.
   */
  public static final String KEY = "SBOMS";

  @Override
  public String key() {
    return KEY;
  }
}
