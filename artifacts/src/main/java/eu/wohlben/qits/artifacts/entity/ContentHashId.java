package eu.wohlben.qits.artifacts.entity;

import java.io.Serializable;
import java.util.Objects;

/** The composite key of {@link ContentHash}: repository, ecosystem, package name and version. */
public class ContentHashId implements Serializable {

  public String repository;
  public String ecosystem;
  public String packageName;
  public String version;

  public ContentHashId() {}

  public ContentHashId(String repository, String ecosystem, String packageName, String version) {
    this.repository = repository;
    this.ecosystem = ecosystem;
    this.packageName = packageName;
    this.version = version;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ContentHashId id
        && Objects.equals(repository, id.repository)
        && Objects.equals(ecosystem, id.ecosystem)
        && Objects.equals(packageName, id.packageName)
        && Objects.equals(version, id.version);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, ecosystem, packageName, version);
  }
}
