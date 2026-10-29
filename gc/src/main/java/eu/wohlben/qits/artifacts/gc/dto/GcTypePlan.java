package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * What one repository type's strategy would do, or the honest reason there is nothing to say.
 *
 * @param type the repository type, in its wire spelling
 * @param strategy the registered strategy's class name, or null when none is registered. Named
 *     rather than boolean: a type collected by an unexpected class is worth seeing in the report.
 * @param note why a type has no plan — "no strategy registered for oci-images". Null when one ran.
 * @param error the strategy refused to plan, with its reason. Fail-closed: the type's blobs are all
 *     treated as live, so an unreachable dependency reclaims nothing rather than guessing.
 * @param dead the identities this run would delete, each naming its rule
 * @param kept the identities it would keep, each naming the rule that saved it
 * @param blobsReleased how many blobs the dead identities referenced. Not the same as how many
 *     would be unlinked — most are still referenced by something that survives.
 * @param blobsSweepable how many of those lose their <b>last</b> reference across every type, with
 *     only this type's plan applied and every other type left as the census found it
 * @param reclaimableBytes what those blobs occupy on disk. Zero is the common honest answer for a
 *     type whose content is shared with something that stays.
 */
public record GcTypePlan(
    String type,
    String strategy,
    String note,
    String error,
    List<GcIdentity> dead,
    List<GcIdentity> kept,
    int blobsReleased,
    int blobsSweepable,
    long reclaimableBytes) {}
