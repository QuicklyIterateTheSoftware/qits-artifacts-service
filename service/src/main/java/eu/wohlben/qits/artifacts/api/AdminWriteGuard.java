package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.auth.MachineAuth;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.Set;

/**
 * Guards the artifacts <b>write</b> surface — the JSON admin API under {@code /artifacts/api} — with
 * a machine token from qits-platform-idp. This is a pure system API (docs/epics/qits-artifacts/);
 * its callers are CI processes and platform services, never a browser session.
 *
 * <p>{@link MachineAuth#require()} is the whole check, and {@code qits.auth.machine.audience} is
 * {@code qits-platform}, the one audience every machine token carries, so it reads as "a validated
 * bearer this platform minted". No claim is inspected: nothing under this API belongs to one
 * project, and a token that reaches here at all was issued to a client qits-platform-idp trusts
 * with the blob store.
 *
 * <p><b>The rollout gate decides whether it does anything.</b> With {@code
 * qits.auth.machine.required} off — the shipped default — every call returns at once and the write
 * surface is open exactly as it was before qits-platform-idp existed. That is the same posture the
 * retired {@code X-Artifacts-Token} filter had with a blank secret, and it is why this can ship
 * before the idp is deployed. Reads (GET) are never guarded either way — a blob must stay usable
 * directly as an {@code <img>}/{@code <video>} src.
 *
 * <p>This filter is JAX-RS, so it sees only what RESTEasy dispatches. It does <b>not</b> run on the
 * raw Vert.x routes — neither {@code /artifacts/git/*} nor the registry's {@code /v2/*} nor the npm
 * paths — and all of them stay unguarded <b>on purpose</b> in phase 1: the git host trades on
 * capability-url repo ids and its own {@code ProtectedRefHook}, and the registry is deliberately
 * tokenless (trusted producers on qits-net; external writes die on the gateway's session policy —
 * see {@code RegistryRoutes.init}). Turning the gate on guards the JSON API and nothing else;
 * {@code RegistryOpenPushTest} pins that.
 */
@Provider
public class AdminWriteGuard implements ContainerRequestFilter {

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  /**
   * The JAX-RS base-relative prefixes this filter claims. A resource served outside them is
   * <b>unguarded</b>, so this set is extended when one is added rather than assumed to cover it.
   * {@code store} holds only the read-only store summary today and is listed so that stays a choice.
   * {@code gc} was listed ahead of its write for a sharper reason — anything under it that writes
   * deletes bytes — and that write exists now: {@code POST /gc/sweep} inherited this guard by
   * construction instead of by remembering. (A deployment shipping a blank token still runs the
   * guard as a no-op — the standing posture until the platform's auth story lands.)
   *
   * <p>{@code mirror-upstreams} was here and is gone with the controller: which public registry a
   * cache dials on a miss is qits-platform-mirror's decision now, and so is guarding it.
   */
  private static final Set<String> GUARDED_PREFIXES = Set.of("repositories", "store", "gc");

  @Inject MachineAuth machineAuth;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    // getPath() is relative to the JAX-RS base (quarkus.rest.path, /artifacts/api); normalize any
    // leading slash. A write to /artifacts/api/repositories/... lands here as "repositories/...".
    // The prefix is "repositories" and not "artifacts" because the segment carries that now — the
    // resource @Paths dropped it.
    String path = requestContext.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    String guardedPath = path;
    if (GUARDED_PREFIXES.stream().noneMatch(guardedPath::startsWith)
        || !WRITE_METHODS.contains(requestContext.getMethod())) {
      return;
    }
    // Throws UnauthorizedException (401) with no machine token, ForbiddenException (403) with one
    // addressed elsewhere. Quarkus REST maps both, so there is nothing to abortWith here.
    machineAuth.require();
  }
}
