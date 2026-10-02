package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The content hash a publisher claimed for one maven GAV or npm version (epic qits-620).
 *
 * <p>Its own row rather than a column on {@code NpmVersion} or {@code MavenArtifact}: those ship in
 * qits-registries-javalib and qits-mirror-service maps them too, over a schema of its own. See
 * {@code V4__content_hash.sql}.
 */
@Entity
@Table(name = "content_hash")
@IdClass(ContentHashId.class)
public class ContentHash extends PanacheEntityBase {

  @Id public String repository;

  /** {@code maven} or {@code npm}. */
  @Id
  @Column(length = 8)
  public String ecosystem;

  /** The maven {@code groupId:artifactId}, or the npm package's full name. */
  @Id
  @Column(name = "package_name", length = 512)
  public String packageName;

  @Id
  @Column(length = 128)
  public String version;

  /** Opaque, {@code v<n>:<alg>:<hex>} — shape-checked by the route, never recomputed here. */
  @Column(nullable = false, length = 160)
  public String value;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
