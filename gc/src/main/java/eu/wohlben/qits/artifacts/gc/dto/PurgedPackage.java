package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * One package an application purge removed, and every version of it (qits-1941).
 *
 * @param type {@code docs}, {@code maven}, {@code npm}, {@code oci} or {@code sbom}
 * @param repository the store's repository row the package lived in ({@code docs}, {@code
 *     maven}, {@code npm}, {@code qits}, {@code sboms} as seeded)
 * @param name the docs site, {@code groupId:artifactId}, npm package or image name; an SBOM is
 *     {@code <packageType>/<packageName>}, the SBOM adapter's own spelling, because one name can
 *     carry a document per type
 * @param versions every version removed, sorted. An image's are its tags and each of its
 *     manifests as {@code sha256:<digest>}, because an untagged manifest is a version no tag names
 */
public record PurgedPackage(String type, String repository, String name, List<String> versions) {}
