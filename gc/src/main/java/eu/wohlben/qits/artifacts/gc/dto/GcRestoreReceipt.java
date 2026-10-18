package eu.wohlben.qits.artifacts.gc.dto;

import java.util.List;

/**
 * What {@code POST /gc/condemned/{type}/{identity}/restore} lifted.
 *
 * <p><b>A restore is not a keep.</b> It clears the condemnation and nothing else: if the next sweep's
 * keep-set still leaves the identity dead, that sweep condemns it again — with a fresh clock, so it
 * stands a full {@code condemnGracePeriod} once more before any run may purge it, and it is back on
 * {@code GET /gc/condemned} where somebody can see it. What makes it stay is a pin: the pin source
 * that should have named it (a manifest on main, a deployment, a launch config) naming it. The
 * receipt says so in {@code note}, because the person pressing restore is the one who has to go and
 * fix that.
 *
 * @param restored the condemnations removed, as they stood — one per repository the identity was
 *     condemned in
 * @param note the standing sentence above, in the words the operator reads
 */
public record GcRestoreReceipt(List<GcCondemnedIdentity> restored, String note) {}
