# qits-artifacts-service

qits' **hosted byte plane**, env-scoped: the hosted OCI registry the platform pushes its images to,
the hosted npm registry and maven repository its own libraries publish to, the daemon binaries every
CI step downloads and executes, the documentation bundles qits-docs serves back, the CycloneDX
bill of materials of every release, the CI media the
golden-diff loop produces — and the pin-based garbage collection over all of them.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## What is NOT here, and where it went

This repository was `qits-platform-artifacts` and held three things it no longer does
(the byte-plane split, phase 4 — the plan's history is in the superproject's git):

| Left | Went to | Why |
|---|---|---|
| the pull-through caches — `npm-proxy`, `maven-proxy`, `oci-mirror`, their upstreams and the access-tracked eviction GC | **qits-mirror-platform-service** | a cache of somebody else's registry is shared across every environment, and holding it was the *only* reason this service was platform-scoped. Without it, this one is an env service again. |
| the git smart-HTTP host — the `git-storage` module, `eu.wohlben.qits.githost*`, the post-receive fan-out | **qits-githost-service** | a repository is not an artifact. It only ever shared the storage layout, and every consumer of it (qits-ci, qits-deployments, qits-workspaces, the daemons) is an env service already. |
| the content-addressed blob store and the three protocol implementations | the libraries **qits-blobstore** and **qits-registries-{common,npm,maven,oci}** | the mirror needs the same store and the same wire code; a library is what stops that being a fork. |

So the two-endpoint topology a client sees: `@qits`-scoped npm packages, qits images and qits jars
come from **this** service at the env's own address; everything third-party comes from the mirror,
one deployment for the whole platform (`127.0.0.1:8082` on a host, `qits-platform-mirror:8080` on
qits-net). Splitting that is client configuration — npm scoped registries, dockerd
`registry-mirrors`, the maven repositories list — because the route prefixes are literals in the
shared jars and both services answer the same paths.

**The schema did not move.** The Flyway lineage under `artifacts/src/main/resources/db/artifacts/`
is carried through the split byte for byte: the libraries ship no migrations by design, each
consuming service owns its own, and this one's is the original chain. That leaves orphaned tables
behind — the git host's `git_pack`/`git_pack_file`/`git_repository_protection` (V4/V5) and the proxy
tables — which is a cutover data question rather than a reason to rewrite history.

**The orphaned data was a second question, and V14 answered it.** V7 prefilled three `oci-mirror`
repository rows on every fresh database, and rows of a type this service registers no profile for
are omitted from the explorer listing and from the GC plan rather than reported with numbers nothing
here can compute — but they were not merely untidy. Excluding a profile does not unregister the wire
code that ships beside it, so while those rows stood the three namespaces still answered a pull.
V14 deletes them, and any `npm-proxy`/`maven-proxy` row a deployment carried through the split, and
that is what closes the path rather than merely disclaiming it. It drops no table, because two of
the mirror's DAOs are live beans here. See "The pull-through mirror".

## Layout

| Module | What |
|---|---|
| `artifacts/` | What did not move out: the docs, daemon and SBOM registries (entities, persistence, services), the store explorer, the live blob census, the repository seeder, the three repository-type **profile beans** this service contributes (`DAEMON_BINARIES`, `DOCS`, `SBOMS`) — and the Flyway lineage. A library jar: no web, no JAX-RS. |
| `gc/` | `eu.wohlben.qits.artifacts.gc` (+ `.dto`) — garbage collection: the pin-based own-artifacts engine, the per-type adapters, the planner, the reconciliation and the sweep, plus the two pin ports (`CdDeploymentPins`, `CiDaemonPins`) and their HTTP adapters. A *process* modelled from within this service, not artifacts domain. Depends on `artifacts` and, one way, never back. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary), `eu.wohlben.qits.docs`, `eu.wohlben.qits.daemon` and `eu.wohlben.qits.sbom` (three raw Vert.x wires), and the SPA. The npm, maven and OCI wires are **not** written here any more — they arrive as beans on the qits-registries jars and register their own routes. |
| `service/src/main/webui/` | The SPA submodule — an Angular app, built into the app by Quinoa and served at the **root** of this service's own host. Not Java, and not a Maven module. |

`artifacts/` and `gc/` are library jars; **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080 — SPA /, blobs /artifacts/api/**,
                                                           #         npm /artifacts/npm/**, maven /artifacts/maven/**,
                                                           #         docs /artifacts/docs/**, daemons /artifacts/daemons/**,
                                                           #         sboms /artifacts/sboms/**, images /v2/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`quarkus.native.additional-build-args` is gone with the git host: every flag it carried was a JGit
static native-image refuses to freeze into the image heap, and JGit left with the host.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip, an image push/pull, an npm publish/install, a maven
deploy/resolve and the browse endpoints. It is the only place the route stacks are proved to
coexist in one process, and the only thing that exercises zero-copy `sendFile` serving in a binary.

`service/src/main/webui` is a submodule, so a fresh clone wants `git submodule update --init` before
`./mvnw package`; without it Quinoa finds no `package.json`, disables itself with a warning, and the
app ships with no client while the build stays green.

The client no longer spells the segment at all. Its Angular `baseHref` is `/`, matching
`quarkus.quinoa.ui-root-path` here, because this service has a **host of its own** —
`registry.<env>.<domain>`, the name docker already resolves every image reference against — and the
client is what that host serves at its root. The two still move together: the browser resolves asset
urls against the document, not against anything the server knows.

The machine surfaces keep their prefixes. The edge path-routes `/artifacts` and `/v2` on every host
and rewrites nothing, so there is no unprefixed form, on `qits-net` either.

Note the route stacks resolve differently: `/artifacts/api/**` is JAX-RS and moves with
`quarkus.rest.path`; `/artifacts/npm/**`, `/artifacts/maven/**`, `/artifacts/docs/**`,
`/artifacts/daemons/**`, `/artifacts/sboms/**` and `/v2/**` are registered straight onto the Vert.x
router with the segment as a literal, and do not.

**Which types this deployment registers is one line of configuration.** Repository types are
contributed as `RepositoryTypeProfile` beans, and each qits-registries module ships *both* halves of
its format — hosted and cache — so the cache profiles arrive on the classpath whether this service
wants them or not. `quarkus.arc.exclude-types` in `service/src/main/resources/application.properties`
vetoes the three, which is what makes "the caches are the mirror's" true rather than merely
intended: `RepositoryTypeProfiles` then indexes exactly eight keys, `ensure` answers a request for
any other with a 400 naming what IS registered, and the GC plan reports those same eight.

`artifacts/` owns its **own datasource, persistence unit and Flyway lineage**
(`db/artifacts/migration`, a separate H2 under `~/.qits/data/artifacts`). It always did — that
lineage was never part of the monorepo's shared `db/migration`, so unlike every other extraction
target nothing here had to be squashed or rebased. `V1__init.sql` is the original file, unchanged.

That H2 is **embedded only** — the url no longer carries `AUTO_SERVER=TRUE`. Automatic mixed mode
made sense while this was a library inside the monolith and a second JVM might open the same file;
as its own process on its own volume a second writer would be a bug, and the option is fatal to the
native binary, which has no `org.h2.server.TcpServer` to start.

## The blob store

A **repository** is a name plus a type (`ci-screenshots`, `ci-videos`); the type selects the
validation profile and the size cap. A **record** is one immutable upload: the content id is the
SHA-256 of the bytes, so many records may share one blob and stay distinct rows.

Metadata is a flat `String -> String` map, delivered as `X-Artifacts-Meta-*` request headers and
queried as exact-match `?meta.<key>=<value>` predicates, with a `?latest` collapse. That is the
whole coupling model: **blobs reference qits branches and flows by string metadata, never by
foreign key.** Nothing here breaks when qits' schema moves, and nothing here needs a JPA relation
into another context's tables.

    PUT  /artifacts/api/repositories/{repo}                 ensure a repository — qits:system
    POST /artifacts/api/repositories/{repo}/blobs           upload, raw body + X-Artifacts-Meta-* — qits:system
    GET  /artifacts/api/repositories/{repo}/blobs/{id}      serve — qits:admin or qits:agent, cacheable, immutable
    GET  /artifacts/api/repositories/{repo}/blobs?meta.…    query — qits:admin or qits:agent

A **repository** here is a named bucket of artifacts — the Maven/npm sense of the word, not
`domain.repository`. The resource keeps that name; the `artifacts` the path used to repeat is gone,
because the segment already says it.

Writes require a machine token from qits-platform-idp — a bearer with `aud=qits-platform-artifacts`, checked by
`AdminWriteGuard`. The check sits behind the platform-wide rollout gate `qits.auth.machine.required`,
which is off by default: off, the write surface is open exactly as it was before qits-platform-idp existed.
Reads are never guarded — a blob must be usable directly as an `<img>`/`<video>` src.

## The git host

It is not here. The smart-HTTP host, the DFS storage over blobs and the post-receive fan-out are
**qits-githost**'s, an env service of its own, where the fan-out is durable domain events rather
than an HTTP call. The `qits-githost-service` README carries what this section used to.

## The OCI registry

> **Both halves of this format ship in `qits-registries-oci`, and this service registers only the hosted one.** The pull-through cache half — its repository type, its upstream rows and its eviction GC — is qits-platform-mirror's; "The pull-through mirror" below is what is left at the seam.

`RegistryRoutes` serves the [OCI Distribution
API](https://github.com/opencontainers/distribution-spec) at the literal `/v2`, so an image pushed
here runs anywhere with one standard command:

```
docker run -it <host>/qits/alpine:latest
podman run     <host>/qits/alpine:latest
```

The mount point is not a choice. Docker and podman resolve a reference against `<host>/v2/` and
accept no path prefix, so this cannot live under `quarkus.rest.path` the way the JSON API does — it
is raw Vert.x at the host root, a literal in the code exactly as `/artifacts/npm` is, and nothing in
the JAX-RS configuration moves it. `RegistryTest` is the only thing that would notice if it drifted.

The corollary is that `/artifacts/v2` must serve **nothing**. A deployment that prefixes the
registry is misconfigured, and a 404 is how a registry client is told "not a registry here" —
answered with the SPA it gets `200 text/html` and no `Docker-Distribution-Api-Version` header, and
reports something that names neither cause nor fix.

`/v2` itself is in `quarkus.quinoa.ignored-path-prefixes` for the same reason turned around. The
client is at `/` now, so its catch-all covers the whole host: without that entry a mistyped registry
path would answer the page rather than a 404.

Storage is the blob store, unchanged: OCI addresses every layer, config and manifest as
`sha256:<hex>`, which *is* `BlobStore`'s model, so layers dedupe globally with everything else. Two
small tables carry what content addressing cannot: `oci_manifest` scopes a manifest to a
`(repository, image)` — without it the globally-deduped store would serve one repository's manifest
out of another's namespace, and an index's untagged children would be unresolvable — and `oci_tag`
holds the one piece of mutable state in the registry, a movable pointer at a manifest digest.

### Creating a repository

Repositories are **not** created implicitly. The first segment of an image name is an
`oci-images`-typed row in `artifact_repository`, created with the ordinary ensure endpoint:

```
curl -X PUT http://<host>/artifacts/api/repositories/<name> \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $QITS_ARTIFACTS_MACHINE_TOKEN" \
  -d '{"type":"oci-images"}'
```

**`qits` needs none of that** — the startup seed ensures that row alongside the two CI types, so a
fresh deployment accepts the platform's own convention (`qits/<application>:<sha>`, what a pipeline's
publish step pushes) with zero manual steps. The curl above is for **additional** repositories.

So `qits/alpine:latest` works out of the box, and `qits/build-images/ci-base:latest` is repository
`qits`, image `build-images/ci-base`. A push to an unknown first segment is `404 NAME_UNKNOWN` with a
message naming this command; a single-segment reference (`docker push <host>/alpine`) is `400
NAME_INVALID`, because it has no repository/image split at all.

### The pull-through mirror

It is not here. The `oci-mirror` type, the upstream rows a mirrored namespace resolves through, the
miss path with its tag TTL and its anonymous-bearer dance, and the four `mirror-upstreams` admin
routes are **qits-platform-mirror**'s — one cache warmed by every environment rather than a copy per
tier, reached at its own address (`127.0.0.1:8082` on a deployment host, `/v2` at that root) and
documented in `qits-mirror-platform-service`'s README. Third-party images come from there; this service serves what the
platform pushed.

Three things about the seam, none of them visible from either README alone:

- **The hosted half refuses nothing new.** `/v2` here accepts, serves and conforms exactly as it did.
  What left is a second *kind* of namespace, not a restriction on this one.
- **`oci-mirror` is an unclaimed type here, and V14 took the last rows of it away.**
  `quarkus.arc.exclude-types` drops `OciMirrorProfile` from bean discovery, so `{"type":"oci-mirror"}`
  on the ensure endpoint is a `400` naming the types that *are* registered. V7's `hub`, `quay` and
  `redhat` rows survived the split in the lineage, which is carried through byte for byte; V14 is
  the migration that deletes them, along with any `npm-proxy`/`maven-proxy` row a deployment brought
  with it. A cache row that still has content cached under it is left standing, because deleting it
  would strand the blobs.
- **Excluding the profile does not unregister the wire code, which is why the rows had to go.**
  `RegistryRoutes` injects `MirrorUpstream` outright and `resolveForPull` matches the type by string,
  so while a prefilled namespace stood it still resolved for a `GET` — and a first segment naming no
  repository row still remapped into `hub`, so a pull aimed here could reach docker.io. With no row
  and no upstream, `resolveForPull` answers `404 NAME_UNKNOWN` and `mirrors.hub()` answers nothing.
  Point dockerd's `registry-mirrors`, every rewritten `FROM` and every other client at
  qits-platform-mirror's address regardless: nothing should arrive here for an image this platform
  did not push.

### Reaching it from a client

Over plain HTTP a client needs a one-time opt-in — this is deployment configuration, not something
the registry can do for you. It disappears entirely behind a TLS-terminating gateway.

```jsonc
// docker: /etc/docker/daemon.json, then `sudo systemctl restart docker`
{ "insecure-registries": ["qits-host:8080"] }
```

```toml
# podman: /etc/containers/registries.conf.d/qits.conf
[[registry]]
location = "qits-host:8080"
insecure = true
```

`localhost` is special-cased as insecure by both, so a same-host smoke test needs neither — and a
test that only ever uses `localhost` proves nothing about a real deployment.

A client that also pulls third-party images needs a **second** entry, for qits-platform-mirror's
address. That is the whole client-side cost of the split on this format, and there is no
`registry-mirrors` line here: a daemon-configured mirror belongs at the mirror.

Through qits-gateway the registry is reached at the same `/v2` on the gateway's port. The gateway
routes it from the **artifacts** `proxy-hosts` entry (there is no `…_V2` key) and allow-lists
`/v2/*`, which is required: a registry client sends none of the headers the gateway uses to tell a
navigation from a background request, so an unlisted `/v2` would answer a 302 into the IdP that
docker reads as "not a v2 registry".

### No login, in either direction

Reads are anonymous, always — image names are meant to be shared, which is also why `/v2/_catalog`
stays unimplemented and the posture stays private-network rather than capability-URL. **Writes are
anonymous too**: the registry carries no credential of its own, and the machine-token gate guards
the blob-store JSON admin API and nothing else (`RegistryOpenPushTest` pins that turning it on does
not drag `/v2` back behind a docker login).

That is a decision with two halves, one per direction:

- **Inside the deployment**, producers on qits-net are trusted — the platform posture everywhere —
  and a tokenless registry is what lets an automated publisher (the CI/CD image-build story) push
  with no credential store. Every client works: `docker push`, `skopeo copy`, `podman push`, with
  no login step at all.
- **From outside**, write protection is qits-gateway's: `/v2` is on its token-free allowlist for
  **read methods only**, so an internet `docker push` is challenged for a session no registry
  client can hold and dies at the front door. Re-allowlisting `/v2` writes there without restoring
  a guard here would open push to the internet — the gateway's `PublicPathsTest` and the comment in
  `RegistryRoutes.init` both hold that line.

The registry once guarded writes with a static token as an HTTP Basic password. That
bought a measured, awkward tradeoff — docker could push after a `docker login`, skopeo/podman
could not (their shared `containers/image` reads the non-challenging `/v2/` ping as "no
credentials needed") — and the guard's whole benefit dissolved once the platform settled on
trusted-network producers and gateway-terminated external auth, so it is gone rather than dormant.

### Deliberately not implemented

`DELETE` (405) and `/v2/_catalog` (404), because there is no garbage collection: the store is
append-only, untagged manifests and orphaned blobs accumulate, and that is acceptable at
private-deployment scale. Nothing should come to depend on deletion semantics before they exist.
`/v2/<name>/referrers/` is also absent; a manifest's `subject` is parsed and ignored rather than
required to resolve.

`sha512` content is rejected: the blob store is SHA-256 throughout — the content id *is* the
sha256 — so a `sha512:` digest answers `400 DIGEST_INVALID`, which is the spec's own SHOULD for an
unsupported algorithm. sha256 is the one algorithm the spec requires.

### Conformance

`RegistryTest` and `PackagedProcessIT` drive a client this repo wrote, so they can only assert the
reading of the spec that went into writing it. `OciConformanceIT` runs
[the upstream suite](https://github.com/opencontainers/distribution-spec/tree/main/conformance)
instead — several hundred assertions nobody here authored — against the packaged process.

It is **opt-in and run on demand**, never in a pipeline and never in a plain `verify`: the suite is
a Go binary with no published release, and a clone of this repo must keep building green with
nothing but a JDK. Build it once, then point the IT at it:

```
git clone https://github.com/opencontainers/distribution-spec.git
cd distribution-spec/conformance && go build -o conformance .    # needs Go >= 1.24

./mvnw verify \
    -Doci.conformance-binary=/abs/path/to/distribution-spec/conformance/conformance
```

Add `-Dit.test=OciConformanceIT` to run only this one, or `-Dnative` to exercise the GraalVM binary
instead of the fast-jar. **Without the property the IT skips**, which is what keeps `-Dnative` —
which flips `skipITs` — from starting to need Go.

The IT ensures the `conformance` repository over the REST API first (nothing is created implicitly),
then asserts on the suite's `junit.xml`: zero failures, zero errors. It parses that file rather than
trusting the exit code, because the binary's `main` *returns* — status 0 — when its config fails to
load, having written no report at all. Failures name the failing testcases and the path to
`report.html`, which carries every request and response.

**Three capabilities are declared `false`, and they are design decisions of this service, not
failing tests being hidden.** The suite has no notion of "this registry chose not to implement
that"; every optional API is a flag the operator declares, and leaving one on that the registry
never serves fails tests for an endpoint it states it does not have:

| Flag | Why |
|---|---|
| Flag | Cost of leaving it on | Why it is off |
|---|---|---|
| `OCI_API_BLOBS_DELETE`, `OCI_API_MANIFESTS_DELETE`, `OCI_API_TAGS_DELETE` | **0 failures** (283 tests move from Disabled to Skip) | `DELETE` is 405 by design — the store is append-only and there is no garbage collector, so deletion has no meaning here yet. See "Deliberately not implemented" above. |
| `OCI_API_REFERRER` | **+15 failures** | `/v2/<name>/referrers/` is absent. The spec makes it optional in as many words: a 404 is the defined "referrers API unavailable" signal with a mandated client fallback to the referrers tag schema, and the `OCI-Subject` response header is required only of "a registry implementation that supports the referrers API". |
| `OCI_DATA_SHA512` | **+81 failures** | The blob store is SHA-256 only, and answers `400` to a sha512 digest — the spec's own SHOULD for an unsupported algorithm. sha256, the required one, stays fully exercised. |

Everything the spec makes mandatory is left on. The costs above are measured, one flag at a time,
not estimated — and the first row is the one worth knowing: **the delete flags hide nothing.** The
suite tracks an endpoint that answers with a valid "unsupported" status rather than failing it, and
this registry's `405 UNSUPPORTED` is exactly that, so declaring the three is a statement of intent
and not a shield. Turn them on and the count stays 430 pass / 2 fail; only the label on 283 tests
changes.

#### Conformance status

Against `distribution-spec` `967efdc` (spec 1.1): **586 run, 0 failures, 0 errors, 6 skipped**,
under the capability declarations above.

The suite found two genuine non-conformances on its first run, and both are fixed. Recorded because
each was a deliberate design choice whose consequence was a wrong status code, and each is the kind
of thing a client-side test written from our own reading of the spec cannot catch — `RegistryTest`
and `PackagedProcessIT` drive a client built from the same reading that was wrong:

- **The final `PUT` of a chunked upload did not validate `Content-Range`.** An out-of-order `PATCH`
  was correctly refused with 416, but the final `PUT` skipped that check, appended the bytes anyway,
  and failed the digest comparison with `400 DIGEST_INVALID`. The same rejection with the wrong
  diagnosis: a resumable client was told its content was corrupt when its offset was stale. The spec
  makes 416 a **MUST** here. Fixed in `RegistryRoutes.finishUpload`; covered by
  `RegistryTest.theFinalChunkOfAnUploadMustStartWhereTheSessionStands`.
- **An invalid digest in a manifest reference answered 404.** `sha256:baddigeststring` matched
  neither alternative of the old `REF` regex, so it missed the route and hit the catch-all — telling
  a client the manifest was absent when its request was unusable. The spec asks for 400. `REF` now
  matches any non-slash segment and the handler judges well-formedness, the same stance the upload
  session id already took; covered by
  `RegistryTest.aMalformedReferenceIsRejectedRatherThanReportedAbsent`.

Both fixes are guarded by ordinary unit tests, not by the conformance suite: it is opt-in and needs
Go plus a checkout of the spec, so a regression must be catchable by `mvn verify` alone.

If the suite ever reports a failure, **do not widen the IT's assertion or filter the case out** —
either the registry is wrong, or a capability declaration above is.

## The npm registry

> **Both halves of this format ship in `qits-registries-npm`, and this service registers only the hosted one.** The pull-through cache half — its repository type, its upstream configuration and its eviction GC — is qits-platform-mirror's; "The proxy" below is what is left at the seam.

`NpmRoutes` serves the npm registry API at `/artifacts/npm/<repository>/…`. One repository type is
registered here, and it is the hosted one:

| Type | Wire name | Seeded row | What it is |
|---|---|---|---|
| `NPM_PACKAGES` | `npm-packages` | `npm` | hosted — accepts publishes |

Unlike `/v2` the mount point is an ordinary choice: npm accepts a registry URL of any depth, so
there is no second root-level segment and no gateway change — this lives inside the `/artifacts`
prefix like everything else. The segment is still a literal in the code, and `NpmRegistryTest` is
the only thing that would notice it drifting.

Cached upstream content and published content **never share a namespace**. That used to be a rule
about types and is now a fact about deployments: the cache is another service. Consumers still need
no merged "group" view, because npm does the routing client-side — one `.npmrc`, two addresses:

```ini
registry=http://qits-platform-mirror:8080/artifacts/npm/npmjs/         # everything, through the cache
@qits:registry=http://qits-platform-artifacts:8080/artifacts/npm/npm/  # ours, from the hosted repo
```

The `npm` row is seeded at startup alongside `qits`, `maven`, `daemons`, `docs` and `sboms`, so a fresh
deployment needs no manual step. Additional repositories are created with the ordinary ensure
endpoint, `{"type":"npm-packages"}`.

### The wire

```
GET    /artifacts/npm/<repo>/<pkg>                    packument
GET    /artifacts/npm/<repo>/<pkg>/-/<file>.tgz       tarball
PUT    /artifacts/npm/<repo>/<pkg>                    publish
DELETE anywhere                                       405 — no unpublish, no GC
anything else under the base                          JSON 404; npm degrades gracefully
```

A **scoped name arrives percent-encoded** (`GET /@qits%2fangular`) and Vert.x' `normalizedPath()`
leaves the escape alone, so the route grammar matches the encoded form and the handler decodes.
Both spellings resolve, because the tarball URL this registry emits carries a real slash and a
client follows it verbatim. `NpmPathsTest` pins the grammar; `NpmRegistryTest` pins that the router
actually behaves that way.

The **packument is derived state** for a hosted repository: assembled per request from `npm_version`
+ `npm_dist_tag` rows and never stored, so it cannot become a second source of truth — the same
reasoning that keeps OCI tags in one table. `Accept: application/vnd.npm.install-v1+json`, the
abbreviated form both npm and pnpm send, is answered with the full document. That is spec-legal (the
abbreviated type is an optimization a registry may decline) and it is the honest first
implementation: trimming it is a bandwidth change, and trimming it wrong silently breaks an install
that needed a field we dropped.

**Publishing** decodes the base64 `_attachments` tarball, recomputes `shasum` (sha1) and `integrity`
(sha512 SRI) and rejects a mismatch with the client's claim — the npm restatement of "a blob that
does not hash to its name is not a blob". The bytes then go through `BlobStore` like everything
else, so a tarball's *storage* key is its sha256 while npm's two hashes are stored columns
re-emitted in packuments; the store stays sha256-only. **Versions are immutable**: re-publishing one
is `403`, and so is publishing over a version garbage collection removed — a version name is never
reused, even after its row is gone (see "Garbage collection"). Only `npm_dist_tag` mutates.

**`latest` only moves forward.** It is the one dist-tag with an ordering rule: a publish may not
point it at a version sorting below the one it names, by semver precedence — and a prerelease sorts
below its own release. The rule exists because a bare `npm publish` means `--tag latest`, so a main
build publishing `<release>-main.g<sha>` would take `latest` backwards permanently and every
consumer installing without a range would get a main build. Publish prereleases under their own tag
(`npm publish --tag main`); every tag other than `latest` moves anywhere. A first `latest` is always
allowed, an equal one is a no-op, and a version that does not parse as semver is refused rather than
passed through. The refusal is a `403` that takes the whole publish with it, exactly as the
immutability one does.

`dist.tarball` **must be absolute** — npm refuses a relative one, so the OCI registry's
path-form-`Location` trick does not transfer. It is built from `X-Forwarded-Host`/`X-Forwarded-Proto`
when present and from the request's own authority otherwise, and **no config key names it**: the
gateway emits the `X-Forwarded-*` set on every proxied request by default, and a qits-net client
dialling `qits-platform-artifacts:8080` has no forwarding hop, so the request always carries the right answer
while a configured value would be right for one caller and quietly wrong for the other.

### The proxy

It is not here. The `npm-proxy` type, the `qits.artifacts.npm.proxy.*` keys, the packument TTL with
its `ETag` revalidation and its serve-stale behaviour, and the tarball miss path are
**qits-platform-mirror**'s, at its own address and in `qits-mirror-platform-service`'s README. The `npmjs` row is seeded
there.

What this side guarantees: the hosted registry above is unchanged, `NpmProxyProfile` is excluded
from bean discovery so `{"type":"npm-proxy"}` is a `400` naming the registered types, and — unlike
the OCI mirror — **no `npm-proxy` row was ever prefilled by a migration**, so a fresh database never
had one and V14 has taken out whatever a carried-over deployment brought. So there is nothing left
behind to omit from a listing, and no path through this service dials npmjs: `NpmUpstream` still
rides in on the shared jar, and reaching it needs a repository row of a type that cannot be
created.

### No login here either

**None. Not a token, not a guard, nothing** — the OCI registry's threat model verbatim (see "No
login, in either direction" above). Producers and consumers are internal, dialling
`qits-platform-artifacts:8080` on qits-net, and from outside `/artifacts/npm/**` falls under qits-gateway's
usual session auth like any other non-allowlisted artifacts path. No `PublicPaths` entry, no method
split, nothing npm-specific; whether an npm client can operate *through* that auth from outside is
deliberately out of scope.

The one wrinkle is client-side and never reaches the wire: the npm CLI has historically refused
`npm publish` when no credential is configured for the target registry (`ENEEDAUTH` is a pre-flight
check). If current npm still enforces it, a pipeline's `.npmrc` carries one dummy `_authToken` line
that **this server neither reads nor knows about** — npm-client ceremony, not an auth scheme, and it
disappears the moment npm accepts an anonymous publish.

### Deliberately not implemented

`DELETE` (405), for the same reason as on `/v2`: the store is append-only and there is no garbage
collector, so unpublish has no meaning here yet. `/-/v1/search`, `/-/npm/v1/security/audits/*`,
`/-/whoami` and the login handshake are absent rather than stubbed — npm degrades gracefully on a
404 for every one of them, so what matters is that the 404 carries npm's `{"error": …}` shape
instead of Vert.x' HTML page. Dist-tag mutation APIs (`npm dist-tag add`) are absent too:
publish-if-absent is the versioning convention, so a tag only ever moves as part of a publish.

## The maven repository

> **Both halves of this format ship in `qits-registries-maven`, and this service registers only the hosted one.** The pull-through cache half — its repository type, its upstream configuration and its eviction GC — is qits-platform-mirror's; "The pull-through cache" below is what is left at the seam.

`MavenRoutes` serves a maven repository at `/artifacts/maven/<repository>/<path…>`, the npm shape
verbatim: npm lives at `/artifacts/npm/npm/<pkg>`, so the platform's own library deploys to
`/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar`. The
first segment is the `artifact_repository` row; `maven` (type `maven-packages`, hosted) is seeded at
startup alongside `qits`, `npm`, `daemons`, `docs` and `sboms`. Maven accepts a repository URL of
any depth, so — like npm and unlike
`/v2` — there is no root-level segment, no gateway change, and no client-side routing story beyond
declaring the repository.

```
GET|HEAD /artifacts/maven/<repo>/<path…>          an artifact, a pom, metadata, a checksum
PUT      /artifacts/maven/<repo>/<path…>          deploy — mvn deploy / gradle publish compatible
DELETE   anywhere under the base                  405 — no undeploy; the append-only stance, verbatim
anything else under the base                      404 with a short text body, never the SPA's HTML
```

The server is a **dumb path store**. `mvn deploy` PUTs the jar, the pom, then each file's checksums,
then its merged `maven-metadata.xml` with checksums — one request per file, no session, no lock —
and resolution is GETs of the same paths. Every byte goes through `BlobStore` like everything else,
so jars dedupe globally with image layers and tarballs. The whole of the server's intelligence is
derivation, never rewriting:

- **`maven-metadata.xml` is derived state**, the packument precedent at two levels. At artifact
  level, `<versions>` is the distinct version directories present, `<latest>` the highest by maven
  ordering and `<release>` the highest non-`SNAPSHOT` one. At version level, a snapshot directory's
  timestamped filenames parse back into the `<snapshotVersions>` a resolver maps
  `1.0.1-SNAPSHOT` through. **The client's own metadata PUT is accepted and discarded** — refusing
  would break `mvn deploy` on its final request; storing it would serve a merge that goes stale on
  the next deploy, the second source of truth the derived document exists to prevent. A snapshot
  directory holding only literal `-SNAPSHOT` files (a non-unique deploy) answers **404**
  deliberately, because the resolver's defined fallback for a missing document is exactly that
  literal filename.
- **Checksums are derived at GET and verified at PUT, never stored.** Every stored file serves
  `.md5`, `.sha1`, `.sha256` and `.sha512` siblings computed from the blob bytes; a checksum a
  client PUTs is recomputed against the referenced blob and refused `400` on mismatch — the npm
  `requireClaimMatches` restated. A match stores nothing, because a derivable value stored is a
  value that can only ever disagree.
- **Releases are immutable, and the path space has three classes.** Release paths and timestamped
  snapshot files (unique by construction — one deploy, one filename) refuse a redeploy with
  different bytes at `403`; an identical redeploy is a `201` no-op, because deploy retries are
  normal. A **literal `-SNAPSHOT` filename** is the one mutable path — the coordinate is a moving
  target by definition — and serves with `no-cache` rather than `immutable`.

**No login here either** — the OCI/npm threat model word for word, with one less wrinkle than npm:
maven sends no credential unless challenged, this server never challenges, so a pipeline's
`distributionManagement` needs no matching `<server>` entry. From outside, `/artifacts/maven/**`
falls under qits-gateway's ordinary session auth like any other non-allowlisted artifacts path.

The deploy `PUT` streams rather than buffers, capped by `qits.artifacts.maven.max-artifact-size`
(default 128M) — the one size answer for both directions a jar can travel, the npm
`max-publish-size` precedent.

### The pull-through cache

It is not here. The `maven-proxy` type, the `qits.artifacts.maven.proxy.*` keys, the
cached-rather-than-derived `maven-metadata.xml` with its TTL and revalidation, the pulled-through
upstream checksums and the `central` row are **qits-platform-mirror**'s, at its own address and in
`qits-mirror-platform-service`'s README.

What this side guarantees: the hosted repository above is unchanged — the derived metadata, the
derived checksums and the three path classes are exactly as described. `MavenProxyProfile` is
excluded from bean discovery, so `{"type":"maven-proxy"}` is a `400` naming the registered types,
and no `maven-proxy` row was ever prefilled by a migration — V14 takes out whatever a carried-over
deployment brought — so nothing is left behind to omit from a
listing and no path through this service dials Central. A build resolving third-party jars declares
the mirror as its repository; `distributionManagement` still names this one.

## The daemon binaries

`DaemonRoutes` serves the platform's own executables at `/artifacts/daemons/<name>/<version>`.
`daemons` (type `daemon-binaries`) is seeded at startup alongside `qits`, `npm` and `maven`.

```
PUT      /artifacts/daemons/<name>/<version>      publish — streams; 201 with the computed digest
GET|HEAD /artifacts/daemons/<name>/<version>      the binary, version-addressed
DELETE   anywhere under the base                  405 — no delete; the append-only stance, verbatim
anything else under the base                      404 with a short text body, never the SPA's HTML
```

Unlike the other three surfaces there is **no repository segment**: this one serves the platform's
own daemons and nothing else, so the first segment after the base is the daemon's name. A second
namespace would be a design decision rather than a path someone can mint by typing one.

**The row write IS the publish.** One request stages the bytes, promotes them into `BlobStore` and
inserts the `daemon_binary` row, in one transaction — so there is no way to store a daemon's bytes
without an identity. That is the hole this type exists to close: the bootstrap used to upload the
ci-daemon through the OCI blob-upload session, which promotes bytes and writes no row by
construction, and the consequence was measurable — **every row-less byte in the store was a
ci-daemon build**, so `orphanBytes` reported a live executable that every build downloads as
garbage-shaped. `daemon_binary.blob_id` is now one of the census's live sets.

**Versions are immutable**: re-publishing an existing `(name, version)` is `409`, even for identical
bytes. That differs from maven's idempotent re-deploy on purpose — a maven deploy sends one file per
request and retries are routine, while a daemon publish is one request from one release pipeline, so
a second one means the version was reused or the release ran twice. The response carries the
computed digest, which is what a release pipeline pastes into a deployment.

**Two download spellings, deliberately.** The digest-addressed blob route on `/v2` is *untouched*:
the launcher, the `qits.ci.daemon-binary-url-template` and every existing `QITS_CI_DAEMON_VERSION`
pin keep working exactly as they are, and the digest stays what the pin holds. The version-addressed
`GET` here is the readable second spelling, safe to add only because a version pointer never moves;
it answers with `Docker-Content-Digest` so a consumer can check it against its pin without a second
request.

**Nothing here is authenticated**, in either direction — the same posture `/v2`, `/artifacts/npm`
and `/artifacts/maven` hold, and deliberately not a weaker one. On qits-net producers are trusted,
which is what lets a release pipeline publish with no credential store. Integrity does not come from
write auth: a version is immutable, so an open publish can add a version and can never change one,
and a consumer pins the digest this route echoes, so what a launcher runs is decided by content
addressing. Machine auth arrives wholesale with qits-platform-idp, for every publish path at once — gating
this one alone would report a posture the other three do not have. `DaemonOpenPublishTest` pins it
with the machine-token gate turned on, beside the npm and registry twins.

Reads are anonymous for the extra reason that the cold-start path is a bootstrap script with no
credential: a fresh platform has to be able to fetch a daemon before it has any CI to mint one with.

The publish `PUT` streams rather than buffers, capped by `qits.artifacts.daemon.max-binary-size`
(default 256M). It uses **no `BodyHandler`**, and that is the point: vertx-web defaults one to
10 MiB and the binary is 43 MB, so the obvious implementation would have 413'd every real publish
while passing every test small enough to be quick.

## The documentation bundles

`DocsRoutes` serves published documentation sites at `/artifacts/docs/<repository>/<site>/-/<version>`.
`docs` (type `docs`) is seeded at startup alongside `qits`, `npm`, `maven`, `daemons` and `sboms`.

```
PUT  /artifacts/docs/<repo>/<site>/-/<version>          publish one bundle — a streamed .tar.gz
GET  /artifacts/docs/<repo>/<site>/-/<version>/<path>   one file of it, zero-copy
GET  /artifacts/docs/<repo>/<site>/-/<version>          that version's metadata, as JSON
GET  /artifacts/docs/<repo>/<site>                      its versions, newest first
DELETE anywhere under the base                          405 — no delete; the append-only stance
anything else under the base                            404 with a short text body, never HTML
```

A `<site>` is whatever namespacing the publishing project already uses — `@qits/ui-components` where
there is an npm package, `someproject/somelib` where there is not, up to four segments. **The `/-/`
between the name and the version is npm's separator, reused**: `/artifacts/npm/<repo>/<pkg>/-/<file>`
has had it all along, and a multi-segment name needs a marker or `<name>/<version>/<path>` could be
split more than one way. What closes the ambiguity is the segment shape rather than route ordering —
a bare `-` cannot begin a name segment, which `DocsPathsTest` pins.

**A whole bundle is one request, and a version is the unit of everything.** The `PUT` streams a
`.tar.gz` to a temp file, walks it entry by entry into `BlobStore`, and writes one `docs_site` row
plus one `docs_file` row per path — **all in one transaction**. So a version is either wholly
published or not published at all, which matters more here than for any single-file type: a
half-written site is one that lists itself and then 404s its own stylesheet, which reads as a broken
deployment rather than a failed publish. Eviction is the same shape from the other end — the
`docs_file` foreign key cascades, so a GC strategy plans one candidate per *version* and there is no
coordinate for a per-file one.

**Exploded into blobs, not stored as a tarball**, and the dedupe is the reason. Every file is an
ordinary content-addressed blob, so serving one is a row read and a `sendFile` with no unpacking and
no extraction cache — and, far more valuably, unchanged fonts and chunks are byte-identical between
releases and are stored once. Measured on the real Storybook bundle: 53 files, 9.93 MB, and a second
version differing only in `index.html` added **one** blob.

**Versions are immutable**: re-publishing is `409` even for identical bytes, `daemon_binary`'s stance
and its reasoning. That is what lets qits-platform-docs be stateless — `latest` is a query over these
rows rather than a pointer something has to keep correct.

**A hostile archive has nowhere to land, structurally.** An entry's bytes go into `BlobStore` under
their own SHA-256 at a path it computes; the entry's *name* survives only as a database string, so
there is no `resolve(entry.getName())` anywhere in this service. Traversing and absolute names are
refused anyway, as defence in depth. What is left is size, and a compressed archive makes that cheap
to send — so the cap (`qits.artifacts.docs.max-bundle-size`, default 256M) is checked against the
**uncompressed** running total and the file count, not against `Content-Length`, which measures the
wrong number entirely.

**Nothing here is authenticated**, the posture `/v2`, `/artifacts/npm`, `/artifacts/maven` and
`/artifacts/daemons` all hold, and for the same reason: a version is immutable, so an open publish
can add one and can never change one.

`media_type` is resolved from the file **extension** at publish and stored, never sniffed —
`MediaTypeSniffer` has no `woff2` entry and would reject exactly the files a static site is made of.

## The SBOM store

`SbomRoutes` serves one CycloneDX document per released artifact at
`/artifacts/sboms/<packageType>/<packageName>/-/<version>`. `sboms` (type `sboms`) is seeded at
startup alongside the other hosted roots.

```
PUT  /artifacts/sboms/<type>/<name>/-/<version>   publish one document — a streamed JSON body
GET  /artifacts/sboms/<type>/<name>/-/<version>   the document, byte for byte, with its specVersion
GET  /artifacts/sboms/<type>/<name>               its stored versions, newest first
DELETE anywhere under the base                    405 — no delete; the append-only stance
anything else under the base                      404 with a short text body, never HTML
```

**The path IS the `SoftwareRelease` identity** — `(packageType, packageName, version)` spelled
exactly as qits-ci announces it, so a consumer of that event fetches the document with no
translation step. `packageType` is one of `npm`, `maven`, `docker`, `daemon`; the name is variable
depth (`eu.wohlben.qits:qits-eventstream`, `@qits/ui-components`, `qits/qits-artifacts`), which is
why `/-/` separates it from the version, npm's separator reused as `DocsPaths` reuses it. There is
**no repository segment**: there is exactly one `sboms` row, so a segment naming it would be a
constant every publisher has to get right.

**A re-PUT is `200 alreadyPublished`, not the daemon's `409`.** This publish sits *inside* a release
run, one step after the artifact publish, and release steps are retried and replayed — a 409 would
turn every replay of a green release into a red one over a document already exactly where it
belongs. The property that matters is immutability, which holds either way: the stored bytes never
change, and the route echoes their digest.

**Nothing here is authenticated**, the posture every other wire under `/artifacts` holds, and the
publish streams rather than buffers — no `BodyHandler`, capped by `qits.artifacts.sbom.max-size`
(default 16M).

GC keeps the last 2 **released** documents of every package and nothing else — the window is `P0D`,
so a third-oldest document goes on the run that finds it. Nothing pins one by coordinate, and that
is still the right shape: qits-platform-maintenance re-reads a live artifact's document on its scan
cadence, and a pin source restating what the belt already keeps would be one keep-set decided twice.
See "Garbage collection".

## The explorer API

The `GET`s under `/artifacts/api` answer the one question this service could not: **what is in
here, and what does it cost.**

None of the protocol surfaces can answer it, and that is by design in each case. `/v2/_catalog` is
refused here; `tags/list` returns `200` with an empty array for an image that does not exist, so
discovery by probing is impossible. npm's `/-/all` and `/-/v1/search` are absent. And
`GET /artifacts/api/repositories/{repo}/blobs` answers `{"records":[]}` for every protocol
repository, because those paths never write `artifact_record` — it looks like an empty registry and
is not. So these routes are new machinery rather than a view over an existing one.

All are reads, and **a read here is `@RolesAllowed({"qits:admin", "qits:agent"})`** — every browse controller
carries the annotation at class level, answered from the `X-Qits-User`/`X-Qits-Roles` pair
qits-gateway asserts for a signed-in operator. `AdminWriteGuard` is a *separate* mechanism and
covers write methods only (`MachineAuth.require()` under the `qits.auth.machine.required` rollout
gate), so it is correct that no read passes through it — but that does not make a read open, and
the retired `ArtifactsTokenFilter` / `X-Artifacts-Token` scheme this section used to name is gone.
Every operation carries `@Operation(hidden = true)` as everything here does, so `docs/openapi.yml`
stays `paths: {}` and the contract is written out below instead.

```
GET /artifacts/api/repositories                                          every repository
GET /artifacts/api/repositories/{repo}/images                            an OCI repository
GET /artifacts/api/repositories/{repo}/images/{image}/tags               one image's tags
GET /artifacts/api/repositories/{repo}/images/{image}/manifests          every manifest, tagged or not
GET /artifacts/api/repositories/{repo}/packages                          an npm repository
GET /artifacts/api/repositories/{repo}/packages/{package}/versions       one package's versions
GET /artifacts/api/repositories/{repo}/maven-packages                    a maven repository
GET /artifacts/api/repositories/{repo}/maven-packages/{coord}/versions   one coordinate's versions
GET /artifacts/api/repositories/{repo}/daemons                           a daemon-binaries repository
GET /artifacts/api/repositories/{repo}/daemons/{daemon}/versions         one daemon's versions
GET /artifacts/api/repositories/{repo}/docs                              a docs repository
GET /artifacts/api/repositories/{repo}/docs/{site}/versions              one site's versions
GET /artifacts/api/store/summary                                         the whole store, ten ways
```

**`/repositories/daemons/daemons` reads oddly and is the design.** The daemon *wire* carries no
repository segment — the seeded `daemons` row is the only namespace a publisher can reach — but the
explorer's subject is an `artifact_repository` row throughout, repositories and then what is inside
one. Giving this one type a second addressing scheme would make the SPA's drill-down a special case
for it, and the URL stays correct on the day a second namespace is a decision somebody takes.

`GET /artifacts/api/mirror-upstreams` was a fifth route and is gone with its controller — which
public registry is mirrored is qits-platform-mirror's question, and it answers it.

| Route | Body |
|---|---|
| `repositories` | `{"repositories":[{"name","type","createdAt","itemCount","sizeBytes"}]}` |
| `images` | `{"images":[{"name","tagCount","manifestCount","sizeBytes"}]}` |
| `tags` | `{"tags":[{"tag","digest","sizeBytes","createdAt","accessedAt"}]}` |
| `manifests` | `{"manifests":[{"digest","mediaType","sizeBytes","createdAt","accessedAt","tags"}]}` |
| `packages` | `{"packages":[{"name","versionCount","latest"}]}` |
| `versions` | `{"versions":[{"version","tarballSizeBytes","publishedAt","accessedAt","distTags"}]}` |
| `maven-packages` | `{"packages":[{"name","versionCount","sizeBytes"}]}` |
| maven `versions` | `{"versions":[{"version","files","sizeBytes","publishedAt"}]}` |
| `daemons` | `{"daemons":[{"name","versionCount","latestVersion","latestPublishedAt","sizeBytes"}]}` |
| daemon `versions` | `{"versions":[{"version","digest","sizeBytes","publishedAt","accessedAt"}]}` |
| `docs` | `{"sites":[{"name","versionCount","latestVersion","latestPublishedAt","sizeBytes"}]}` |
| docs `versions` | `{"versions":[{"version","fileCount","sizeBytes","publishedAt","accessedAt","metadata"}]}` |
| `store/summary` | `{"ociPerImageSumBytes","ociUnionBytes","ociMirrorBytes","orphanBytes","npmPublishedBytes","npmProxyTarballBytes","npmProxyPackumentBytes","mavenPublishedBytes","mavenProxyBytes","daemonBinaryBytes","diskTotalBytes"}` |

**The summary's four cache figures are structurally zero**, and they keep their places in the record
rather than being removed: this service registers no cache type, so no repository row can carry one
and the census has nothing to attribute — but "nothing cached" is an answer a reader needs, whereas a
missing field reads as a figure someone forgot. qits-platform-mirror reports its own.

Details a client trips over if it does not know them:

- CI `blobs`, OCI `tags`, and OCI `manifests` accept inclusive `accessed-after`,
  `accessed-before`, `created-after`, `created-before`, `min-size`, and `max-size` filters.
  `never-accessed=true` selects null access timestamps; `never-accessed=false` selects rows that
  have been read. A null timestamp does not match an access range. Invalid or inverted bounds are
  400. CI records expose the nullable `accessedAt` beside their existing fields.
- Client content reads are coalesced to at most one timestamp write per row per hour. A CI blob URL
  identifies content, so it touches every record in that repository naming that digest. An OCI tag
  manifest pull touches its tag and resolved manifest; a digest pull touches the manifest only.
  Layer blobs remain untracked because a globally deduplicated blob cannot be attributed to one
  manifest or tag from its request.
- The three protocol tables track the same way (V11), on the same hour, so the settled GC can reason
  about every type it is configured for. An npm tarball `GET`/`HEAD` touches its `npm_version` row.
  A maven
  `GET`/`HEAD` of a stored file touches its `maven_artifact` row; derived `maven-metadata.xml` and
  derived checksums touch nothing, because they are not that row's bytes. A daemon `GET`/`HEAD`
  touches its `daemon_binary` row. **The digest-addressed daemon download on `/v2` is deliberately
  unattributed**, exactly as layers are: it resolves an OCI repository and a globally deduplicated
  digest, so the request names no daemon — what keeps a digest-fetched daemon alive is the live pin,
  never an access timestamp.

- **`itemCount` means something different per type**, on purpose: images for `oci-images`, packages
  for `npm-packages`, deployed files for `maven-packages`, published versions for
  `daemon-binaries`, `artifact_record` rows for the two CI
  types. One number with a type-dependent meaning beats five that are always null.
- **A repository whose type no profile here claims is not listed at all**, and `itemCount` refuses
  it rather than answering zero. Since V14 no such row should exist — that rule covered V7's
  `oci-mirror` prefill and now covers only a cache row a deployment kept because content is still
  cached under it. Counting one would mean answering "how many things are in it" about a type this
  service cannot read, and reporting zero would read as an empty repository rather than as somebody
  else's.
- **404 is an unknown repository; 400 is a repository of the wrong type.** An npm repository has no
  images and never will, and reporting that as an empty list would read as an image registry that
  lost its images.
- **An unknown *image* is `200` with an empty list**, not a 404 — an image is not a row, so there is
  nothing to be absent. The same answer `tags/list` gives.
- **`{image}`, `{package}` and `{site}` may contain slashes**, and both spellings resolve:
  `build-images%2Fci-base`
  and `build-images/ci-base`, `@qits%2Fui-components` and `@qits/ui-components`,
  `@userflows%2Fqits-artifacts` and `@userflows/qits-artifacts`. `qits/build-images/ci-base`
  is repository `qits`, image `build-images/ci-base`; every scoped npm name has a slash in it too,
  and a docs site like `@userflows/qits-artifacts` is **one site name**, not a scope and a name.
  **The docs *wire* accepts only the literal spelling** — `DocsPaths` deliberately has no
  percent-encoded separator, because its publishers are `curl` and qits-docs — while this surface is
  reached from a browser and has to answer both. `{daemon}` is the exception and takes a plain
  segment: a daemon name is bounded to `[a-z0-9][a-z0-9._-]{0,63}` and cannot contain a slash, so
  the question does not arise.
- **The daemon and docs listings are the answer to a question their wires cannot be asked.**
  `/artifacts/daemons` 404s on the bare segment and every route under it is version-addressed, so
  nothing there enumerates what exists. Docs has an open wire catalog (`GET /artifacts/docs/<repo>`,
  tokenless because qits-platform-docs renders it) which carries no size — a byte figure on an open
  route is an inventory of the store to anyone who can reach it — so these are two readers over the
  same rows rather than one reader with a flag. **Listing a docs version's files is deliberately
  absent here**: the wire answers it at `GET /artifacts/docs/<repo>/<site>/-/<version>`, and a
  second spelling would be a third place the same list is assembled.
- **Every size on those four routes is a union over distinct blob ids, never a sum**, and that
  matters most exactly here. Two daemon versions built from identical bytes (a rebuild that changed
  nothing, a calver re-tag of an adopted digest row) are one blob; docs versions share fonts and
  unchanged chunks byte-for-byte across releases, so summing `docs_file.size_bytes` overstates a
  Storybook-shaped site several times over. `DocsSite.totalBytes` *is* that sum and is deliberately
  not what is reported: it sizes the bundle as published, not the disk. The one row-level exception
  is a daemon *version*, which is one blob and has nothing to double-count; `fileCount` likewise
  stays a row count, because it answers "how many paths does this version serve".
- **`accessedAt` is null until a versioned download**, on both, and null means *never read since
  tracking began* rather than "read long ago" — which is why it is nullable rather than zero. A
  daemon's version-addressed `GET`/`HEAD` moves it; the digest-addressed `/v2` blob route
  deliberately does not, exactly as layers are unattributed. Any file `GET` of a docs version moves
  that version's. Both coalesce to at most one write per row per hour.
- **An unknown daemon or site is `200` with an empty list, not a 404** — neither is a row, so there
  is nothing to be absent; only the repository can be. `404` is an unknown repository and `400` is a
  repository of the wrong type, the same two answers the image and package listings give.
- **A daemon version's `digest` is the wire spelling `sha256:<hex>`**, not the stored hex — the
  exact string `DaemonRoutes` echoes as `Docker-Content-Digest` and returns in the publish receipt,
  which is what a deployment pins. An operator copying it out of this listing must not end up
  pinning a value the wire never uttered.
- **A docs version's `metadata` is the flat `X-Artifacts-Meta-*` map the publisher rode in with** —
  `git.branch.name`, `git.commit.hash`, whatever else it chose. Never null: a version published
  without any is an empty map, because "no metadata" and "metadata not read here" must not look the
  same on this surface.
- **`digest` is the wire form** `sha256:<hex>`, not the bare hex the database stores — it is what a
  person pastes into a pull by digest.
- **`createdAt` on a tag is `oci_tag.updated_at`**, when the tag last came to name that digest. A tag
  is the registry's one movable pointer and has no other timestamp; for a tag that never moves —
  which is every commit-sha tag this platform pushes — it is when the image was pushed.
- **`tarballSizeBytes` may be null.** There is no size column on `npm_version`; the figure is the
  file's size on disk, and a row can outlive its bytes. Zero would read as an empty tarball.

### Sizes, which are the only new machinery

**A size on this store is a set union, never a sum.** Blobs are content-addressed and deduped
globally — across types and across repositories — so adding two overlapping figures overstates the
store. Measured three ways over the same content:

```
sum over all 155 manifest ROWS  (per-tag sizes, added up) : 10.63 GiB
sum over 10 IMAGES              (per-image unions, added) :  4.36 GiB
TRUE UNION across everything OCI                          :  4.04 GiB over 564 blobs
```

The inflation is almost entirely *within* an image: every rebuild shares its base layers with the
previous tag. So the **per-image union is the headline number** (1.08× the truth), a per-tag figure
is never shown as if it were additive (2.63×), and the true union and the orphan bytes are named on
the store summary beside it. An unlabelled byte count over a deduped store is a lie with a number
in it.

Where the numbers come from, given that **96.3% of the stored bytes have no database row** — OCI
layers and configs get none by design, and `oci_manifest.size_bytes` is the size of the manifest
*JSON*, three thousandths of the store:

- **`OciManifestFootprints`** parses the stored manifest documents and reads the `size` fields
  inside them. Those are the numbers a manifest's digest covers, so they cannot drift; parsing all
  155 of this store's manifests found zero mismatches and zero missing referenced blobs. An index
  recurses into its children. There is no `oci_blob(digest, size)` table, and that is the deliberate
  first cut — it is what survives a registry ten times this size, and it is not needed at this one.
- **`BlobDiskIndex`** walks the blob directory (1450 files, two levels) for what is actually there.
  It is the only thing that can see the orphans, and the only source of an npm tarball's size.

**Caching and invalidation**, which are two different problems here:

- A **footprint is cached forever and never invalidated**, and that is a property of the key rather
  than an oversight: a manifest is content-addressed, so the bytes behind `(repository, image,
  digest)` cannot change. A push adds a key; it can never make an existing one wrong.
- The **aggregates are not cached at all** — per-image, per-repository and store-wide unions are
  recomputed from the manifest rows on every request. That is a few thousand map merges over an
  index scan, and it is cheaper than being wrong after a push.
- The **disk index is invalidated by the write.** Every byte this service stores lands through
  `BlobStore.promote` — the registry's layers, npm's tarballs and publishes, maven's deploys, the
  daemon and docs uploads, the JSON API's — so that one call is the complete set of events that can
  make a directory listing stale, and it is where `invalidate()` is called from. A 60-second age
  ceiling is a second belt for what a process cannot see: a volume restored under it, or a sibling
  writing the same directory. A test that wipes the directory says so explicitly.

The eleven figures reconcile in exactly one way, which is the panel's whole claim:

```
diskTotalBytes = ociUnionBytes + npmPublishedBytes + npmProxyTarballBytes
               + mavenPublishedBytes + mavenProxyBytes + daemonBinaryBytes + orphanBytes
```

`ociPerImageSumBytes` sits above `ociUnionBytes` by whatever images share, and
`npmProxyPackumentBytes` is outside the identity because those bytes are rows, not files.

**The four cache terms are structurally zero here** — no cache type is registered, so nothing can be
attributed to one. The identity had one known hole, and it was the split's leftover rather than a
bug in the arithmetic: bytes cached under a mirror row are still reached by their surviving
`oci_manifest` rows, so the census does not call them orphans, while `ociMirrorBytes` reports `0`.
They are in no term. V14 closed it for every deployment that cached nothing, which is all of them
that were bootstrapped fresh; where bytes really were cached the row is deliberately left standing,
because deleting it would strand them, and reclaiming those is an ops action.

**`orphanBytes` is not a rounding error, and it is the figure `daemon-binaries` exists to empty.**
This store holds 124 MiB in three ELF binaries — the ci-daemon — uploaded through the OCI
blob-upload session with no manifest, no tag and no row of any kind. They are servable, because
`serveBlob` validates the repository rather than that the digest belongs to the image, and there is
nothing else in the pool: the three sizes reconcile to `orphanBytes` with zero remainder. Anything
published through `PUT /artifacts/daemons/…` gets its row at publish time and lands in
`daemonBinaryBytes` instead; the three already on the volume move across when they are adopted,
which is an ops action rather than a migration — a lineage must not embed live-platform digests, and
a migration cannot verify one against the running store. Until then: report it; do not sweep it.

### Deliberately not here

- **Any link to a project.** Not one column in any of the six tables joins to one. `oci_manifest.repository`
  is `"qits"` for every row — that is the image namespace, equal to the project slug by naming
  accident — and `npm_version.package_name` does not even coincide. The one genuine cross-store link
  is `oci_tag.tag`, which is a git commit SHA, and it is undeclared.
- **Writes of any kind**, and "what is actually used". A tarball `GET` is a `sendFile` with zero
  database writes; last-accessed is `artifact-access-tracking.md`, which is the prerequisite for
  cleanup and is not started.

## Garbage collection

> **The cache engine is gone from this repo.** `CacheEvictionStrategy` and the three cache adapters went to qits-mirror-platform-service with the types they collect, and git pack GC went to qits-githost-service. What is left is the pin-based `OwnArtifactsStrategy` over the hosted types, the two CI stubs, and the reconciliation and sweep, which are type-agnostic and unchanged.

**Reading is the default; executing is a separate, deliberate act.** The dry-run plan shipped
first, the user reviewed it, and only then did the execute route land. Nothing executes without the
`POST`.

```
GET  /artifacts/api/gc/plan     what every type's strategy would delete, and what a sweep would unlink
POST /artifacts/api/gc/plan     the same report, over pins supplied in the body
POST /artifacts/api/gc/sweep    execute exactly that, once, and answer with the receipt
```

The sweep computes its own plan inside the request — there is no way to submit one, because a
stored plan is a plan on stale facts, and a plan on stale facts deletes a running image (the OCI
keep-set's cd pins are fetched inside the same request too). The receipt is the plan report's
executed twin: identities deleted per type, blobs unlinked with their bytes, what the grace window
withheld, the pins section the plan carries, and the untouchable pool restated.

**The grace window gates identity rows as well as blob unlinks.** A blob may only be swept by
*losing* its last identity row — so deleting a row while the blob's file is still inside the window
would strand the blob forever, row-less and untouchable. An identity whose released blobs include
one still in grace is therefore withheld whole: rows stay, the next run re-plans it, and row and
file mature out of the window together. On a store younger than the window a sweep provably
deletes nothing, and that no-op receipt is the mechanism's own safety proof.

The `POST` is `@RolesAllowed("qits:system")` — a sweep is a machine's call, made by
qits-platform-orchestrator — and, as a write under the `gc` prefix, it also passes through
`AdminWriteGuard`'s `MachineAuth.require()`. Stated honestly: that second belt is inert while the
`qits.auth.machine.required` rollout gate ships off, which is the standing qits-platform-idp
direction and the reason the guard could ship before the idp was deployed. The retired
`X-Artifacts-Token` filter it replaced is gone; per the recorded decision, no interim token scheme
is invented meanwhile. The
registries' `405` on client deletes (`/v2` manifests, npm unpublish) is untouched: no client gains
deletion semantics from any of this.

### Running a sweep (ops)

This platform has never deleted a byte, and the first sweep changes that. The choreography below is
what that first run does, and it is the **standing procedure** for every sweep after it — the first
one is not a special ceremony, it is the ordinary one performed while nobody yet trusts the result.

1. **Read the dry-run.** `GET /artifacts/api/gc/plan`. Start at `summary`: executable yes/no, what
   it would free, and which type is doing the work. Then check three things in the detail, because
   they are the three ways this goes wrong:
   - the **pins** section — every source `answered`, and its `keeps` are the shas your deployments
     are actually running and the daemon rungs qits-ci is actually on;
   - the **releases are on the kept side** — every type's `kept` list holds its last two releases,
     naming the release rule rather than an access window;
   - the **per-type windows** are the ones intended (`configuration[]`), and each type's dead list
     is content that really has gone cold for that long.
2. **Back up H2 and save the blob listing.** Copy the database file, and keep the plan's
   `sweep.blobIds` and `untouchable.blobIds`. The first is what the run is about to unlink; the
   second is the list to check the CI daemon binary against, and the pair is what makes an unwanted
   deletion answerable afterwards rather than only regrettable.
3. **Run one sweep, by hand.** `POST /artifacts/api/gc/sweep`, once. Nothing is scheduled and
   nothing runs on a timer; the plan it executes is computed inside that request.
4. **Verify four things, in this order:**
   - `GET /artifacts/api/store/summary` — the identity still balances
     (`diskTotal = ociUnion + npmPublished + mavenPublished + daemonBinaries + orphans`, the cache
     terms being zero here), which is the census and the disk agreeing after a delete;
   - a **qits-cd restart still pulls** — restart one deployed application and watch it come back
     from its pinned sha, which is the pins section proved against reality;
   - a **kept release still installs** — install one of the last two versions of a package the run
     touched, which is the release belt proved rather than assumed;
   - the receipt's `withheldByGraceWindow` — a young store withholds everything, and that no-op is
     the mechanism working rather than a failure to investigate.

Anything unexpected in step 1 is a reason to change configuration and re-read, not to run the sweep
and inspect afterwards: the plan is reviewable without side effects precisely so that argument
happens before the delete.

### Two layers, because identities and blobs are different questions

A blob is content-addressed bytes with no meaning of its own, so *"is this blob garbage"* is not a
question the blob store can answer. It lives one level up, in the repository types — and each type's
answer is its own.

- **Identity GC** is per type. It deletes *rows*: an `oci_tag`, an `npm_version`, an
  `artifact_record`, a pack description. It frees no bytes; it changes what the store *means*.
- **The blob sweep** is one mechanism with no policy at all. A blob may die only when **no type**
  reaches it any more, because the store dedupes globally across types and repositories.

That split is what keeps eight types collectable safely: a strategy never touches bytes, so no
type's rule can free a blob another type still needs.

**One rule per type used to mean one rule *class* per type; it does not any more.** This section
used to argue that docker's and npm's rules resemble each other by coincidence and must therefore
never share code — no base class, no retention-rule framework, no reuse. **That rule is superseded
by the user's decision of 2026-08-05** (the GC settlement; its history is in the superproject's
git), which replaced the
bespoke strategies with two engines chosen per type in configuration. The design below is that
decision; the argument above is history, and re-splitting an engine into per-type rules is now the
change to refuse.

What the old rule was protecting survives it, and is not softened: a type has exactly **one**
policy (two claimants are a collision the report names and never merges), the per-type **facts**
stay in that type's own adapter, and no engine may switch on `RepositoryType`.

### The settlement: two engines, configured per type

The doctrine, settled by the user on 2026-08-05: **two generic strategies,
not one bespoke rule per type, mapped onto the types by configuration.**

- **`CacheEvictionStrategy`** — a pull-through cache holds somebody else's re-fetchable content, so
  everything unaccessed past the window goes and a live pin is the only thing that stays regardless.
  **It is not on this classpath any more**: it went to qits-mirror-platform-service with the three
  types it collected. The doctrine is named here because the other engine is one half of a pair, and because
  a `cache` value on the mapping key below is still what a deployment would be refused for.
- **`OwnArtifactsStrategy`** — the platform's own artifacts keep the **last 2 released versions per
  identity group** whatever their age, plus everything a live pin names; the rest ages out. Anything
  older survives on *use* — an old release someone still installs is accessed — rather than on
  policy.

Both check pins **before** the access rule, because a pin is the one fact no timestamp implies: a
container running untouched for months still pulls its image sha on restart.

The rules live in the engines; the **facts** live in a `GcTypeAdapter`, one per type, sharing no
code at all — what an identity is, what a release is, which of two is newer, when each was last
touched, and how a row is deleted. That is the line the settlement drew instead of the old one:
types share a *rule* now, chosen for them by name, and each still owns everything that makes it
different. The eight `*GcStrategy` classes left over are **binders** across that line — a type, an
adapter, four lines — and a rule appearing in one is the settlement being unpicked one type at a
time.

```java
public interface GcTypeAdapter {
  RepositoryType type();
  List<GcCandidate> enumerate();                 // identities, each with its effective access time
  Comparator<GcCandidate> byAge();               // oldest first — "newest" is a per-type fact
  GcStrategy.Applied delete(Plan, GraceWindow);  // this type's own collection funnel
}
```

**Effective access time is `max(created/published/fetched, accessed_at)`** — creation counts as a
first access, so something cached or published an hour ago reads as young rather than never-read.
The adapter folds that in; an engine only compares it.

The mapping is `qits.artifacts.gc.type.<wire-name>.strategy` (`own` or `excluded` here) and
`….window` (ISO-8601), shipped in the `gc` jar's own `META-INF/microprofile-config.properties`.
Every **registered** `RepositoryType` must have an entry — a type with none is **refused**, not
defaulted, because a type nobody configured is a decision nobody took. The three cache lines left
with the engine, which is consistent: their profiles are not registered either, so no row can carry
one.

| type | strategy | window |
|---|---|---|
| `oci-images`, `npm-packages`, `maven-packages`, `daemon-binaries`, `docs`, `sboms` | `own` | `P0D` |
| `ci-screenshots`, `ci-videos` | `excluded` | — (a window beside a type nobody collects reads as a running rule) |

**All six windows went to `P0D` on 2026-09-05**, from `P3D` the day before and `P30D`/`P90D` before
that, and zero is a different kind of number than the two that preceded it: **retention IS the
keep-set now.** An identity lives because something names it — a pin source, a belt, or on
`maven-packages` the fact that it is a published release — and not because somebody pulled it
lately. The window was the last place where "in use"
was *inferred* from a timestamp, and a timestamp is the one input here nobody owns: a container
running untouched for months pulls its sha on restart, and a library nothing has resolved this week
is still what every build of its consumer needs. Age answered neither; the keep-classes answer both
by name.

**Why zero is safe**: per-push CI is retired, so nothing builds a bare branch. Every QA build
resolves the octopus **fold** with current `main`, and `main`'s manifests are exactly what the
maintenance dependency pins name — so a branch's build resolves the keep-set by construction. The
one branch that can miss is one hand-pinning an internal version already three releases stale, which
fails loudly at resolve time and is re-bumped in a commit. Raising a window is a decision about
disk; narrowing the pin coverage is what would make it one about correctness again — a pin source
removed is a window owed.

**The one clock left is the blob grace period** (`PT6H`), and it is race safety rather than
retention: bytes pushed minutes ago must outlive the gap until their pin appears. See "A grace
window" below.

**That argument did not survive contact with `maven-packages`, and one night later.** At `01:58Z` on
2026-09-05 the `P3D` window deleted 67 published `eu.wohlben.qits` maven coordinates and every gating
build on the platform stopped resolving with `Could not find artifact … in qits-maven-network`. The
pin coverage was not narrowed — the dependency source answered, with 114 pins across 96 repositories
— it was simply never wide enough to be a floor: it names what **main's manifests directly
reference**, and a build resolves branches, parent poms, transitive versions and every coordinate
main pinned before its last bump. So `maven-packages` no longer age-collects published releases at
all; its window governs superseded snapshot sets and nothing else. The reasoning is in
`MavenPackagesGcAdapter`'s javadoc and in the section on that type below. The other five windows
stand: an image, a tarball, a daemon binary, a docs site and an SBOM are each consumed by being
fetched, which is the property a jar does not have.

**The own engine is live over every configured type**, which is the first change to what dies on
this platform. What each one condemns, in one line each — the sections below carry the reasoning:

| type | engine, window | identity that dies | what keeps it | liveness expression |
|---|---|---|---|---|
| `oci-images` | `own`, `P0D` | a sha tag, or a manifest no tag and no tagged manifest reaches | the last 2 calver releases; a coordinate qits-platform-deployments pins; an image a repository's Dockerfile references; an image qits-configuration would configure; an image qits-workspaces or qits-projects would launch today; the tag literally named `latest`; the newest release per image | manifest closure over surviving tags and manifests |
| `npm-packages` | `own`, `P0D` — **prereleases only** | a published version, and only ever a prerelease | **every published release, at any age**; anything a dist-tag names; anything a repository's package.json still resolves to | `npm_version.tarball_blob_id` of survivors |
| `maven-packages` | `own`, `P0D` — **snapshots only** | a **coordinate** — one version's whole file set, and only ever a superseded timestamped snapshot set | **every published release, at any age and whatever the window says**; anything a repository's pom still references; the newest deployable set of every snapshot line; a path this layout cannot read | `maven_artifact.blob_id`, sized from the row |
| `daemon-binaries` | `own`, `P0D` | a `daemon_binary` row | the last 2 versions; both rungs qits-ci names; a pinned digest's bytes | `daemon_binary.blob_id`, sized from the row |
| `docs` | `own`, `P0D` | a published **version** of a site — never a file, because `docs_file` cascades | the last 2 versions of every site | `docs_file.blob_id` of surviving versions |
| `sboms` | `own`, `P0D` | one stored document, `packageType/packageName@version` | the last 2 released documents of every package | `sbom_document.blob_id`, sized from the row |
| `ci-screenshots`, `ci-videos` | `excluded` | **nothing** — no engine is configured, so nothing of them is ever deleted | everything | `artifact_record.blob_id`, reported live |
| `oci-mirror`, `npm-proxy`, `maven-proxy` | none here | **nothing** — no profile claims them, so `GcPlanner` omits them rather than reporting zeros | everything | qits-platform-mirror's, with the cache engine |

`GcTypeConfigTest` is the guard over that table and is edited **deliberately, once per workstream**:
a type whose dead set moves has its new set written out there, and every other type stays
identity-for-identity what it was.

The report carries the **configuration echo** beside the outcomes: `configuration[]` in
`GET /gc/plan`, one line per type with the configured strategy, the window and the effective rule as
a sentence. It is the half of a plan the outcomes cannot show — "nothing died" reads identically
whether the rule is right or the window is a year. The two `excluded` types say so **twice**: in
that echo, and on their own entry in `types[]`, because `dead: []` beside a claimed strategy would
otherwise read as a rule that ran and found nothing.

### Live pins, and the whole-run abort

Six services hold references into this store that nothing here can derive, and all six are read
**once at the start of every plan and every sweep**, never cached:

| source | what it pins | shape |
|---|---|---|
| `GET /platform-deployments/api/pins` (qits-platform-deployments) | image coordinates: what is serving, and what a rollback would restore, unioned over every environment | `{"pins":[{"applicationName":…,"shas":[…]}]}` |
| `GET /ci/api/daemon` (qits-ci) | the daemon ladder's top two rungs, which protect `daemon_binary` rows keyed `(name, version)` | `{daemonName, daemonVersion, previousDaemonVersion, source}` |
| `GET /maintenance/api/pins` (qits-platform-maintenance) | the internal maven, npm and docker versions repositories' manifests still **reference** on `main` — what source still builds against, which no pull implies | `{"generatedAt":…,"repositories":[…],"pins":[{"ecosystem","name","version","repository","manifestPath"}]}` |
| `GET /configuration/api/pins` (qits-configuration) | the container images the platform is **configured** to launch — the version the NEXT deploy of a launching service will be handed | `{"generatedAt":…,"pins":[{"image","version","application","key"}]}` |
| `GET /workspaces/api/pins` (qits-workspaces) | the workspace and editor images that service would pull **today**, out of the configuration it is actually running with | `{"generatedAt":…,"pins":[{"image","version","launches"}]}` |
| `GET /projects/api/pins` (qits-projects) | the agent and refinement images, on the same terms | `{"generatedAt":…,"pins":[{"image","version","launches"}]}` |

**The last four answer for consumption, and they are what a `P0D` window rests on.** The first two
say what is running; these say what is referenced, what would be configured and what would actually
be pulled. They join on a coordinate spelled exactly as the adapter spells its identities — `groupId:artifactId:version`,
`name@version`, and for images the **full** name (`qits/workspace-base:2026.902.143920`, repository
row plus image, which is what a Dockerfile and a configuration entry both write and what cd's
`applicationName` does not carry). The maintenance answer's `repositories` array is the scan's
freshness provenance: it is shape-checked and dropped, because deciding when an inventory is fresh
enough to act on is maintenance's call, made as a `503` that arrives here as a failure like any
other. An ecosystem this store cannot file is **refused**, not skipped — a pin filed nowhere is a
keep dropped in silence. `launches` on a launch pin is the same kind of provenance: parsed, carried
onto the record for the receipt, and deciding nothing — two kinds of start pulling one image are one
keep.

**The last two are the fourth source in a different TENSE, and they exist because it left a
residual.** qits-configuration holds the version the *next* deploy of a launching service will be
given; that service keeps launching whatever it was deployed with until the deploy happens. For as
long as that gap lasts the configured coordinate names an image nobody pulls while the one every
workspace start pulls is named by nothing — a live image held up by access alone, which is the
inference the short windows gave up on. So the consumer that pulls answers for itself, and the two
answers are kept as **separate keep-sets**: both services launch `qits/workspace`, possibly at
different versions, and a report that folded them together could not say which of them saved a tag.

All six are folded into one `GcPins` for the run. Two details are load-bearing and neither is visible
on the wire:

- **A blank `daemonVersion` is an answer**, not an absence: it means this deployment has adopted or
  pinned no daemon, which is the shipped default. Treating it as a failure would abort every run on
  a platform that has published none.
- **A 64-hex daemon version pins a blob as well as a row.** The pin has been a sha256 digest since
  the daemon shipped (`QITS_CI_DAEMON_VERSION`, fetched from the blob route), so it may name bytes
  no version-addressed row exists for.

**A source that cannot answer aborts the whole run.** Not the type — the run. `POST /gc/sweep`
returns a receipt with `aborted` naming the source, every type carrying that reason, and nothing
deleted, before the census is even taken. The reason it is all-or-nothing: blobs dedupe globally, so
a tarball one type releases may be the last reference to bytes a pinned image also names, and with
the pins missing nothing can tell.

`GET /gc/plan` does **not** fail — a report that 500s tells a reviewer nothing about the types that
are fine. It answers with `executable: false`, the failures in `pinFailures`, and every pin-dependent
type carrying a refusal instead of zeros nobody can interpret.

The keep is reported under the pin's own name — `pinned by a qits-platform-deployments deployment`,
`pinned by qits-ci daemon ladder`, `referenced by a repository manifest on main
(qits-platform-maintenance dependency pins)`, `a configured container image (qits-configuration)`,
`the image qits-workspaces would launch today (effective pin)`, `the image qits-projects would launch
today (effective pin)` — and both engines check it **before** the access rule, because a pin is the one fact no timestamp
implies.

#### Pins may arrive in the request

`POST /artifacts/api/gc/plan` and both sweeps take an optional body — `{"pins": {"deployments": <the
verbatim body of GET /platform-deployments/api/pins>, "ciDaemon": <the verbatim body of GET
/ci/api/daemon>, "dependencies": <GET /maintenance/api/pins>, "configuredImages": <GET
/configuration/api/pins>, "workspaceLaunches": <GET /workspaces/api/pins>, "projectLaunches": <GET
/projects/api/pins>}}` — and use those documents instead of the readers above. They are parsed by the
**same** code the readers use, so a keep-set does not change with the way the document travelled;
only the provenance does, and the report says so: the sources read `supplied:
qits-platform-deployments`, `supplied: qits-ci`, `supplied: qits-platform-maintenance`, `supplied:
qits-configuration`, `supplied: qits-workspaces`, `supplied: qits-projects`, and carry no url,
because this service made no call. It is how qits-platform-orchestrator gives one platform-wide pin set — read once, at the start
of a run — to every deleter that run touches, so two deleters can never work off two different
truths. Nothing about the fail-closed rule softens: a member the caller left out is that source
**unanswered**, the plan reports itself non-executable and the sweep aborts with nothing deleted,
exactly as an unreachable service does. A deployments document of `{"pins":[]}` is the opposite and
is a real answer — a platform with nothing deployed, pinning nothing, and a run may proceed on it. A body that is not that shape is a `400` rather
than a quiet fall back to the readers. **`POST /gc/plan` accepts `qits:admin` or `qits:system`** —
the `GET` takes `qits:admin` or `qits:agent` and both sweeps stay `qits:system` — because the caller that needs
it is a machine: the orchestrator authenticates as `qits:system,qits-platform:system` and never
holds `qits:admin`, and it is the same machine already allowed to run the sweep. Being a `POST` also
puts it inside `AdminWriteGuard`, so once `qits.auth.machine.required` is on it needs the machine
audience the sweep needs. Sending no body at all is unchanged in every respect, which
is what the SPA and every operator recipe do, and the HTTP readers stay the fallback for them.
**The envelope grew from two members to four on 2026-09-04 and to six on 2026-09-05, and that is why
this service ships last in each of those rollouts**: an orchestrator still sending an older envelope
supplies a partial keep-set, which is a source unanswered and a run that refuses. The order is the
same shape both times — bring the answering services up (qits-platform-maintenance and
qits-configuration, then qits-workspaces and qits-projects), then the orchestrator, then this.
**Those readers send no credential**, so on an authenticated platform they get a `401` and every
no-body run aborts; supplying pins is the way around that today, and fixing the readers is a
separate piece of work that is deliberately not done here.

### One census, never two

`LiveBlobCensus` is the reference set, and both the store summary and every GC plan read it. It was
extracted from the explorer rather than written again, because a second implementation of "what is
live" is a set the UI reports and a set the sweep protects, drifting silently until the day a sweep
deletes something the page called referenced. Its byte-exactness is the summary's own identity,
proved by the explorer's tests: `diskTotal = ociUnion + npmPublished + npmProxyTarballs +
mavenPublished + mavenProxy + orphans`.

It splits liveness **by the type that names a blob**, which is what lets one type let go of content
another still serves — the same bytes can be an image layer and a published tarball, and the file is
one file.

### Row-less blobs are untouchable

A blob on disk that no identity row of any type names is reported (`untouchable` in the plan) and can
never be swept. This is structural rather than an allowlist: **a blob becomes a candidate only by
losing its last identity row to a strategy's own deletion**, so one that never had a row is out of
reach of the mechanism entirely.

It is the most important rule here. The store's row-less pool is 124 MiB in three ELF binaries pushed
through the OCI blob-upload session with no manifest — and one of them is the CI daemon binary every
build downloads by digest. A sweep that deleted "everything no row references" would stop CI
platform-wide. The plan lists the pool's digests so that fact is checkable rather than promised.

### The delete primitive

`BlobStore.delete` is the only way bytes leave this store. It is **package-private** and its only
permitted caller is the `gc` module's `BlobSweep`, which reaches it through `BlobReclaim` — the one
narrow public door, javadoc'd as gc's alone. That unlink loop runs only when `GcSweepExecutor`
drives it behind the `POST`, after the strategies' identity deletions, against a census taken fresh
after them. Three constraints are enforced in the method rather than trusted to its caller:

- **A grace window** — `qits.artifacts.gc.blob-grace-period`, default **6 hours**, measured from the
  file's mtime, which is when `promote` moved it into place. It closes the upload race: a client's
  blob-exists probe (or npm's dedupe) answers "have it" for a blob about to be unlinked, and the
  manifest referencing it lands after. A withheld blob is not lost — it is reported as withheld, and
  the next run takes it.
  **With the access windows at `P0D` this is the only clock left in the collector, and it is race
  safety rather than retention.** The keep-classes name a coordinate the moment somebody else's row,
  manifest or configuration names it; what they cannot do is name it in the gap between a push and
  that row appearing — an image landing minutes before its deployment row, a library landing before
  its consumer's fold resolves it. This window is exactly that gap. It gates identity **rows** as
  well as blob unlinks, so a fresh push is immune whole for six hours, coordinate and bytes
  together. It was `P7D` until 2026-09-04 and `P2D` until 2026-09-05.
- **A pre-unlink re-census** — a plan is a photograph; the store moves. The caller passes a guard
  that is asked again inside the store's write lock, the same lock `promote` takes, so the check and
  the unlink cannot be separated by a write. Both writers live in one JVM, which is what makes an
  in-process lock sufficient rather than hopeful. The guard must be a set lookup, not a computation:
  it runs with uploads blocked.
- **Delete then invalidate** — each unlink signals `BlobDiskIndex` exactly as `promote` does, so the
  store summary stays honest through a sweep.

Every outcome is a returned `DeleteResult`, not an exception: `ALREADY_GONE` and `STILL_REFERENCED`
are ordinary answers for a sweep running against a store that moved under it.

### The seam a strategy implements

`GcStrategy` is the whole contract. A strategy is a CDI bean; nothing else is registered, and a type
with two claimants is reported as a policy collision rather than merged.

```java
public interface GcStrategy {
  RepositoryType type();
  default boolean readsPins();                   // true ⇒ never planned on a run with broken pins
  Plan plan(LiveBlobCensus.Census census, GcPins pins);  // pure; throws ⇒ that type is fail-closed
  default String note();                         // a standing caption for the reports, or null
  default Applied apply(Plan plan, GraceWindow grace);  // the execute half; the default refuses
                                                        // any plan that condemns

  record Plan(List<GcIdentity> dead,      // what dies, each naming the rule that condemned it
              List<GcIdentity> kept,      // what survives, each naming the rule that saved it
              Set<String> blobsReleased,  // every blob the dead identities reference
              Set<String> blobsRetained)  // this type's live set AFTER the plan
  {}

  record Applied(List<GcIdentity> deleted,               // rows gone now
                 List<GcIdentity> withheldByGraceWindow, // left whole; re-planned next run
                 List<String> errors)                    // per identity, never thrown
  {}
}
```

The two blob sets may overlap, and asking for both is what keeps reconciliation out of the
strategies: a layer under a dying tag and a surviving one is released *and* retained, and subtracting
is the substrate's job. A strategy that cannot establish its keep-set safely **throws** — the planner
reports the type as failed and treats every blob the census attributes to it as live, so an
unreachable dependency reclaims nothing instead of guessing.

`plan()` stays pure — it is what the dry-run report reads. `apply()` is called only by
`GcSweepExecutor`, only on a plan the same request computed, and it owns its type's deletion
mechanics end to end: OCI rows go through `OciRegistryService.collectTag`/`collectManifest` (the
latter refuses a manifest a tag still names), npm rows through `NpmRegistryService.collect` (the
tombstone and the dist-tag refusal live in the mechanism, not the policy). A strategy that never
condemns — the mirror, the CI stubs — keeps the default, which is correct for the empty set and
loud for anything else.

### `oci-images`, on the own engine

`OciImageGcStrategy` is a four-line bean now: the rule is `OwnArtifactsStrategy`'s, the wiring is
`OwnGcStrategy`'s, and docker's facts are `OciImagesGcAdapter`'s. The settled rule, in one sentence:
**the last two calver releases of every image stay, everything a live pin names stays, `latest`
always stays, and everything else dies on the run that finds it.**

| Kept because | Spelled |
|---|---|
| it is one of the last two releases | a tag shaped like a calver version (`2026.801.85448`), ranked by the version's own order — not by a row timestamp, so a release pulled last week is not thereby the newer release. There is no `-main.g<sha>` suffix in docker: the sha tag *is* the prerelease coordinate and a release adds a version tag beside it |
| qits-platform-deployments pins it | any coordinate `GET /platform-deployments/api/pins` names for that image — what is serving, and what a rollback would restore. **One rule, the deployer's**: this used to be two rules derived here from raw deployment rows, and the derivation was wrong (it read a `FAILED` attempt as the rollback target and dropped the sha that actually served) |
| a repository's Dockerfile references it | any `image:tag` `GET /maintenance/api/pins` names in the `docker` ecosystem, joined on the **full** image name. A base image is pulled by the *builder*, so nothing here has an access row to show for it and no deployment names it |
| the platform is configured to launch it | any `image:version` `GET /configuration/api/pins` names, joined the same way. A workspace or agent image is started on demand from a configuration entry, so the image a user's next click pulls can easily be one nobody has started for a week |
| it is the moving pointer | a tag literally named `latest`. CI step recipes pull `qits/build-images/*:latest` by name; `latest` is not a calver, so no release rule covers it, and the host-side keep-prefix suppresses exactly the pulls that would keep it access-warm. Deleting it `404`s a fresh host's first pull of a step image, and it costs nothing — `latest` shares the newest push's blobs |
| the next deploy will pull it | the newest **calver** tag per image, ordered `BY_CALVER` — the version's own order. This is the whole safety net for an image cd has never deployed, and a deploy pulls a version coordinate, so the newest release is the coordinate it will ask for. It named the newest build sha until 2026-09-04, which stopped being the pull target when deployments moved to version coordinates |
| — | there is no sixth row any more. Access was the *unclassified-means-keep* backstop's last home, and at `P0D` a coordinate nobody names is condemned on the run that finds it. What buys a just-pushed tag time is the six-hour grace on its bytes, which withholds the identity whole |

**Two rules changed direction, and both changes are the settlement's.** Calver releases used to be
kept forever and are now kept as the last two per image. Build-sha tags used to die structurally the
moment a newer build existed; since 2026-09-05 they die because nothing names them, which is the
same outcome reached by a rule that can be argued with — a sha the deployer pins survives, and one
that is merely popular does not.

A manifest is an identity of its own **only when no tag names it and no tagged manifest reaches
it** — a tagged manifest's identity is its tag, and an index child rides on the index's closure.
That is the mirror's shape verbatim, and it has the mirror's consequence: a manifest whose tag this
run condemns becomes an untagged manifest today and is collected on the *next* run. Its bytes are
safe in the meantime — the sweep's pre-unlink re-census sees the surviving row and counts the blob
as still referenced — so the dry-run's per-type figure is the one that runs a run ahead of reality.
Collecting the store's measured 73 untagged manifests is the second half of this rule, and they are
now access-gated like everything else.

**Its pins come from the run**, fetched once at the start from qits-cd, never cached and never
derived here — see "Live pins, and the whole-run abort" above.

### `npm-packages`, on the own engine — and the tombstone only it needs

`NpmPackagesGcStrategy` is a four-line bean now: the rule is `OwnArtifactsStrategy`'s, the wiring is
`OwnGcStrategy`'s, and npm's facts are `NpmPackagesGcAdapter`'s. The rule since the evening of
2026-09-05: **every published release stays, anything a dist-tag names stays, anything a
repository's package.json still resolves to stays, and prereleases die on the run that finds them.**

| Kept because | Spelled |
|---|---|
| **it is a published release** | the version has **no prerelease part** — `0.0.1` through `2026.801.85149`. Every one of them, at any age and any depth in the version order. See below — this replaced the belt of two the same evening `maven-packages` lost its |
| a pointer names it | any version a dist-tag currently names. A packument whose `dist-tags` names a version its `versions` does not list is a broken package to every npm client, so this is checked before the window rather than left to it |
| a repository still resolves it | any `name@version` `GET /maintenance/api/pins` names in the `npm` ecosystem — the identity spelling verbatim, so the lookup is an equality test. It is what a lockfile on `main` will install the next time that consumer builds, which may be well outside the window |
| — | there is no fourth row any more. An install used to keep a version alive for `P3D`; at `P0D` it does not, because an install moves a timestamp and a timestamp cannot say whether anything will install again. The `@main` pointer and the dependency pin say it outright, and both are above |

**No published release is age-collected. Withdrawn 2026-09-05, hours after the maven one and for
the same reasons plus a sharper one.** The settlement priced releases as a belt of two with an
access window under it; when the windows went to `P0D` that afternoon, the belt became the whole of
what stood between a published tarball and a delete. The sweep that evening took
`@qits/ui-components` from eight versions to **three** and `@qits/angular` to **two**. Fifteen
frontend `package-lock.json` files pin `@qits/ui-components@2026.904.202810`, which it took; two
services' release runs died on `npm ci` with `E404`, and two more frontends could not cut a release
at all. Why the rule went rather than the number:

- **An install is not a fetch of this registry.** A tarball is downloaded once and served from
  `node_modules`, a warm npm cache and every baked build image after that. Age here measures cache
  warmth, not need — and at `P0D` it measures nothing at all.
- **No pin source can see an npm pin.** `MaintenanceDependencyPins` reads manifests on `main`, and a
  frontend's lockfile is not on the service's `main`: it is reached through a **submodule gitlink**
  that a release tag freezes. Fifteen services' gitlinks name frontend commits pinning five
  different versions of this package, and nothing reports one of them.
- **The disk rounds to nothing.** The whole hosted npm registry is on the order of ten megabytes
  against a 29 GB store that is 28.8 GB of images.
- **It used to be irreversible.** A collected version left a tombstone that refused even an
  identical republish. That refusal has since narrowed to what it actually protects — different
  bytes — but not needing the restore is the cheaper fix.

**What still ages out** is npm's build output: **prereleases**, the per-push `-main.g<sha>` builds,
which are the analogue of the timestamped snapshots `maven-packages` still collects. Nothing's
lockfile pins one for long and `@main` resolves to the newest by dist-tag. A version that does not
parse as semver is never a release either (it cannot be ordered), and a pointer is what saves one: a
dist-tag, or the dependency pin.

`NpmPackagesGcAdapter.pinnedBy` answers the release keep, so no release reaches the belt or the
window and `OwnArtifactsStrategy` is untouched; `NpmPackagesGcStrategy.note()` carries the
correction onto every report line, because the configuration echo beside it is the own engine's
sentence and still describes the belt.

**No whole-or-nothing repair was needed here.** The half-collected version `maven-packages` had to
be fixed that morning cannot occur: an npm identity is one row naming one tarball, and `collect`
removes the row and writes its tombstone in a single transaction. There is no per-file loop to leave
half-applied.

**The adapter still filters `npm_version` by the repository row's type**, although no `npm-proxy`
row can exist here any more. It is one line and it stays: the table is shared with the cache half in
the `qits-registries-npm` jar, a deployment carrying leftover rows would otherwise have them
collected under the hosted rules, and a filter removed because the other type is elsewhere is a
filter nobody restores when it comes back.

**The tombstone is npm's alone, and docker needs nothing like it.** Version immutability is enforced
by looking for the row (publish over an existing version is `403`), so deleting a row would quietly
re-open that version's name for a publish carrying different bytes — one coordinate resolving to two
tarballs over its lifetime, the mutability this registry exists to refuse. Immutability's meaning
narrows honestly from *"a version row is never touched"* to *"a version, once published, is never
republished"*, and `npm_version_tombstone` (V6) carries the second half after the first stops being
true. `publish` consults it and answers a `403` that says **garbage-collected**, not *immutable*: a
pusher told "immutable" goes looking for a version that is not there. An OCI tag is a movable pointer
by design and re-pushing one has always been legal, so there is no promise for a deletion to weaken
on that side.

`NpmRegistryService.collect` is the only way a version row ever leaves, it writes the tombstone in the
same transaction, and it refuses a version a dist-tag still names. It is package-private, reached
only through the `NpmRegistryCollection` facade, and its one caller is
`NpmPackagesGcAdapter.delete` — it shipped ahead of that caller precisely so the tombstone
was never a step someone had to remember, and both guarantees live in the mechanism where no path
around them exists.

### The three caches — gone with the engine that collected them

`oci-mirror`, `npm-proxy` and `maven-proxy` ran one engine (`CacheEvictionStrategy`) over one rule:
everything unaccessed for longer than the configured window is evicted, a live pin outranks the
window, and eviction writes no tombstone because re-fetching is what a cache is for. All of it — the
engine, the three adapters, the proxy eviction doors on the npm and maven registry services, and the
windows that mapped the types onto it — went to **qits-mirror-platform-service** with the types it
collected. Its README carries the rules this section used to.

What is left here is the seam, and it is three sentences:

- **The `gc` jar's type mapping has no line for any of the three.** `GcTypeConfig.of` refuses a
  registered type with no configuration rather than defaulting it, and the three cache lines left
  with the engine — which is consistent, because their profiles are not registered either.
- **A repository row whose type no profile claims is not planned.** V7's `oci-mirror` rows were
  exactly that until V14 deleted them, and the rule still guards the one case V14 leaves alone —
  a cache row with content under it: `GcPlanner` omits it, so a plan says nothing about it rather
  than reporting zeros that read as a rule that ran and found nothing. The same rule keeps it out of
  the explorer listing.
- **Their cached bytes are not orphans, and no sweep can reach them.** A blob becomes a candidate
  only by *losing* its last identity row to a strategy's own deletion, and nothing here deletes a
  cached `oci_manifest` or `oci_tag` row — so those blobs stay held by rows the census still walks.
  The store summary's four cache figures are **structurally zero** (this service registers no cache
  type), so leftover cached bytes are counted in no term of the reconciliation above either.
  Reclaiming them is a cutover action, by hand, once.

The H2-compaction procedure that used to sit here went with the caches as well: it existed because
evicting a cached packument frees no disk, those documents being CLOBs rather than files, and no
type this service collects has that shape.

### `maven-packages`, on the own engine — the one type whose identity is not a row

`MavenPackagesGcStrategy` + `MavenPackagesGcAdapter`. This type used to plan "nothing dies" under a
note naming a cleanup rule nobody had implemented; the settlement priced every own type at once, so
the append-only posture is replaced deliberately rather than eroded, and the note goes with it.

**A coordinate is the identity, not a path.** A maven version is a *set* of files — a jar, its pom,
sometimes sources — and half a version is not a smaller version, it is a broken resolve. The settled
rule counts **versions** ("the last 2 release versions per artifact"), which an engine counting paths
could not express at all, so one identity here is one resolvable coordinate and every file under it
lives or dies together:

- `eu.wohlben.qits:qits-eventstream:1.0.0` — a release version directory. A **release**.
- `eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3` — one timestamped snapshot deploy,
  exactly the coordinate the derived version-level metadata resolves `1.0.1-SNAPSHOT` to.
- `eu.wohlben.qits:qits-eventstream:1.0.1-SNAPSHOT` — the literal-filename snapshot, the one mutable
  path class (`uniqueVersion=false`).

| Kept because | Spelled |
|---|---|
| **it is a published release** | every release version, at any age and at any depth in the version order. See below — this replaced the belt of two on 2026-09-05 |
| a repository's pom still references it | `groupId:artifactId:version` from `MaintenanceDependencyPins`, joined verbatim. Belt and braces for a release; the only non-structural keep a **snapshot** has |
| a resolver would break without it | **the newest deployable set of every snapshot version line**: the newest timestamped set if the line has any, else the literal `-SNAPSHOT` set. `maven-metadata.xml` is computed from the surviving rows at read time, so deleting that one would point the document at a file the store no longer has — the single failure this type must not produce |
| this layout cannot read its path | a row that is not `<group>/<artifact>/<version>/<file>` is its own identity under its own path spelling, so the adapter cannot say which coordinate it is half of. It is not collected |
| — | there is no fifth keep. A resolve used to keep a coordinate alive — the **newest** `max(created_at, accessed_at)` across its files, one warm file keeping the set — and at `P0D` it keeps nothing at all. For a release that changes nothing, because the release rule answers first; for a **superseded snapshot set** it means the set goes on the run that finds it |

**No published release is age-collected. Withdrawn 2026-09-05, after it broke the platform.** This
type was priced like the other three own types: a belt of two releases per artifact, everything older
surviving on access inside the window. At `01:58Z` on 2026-09-05, with the window at `P3D`, that rule
deleted **67 published `eu.wohlben.qits` coordinates** in one run — every one of them under
"superseded and unaccessed for longer than P3D" — and every gating build on the platform stopped
resolving. The argument for withdrawing rather than retuning, in short:

- **For a library, access was never consumption.** A jar is fetched once and answered out of a
  hundred local `~/.m2` caches thereafter. Age on a maven row measures cache warmth, not need.
- **A pin cannot be the floor.** `MaintenanceDependencyPins` names what main's manifests reference —
  13 coordinates on the morning of the incident, against hundreds held that branches, parent poms and
  unbumped consumers still resolve. And it is one service's reachability away from empty.
- **The disk is not here.** Measured that morning: 29.4 GB of store, 28.8 GB of it images. The whole
  hosted maven repository rounds to nothing beside that; the 67 coordinates freed a few megabytes.
- **The registry is the artifact of record.** Nothing else holds these bytes and a release path is
  immutable, so a collection here is not reclaimable space, it is the loss of a build input.

`MavenPackagesGcAdapter.pinnedBy` answers the release keep, so no release ever reaches the belt or
the window; `OwnArtifactsStrategy` is untouched and the other three own types keep their belt.
`MavenPackagesGcStrategy.note()` carries the correction onto every report line, because the
configuration echo beside it is the own engine's sentence and still describes the belt.

**Where this is deliberately conservative.** `maven-repository-plan.md` §3.6 sketched "keep the
newest N timestamped builds per snapshot version" and never settled N or priced the deletion, so no N
is invented: the window decides for snapshots — and at `P0D` that is "on the next run" — with the
only structural keep among them the one a resolver would break without. The grace window gates the whole coordinate too — one young file
withholds every row of it, because deleting the mature rows and keeping the young one would produce
exactly the half-version the identity model exists to prevent.

Deletion goes through `MavenRegistryCollection` → `MavenRegistryService.collect`, one file at a time
with the collector removing a whole coordinate's set — **inside one transaction per coordinate**.
`collect` is `@Transactional` per *file*, so the loop used to commit path by path and a throw on the
second file left the first deleted: the paths sort `.jar` before `.pom`, so what that leaves behind
is a version whose pom answers 200 and whose jar answers 404. The coordinate is now removed whole or
not at all, and a failure is reported against an identity that is still intact. No tombstone: a
collected release path is a coordinate the repository no longer serves at all, and a re-deploy there
is a fresh deploy rather
than a mutation of a live one. `maven-proxy` was the cache beside it, sharing `maven_artifact`; the
type-filter on this adapter outlives it for `npm-packages`' reason above.

### `daemon-binaries`, on the own engine — the type the platform *executes*

`DaemonBinariesGcStrategy` + `DaemonBinariesGcAdapter`, and it is the last type to be claimed: it
reported "no strategy registered" until its pin source existed, because the keep-set here is partly
qits-ci's answer and the blob class is the one a running service runs.

**Every row is a release.** There is no prerelease coordinate: a `daemon_binary` row is written in
the same transaction as a publish, publishes come from the release pipeline, and versions are
immutable (`409` on republish). So the belt is the settlement's sentence with nothing to qualify —
the last two versions of every daemon live whatever their age, both rungs of qits-ci's ladder live
under qits-ci's own rule, and everything else dies on the run that finds it.

| Kept because | Spelled |
|---|---|
| it is one of the last two versions | per `(repository, name)`, ranked by version: an **adopted digest-hex version ranks below every calver one**, because the ops adoption carries the blob's own digest as the version and comparing 64 hex characters as a number would rank the oldest thing here as the newest |
| qits-ci's ladder names it | `GET /ci/api/daemon`, both rungs — the version a run would launch and the fallback beneath it. A runner that has not started in months still fetches its rung the moment one does |
| a pin names its bytes | the digest half of the same aggregate: `QITS_CI_DAEMON_VERSION` has been a sha256 since the daemon shipped, so a pinned digest keeps whichever row names those bytes |
| — | a download used to keep a rung alive for `P3D`. At `P0D` it does not: a fetch is a timestamp, and qits-ci answers with both rungs on every run, so what a runner will launch is named rather than inferred. (The digest-addressed `/v2` blob route never moved an access timestamp anyway and must not grow a twin — it carries no daemon identity.) |

Deletion goes through `DaemonRegistryCollection` → `DaemonRegistryService.collect`, the fourth narrow
door beside `BlobReclaim`, `OciRegistryCollection` and `NpmRegistryCollection`. **There is no
tombstone here, deliberately**: npm has one because a deleted version re-opens its name for a publish
with different bytes under somebody's lockfile, while a daemon version is resolved by a pin a
bootstrap re-reads — so a re-release at a collected version is legitimate rather than a silent
content swap, and a tombstone would refuse it forever.

The row-less legacy ELF blobs are untouched by all of this and cannot be reached by any sweep: a blob
becomes a candidate only by *losing* its last identity row, and those never had one. The settlement's
answer to them is an ops action, once, by hand.

### What the plan says

```jsonc
{
  "summary": {                          // first, because it is what a human reads first
    "executable": true,
    "headline": "a sweep run now would execute this plan: 37 identities would be deleted and 21 blob files unlinked, reclaiming 402.7 MiB, with 3 blobs (12.0 KiB) withheld by the PT6H grace window.",
    "identitiesCondemned": 37,
    "blobsSweepable": 21,
    "reclaimableBytes": 422313984,
    "reclaimable": "402.7 MiB",         // the same figure, so nobody counts digits
    "withheldByGraceWindow": 3,
    "types": [                          // one line per type: configured engine, window, outcome, note
      "ci-screenshots (excluded): excluded by configuration, so nothing of it is ever deleted — a decision, not a gap — stub: the golden-diff loop has never produced a screenshot.",
      "ci-videos (excluded): excluded by configuration, so nothing of it is ever deleted — a decision, not a gap — stub: the golden-diff loop has never produced a video.",
      "oci-images (own, P0D): 12 identities condemned, 28 kept, 9 blobs freed, 384.0 MiB reclaimable",
      "npm-packages (own, P0D): 1 identities condemned, 4 kept, 1 blobs freed, 17.5 KiB reclaimable",
      "maven-packages (own, P0D): 1 identities condemned, 6 kept, 2 blobs freed, 40.3 KiB reclaimable",
      "daemon-binaries (own, P0D): 1 identities condemned, 2 kept, 1 blobs freed, 41.1 MiB reclaimable",
      "docs (own, P0D): 1 identities condemned, 4 kept, 6 blobs freed, 1.2 MiB reclaimable"
    ]
  },
  "generatedAt": "2026-08-01T12:00:00Z",
  "dryRun": true,                       // always, on this route; the sweep's receipt is the twin with false
  "graceWindow": "PT6H",
  "executable": true,
  "pinFailures": [],
  "pins": [                             // how this run read its pins; the sweep receipt carries the same
    { "source": "qits-platform-deployments", "url": "http://qits-platform-deployments:8080/platform-deployments/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 34,
      "outcome": "9 application pins over 14 image shas — what is serving, and what a rollback would restore",
      "pinCount": 9,
      "keeps": ["qits-platform-artifacts:3ff84c05…", "qits-ci:ab854a19…"] },
    { "source": "qits-ci", "url": "http://qits-ci:8080/ci/api/daemon",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 11,
      "outcome": "daemon qits-ci-daemon, 2 ladder rungs pinned (source: adopted)",
      "pinCount": 2,
      "keeps": ["blob 9f2c…", "qits-ci-daemon@2026.802.40", "qits-ci-daemon@9f2c…"] },
    { "source": "qits-platform-maintenance", "url": "http://qits-platform-maintenance:8080/maintenance/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 62,
      "outcome": "184 manifest references over 41 coordinates (23 maven, 12 npm, 6 docker) — what repositories on main still build against",
      "pinCount": 184,
      "keeps": ["@qits/ui-components@2026.902.204627", "eu.wohlben.qits:qits-blobstore:2026.903.85122", "qits/workspace-base:2026.902.143920"] },
    { "source": "qits-configuration", "url": "http://qits-configuration:8080/configuration/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 8,
      "outcome": "4 configured entries over 3 image coordinates — what a launch would pull, which no deployment row names",
      "pinCount": 4,
      "keeps": ["qits/project-agent:2026.904.160152", "qits/workspace-editor:2026.904.100239", "qits/workspace:2026.904.160522"] },
    { "source": "qits-workspaces", "url": "http://qits-workspaces:8080/workspaces/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 7,
      "outcome": "2 launch images over 2 coordinates — what a workspace or editor start would pull TODAY, which the configured version only names after the next deploy",
      "pinCount": 2,
      // NOT 2026.904.160522: this service has not been redeployed since that entry moved, so the
      // version it is actually pulling is the older one — which is the whole reason it is asked.
      "keeps": ["qits/workspace-editor:2026.904.100239", "qits/workspace:2026.903.120000"] },
    { "source": "qits-projects", "url": "http://qits-projects:8080/projects/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 9,
      "outcome": "2 launch images over 2 coordinates — what an agent or refinement start would pull TODAY, which the configured version only names after the next deploy",
      "pinCount": 2,
      "keeps": ["qits/project-agent:2026.903.090000", "qits/workspace:2026.903.120000"] }
  ],
  "types": [                            // every REGISTERED type, always — including the ones nobody collects
    { "type": "oci-images", "strategy": "OciImageGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "qits", "identity": "qits-ci:3ff84c05…",
                 "rule": "superseded and unaccessed for longer than P0D" }],
      "kept": [{ "repository": "qits", "identity": "qits-stt:2026.801.85448",
                 "rule": "among the last 2 released versions of this identity group — releases are kept by policy, not by access" }],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 },
    { "type": "daemon-binaries", "strategy": "DaemonBinariesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "daemons", "identity": "qits-ci-daemon@2026.601.10",
                 "rule": "superseded and unaccessed for longer than P0D" }],
      "kept": [{ "repository": "daemons", "identity": "qits-ci-daemon@2026.802.40",
                 "rule": "pinned by qits-ci daemon ladder" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 43123792 },
    { "type": "npm-packages", "strategy": "NpmPackagesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.63140-main.gab854a1",
                 "rule": "superseded and unaccessed for longer than P0D" }],
      "kept": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.85149",
                 "rule": "among the last 2 released versions of this identity group — releases are kept by policy, not by access" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 17904 },
    { "type": "maven-packages", "strategy": "MavenPackagesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "maven", "identity": "eu.wohlben.qits:qits-eventstream:1.0.1-20260601.101010-1",
                 "rule": "superseded and unaccessed for longer than P0D" }],
      "kept": [{ "repository": "maven", "identity": "eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3",
                 "rule": "the newest deployable set of this snapshot version — what the derived maven-metadata.xml resolves to, so a resolver would 404 without it" }],
      "blobsReleased": 2, "blobsSweepable": 2, "reclaimableBytes": 41216 },
    { "type": "docs", "strategy": "DocsGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "docs", "identity": "qits-eventstream@2026.601.10",
                 "rule": "superseded and unaccessed for longer than P0D" }],
      "kept": [{ "repository": "docs", "identity": "qits-eventstream@2026.802.40",
                 "rule": "among the last 2 released versions of this identity group — releases are kept by policy, not by access" }],
      "blobsReleased": 6, "blobsSweepable": 6, "reclaimableBytes": 1258291 },
    { "type": "ci-screenshots", "strategy": "CiScreenshotsGcStrategy",
      "note": "excluded by configuration: no engine is configured for this type, so nothing of it is ever deleted — a decision, not a gap. stub: the golden-diff loop has never produced a screenshot. The intended rule is branch-scoped …",
      "error": null, "dead": [], "kept": [],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 }
  ],
  "sweep":       { "blobCount": 0, "reclaimableBytes": 0,
                   "withheldByGraceWindow": 0, "withheldBytes": 0, "blobIds": [] },
  "untouchable": { "reason": "…", "blobCount": 3, "bytes": 130419952, "blobIds": ["…"] }
}
```

Details a reader trips over otherwise:

- **The summary is derived, never a second opinion.** Every figure in it comes from the report below
  it (`GcSummary`), so it can only be wrong about arithmetic. It leads with the refusal when a plan
  is not executable, because a plan that cannot run must never be skimmed as a plan that would free
  nothing — the figures under a refusal are what the types that could still plan would do.
- **The pins section is the provenance of every keep.** The keep-set is partly another service's
  answer, so the report shows the answer as well as its consequences: the url called, whether it
  answered, how long it took, and the keep-identities it produced. Checking that the pinned shas are
  the shas the deployments are running is the first thing a review of this report does. The sweep
  receipt carries the same section, an aborted run included — a receipt whose whole story is one
  unreachable source has to show that source.
- **A type with no strategy says so, and an excluded type says so twice.** "Nothing to collect",
  "nobody is collecting" and "nobody is *meant* to be collecting" are three answers, and the report
  distinguishes them: an unclaimed type carries "no strategy registered" (what a **new**
  `RepositoryType` reads as on the day it lands, proved by `GcPlanTest` over an empty registration),
  while `ci-screenshots` and `ci-videos` carry the excluded line on their own entry as well as in
  the configuration echo.
- **Every type on an engine refuses when the pins are missing** — all six of them. A run with
  qits-cd or qits-ci unreachable reports them as `live pins unavailable` rather than planning them
  against "nothing is pinned". Two of them are pinned by *coordinate* (an image sha, a daemon
  version); the rest only by **digest**, and blobs dedupe globally, so the check is real everywhere.
- **`sweep` is not the sum of the per-type figures.** A blob dies once, and two types releasing the
  same content free it once. The per-type numbers answer "what does this rule buy on its own"; the
  sweep answers "what would a run free tonight".
- **The per-type figures ignore the grace window; the sweep applies it.** The window is about when an
  unlink may happen, not about what a rule structurally frees, and a strategy's worth should not read
  as zero because its content was pushed this morning.

`GET /gc/plan` is `@RolesAllowed({"qits:admin", "qits:agent"})` like every other read on this API; its `POST` twin
allows `qits:admin` **or** `qits:system`, because the orchestrator that sends a pin set is a machine
and is already allowed to run the sweep this plan feeds. `AdminWriteGuard` covers write methods
only, so no read passes through it — the sweep `POST` is the write the `gc` prefix was pre-listed
for and inherits it by construction, with the rollout-gate honesty stated at the top of this
section still applying.

## The boundary

Everything this context needs from the rest of qits goes through a port it declares. There used to
be an optional one — `RepositoryNameResolver`, whose absence meant a 404 on one git route — and it
left with the git host. Two ports remain, and both are exceptions rather than the pattern.

`CdDeploymentPins` and `CiDaemonPins` are declared **and** implemented inside this repo, over HTTP,
dialling `qits-cd` and `qits-ci` for GC's live pins (see "Garbage collection"). Absent is **not** a
supported configuration for either, which is the difference that matters: a pin source that cannot
answer aborts the whole GC run instead of falling back.
Both live in `gc/` with the process that needs them — the `artifacts` library dials nothing at all,
which is the domain-blindness the module split gave back.

## Config

Defaults ship from each jar's `META-INF/microprofile-config.properties` (ordinal 100); the consuming
app's `application.properties` overrides them.

| Key | Default | What |
|---|---|---|
| `qits.artifacts.blobs-dir` | `~/.qits/data/artifacts/blobs` | content-addressed blob bytes |
| `qits.artifacts.gc.blob-grace-period` | `PT6H` | how long a blob file must sit untouched before the sweep may unlink it — see "Garbage collection". With the access windows at zero this is the only clock left, and it is race safety for a just-pushed artifact rather than retention: it gates identity rows as well as blob unlinks |
| `qits.artifacts.gc.pins.cd-base-url` | `http://qits-cd:8080/cd/api` | where a run reads the image shas deployments pin (`GET /cd/api/pins`). **Renamed** from `qits.artifacts.gc.oci.cd-base-url` |
| `qits.artifacts.gc.pins.cd-timeout` | `PT10S` | per-request timeout on that fetch. **Renamed** from `qits.artifacts.gc.oci.cd-timeout` |
| `qits.artifacts.gc.pins.ci-base-url` | `http://qits-ci:8080/ci/api` | where a run reads the daemon pin ladder (`GET /ci/api/daemon`) |
| `qits.artifacts.gc.pins.ci-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.pins.maintenance-base-url` | `http://qits-platform-maintenance:8080/maintenance/api` | where a run reads the manifest dependency pins (`GET /maintenance/api/pins`) |
| `qits.artifacts.gc.pins.maintenance-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.pins.configuration-base-url` | `http://qits-configuration:8080/configuration/api` | where a run reads the configured container images (`GET /configuration/api/pins`) |
| `qits.artifacts.gc.pins.configuration-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.pins.workspaces-base-url` | `http://qits-workspaces:8080/workspaces/api` | where a run reads the images a workspace or editor start would pull today (`GET /workspaces/api/pins`) |
| `qits.artifacts.gc.pins.workspaces-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.pins.projects-base-url` | `http://qits-projects:8080/projects/api` | where a run reads the images an agent or refinement start would pull today (`GET /projects/api/pins`) |
| `qits.artifacts.gc.pins.projects-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.type.<wire-name>.strategy` | per type, see "The settlement" | which engine collects a repository type: `own` or `excluded` here — the `cache` engine is qits-platform-mirror's. Every registered type must have one, and a missing entry is refused, not defaulted |
| `qits.artifacts.gc.type.<wire-name>.window` | `P0D` for all six own types | how long an identity may sit unaccessed before it is eligible, ISO-8601. At the shipped zero the keep-classes are the whole retention policy. Absent for an `excluded` type |
| `qits.auth.machine.required` | `false` | the machine-token rollout gate. Off, the JSON admin write surface is open — network trust. On, its writes need a bearer with `aud=qits-platform-artifacts` |
| `qits.auth.machine.audience` | `qits-platform-artifacts` | this service's own id, and the `aud` its tokens must carry |
| `qits.artifacts.startup-seed.enabled` | `true` | self-seed the hosted roots: `ci-screenshots`, `ci-videos`, `qits`, `npm`, `maven`, `daemons`, `docs`, `sboms`. No cache root — those are qits-platform-mirror's |
| `quarkus.oidc.auth-server-url` | `http://qits-platform-idp:8080/idp` | the idp, reached direct on qits-net, for validating an inbound machine token |
| `qits.artifacts.oci.max-layer-size` | `1G` | the registry's per-layer cap, enforced while streaming |
| `qits.artifacts.oci.max-manifest-size` | `4M` | manifests are buffered whole to be digested and parsed |
| `qits.artifacts.oci.upload-session-ttl` | `PT30M` | in-memory upload sessions; lost on restart, by design |
| `qits.artifacts.oci.upload-idle-timeout` | `PT1M` | wait for the *next* chunk, not for the whole upload |
| `qits.artifacts.npm.max-publish-size` | `32M` | the largest npm tarball, in either direction — see below |
| `qits.artifacts.maven.max-artifact-size` | `128M` | the largest maven artifact, in either direction — jars are megabytes at most; the deploy PUT streams, so the knob is the only bound |
| `qits.artifacts.daemon.max-binary-size` | `256M` | the largest daemon binary, in either direction. The measured ci-daemon is 43 MB, so this is headroom rather than a snug fit; the publish PUT streams, so the knob is the only bound a chunked upload has |
| `quarkus.http.limits.max-body-size` | `1088M` | **global**; above the largest upload cap |
| `quarkus.http.enable-compression` | `true` | gzip on the way out — **build-time fixed**, see below |

The last one is a global ceiling, but not the single hard gate this section used to claim. Quarkus
enforces it in two places: a route at order −2 that 413s a declared `Content-Length` over the limit
(nothing bypasses that), and — for a request with **no** `Content-Length` — nowhere at all, beyond
stashing the number for whatever reads the body. A chunked upload to a raw Vert.x route is therefore
bounded only by what that route enforces itself, which is why the registry reads through the same
limit-aware stream RESTEasy uses and caps the layer while streaming.

It is `1088M` = 1 GiB + 64M of slack, deliberately above `qits.artifacts.oci.max-layer-size`: were
they equal the wire 413 would always win, and a client would get an empty-bodied 413 with the
connection reset instead of the spec's error envelope. It must also never drop below 64M or the
`ci-videos` cap breaks silently. Raising it raises the ceiling for every route in this process; the
tradeoff, the mechanism, and what an operator can actually do about it are in
`docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`.

`qits.artifacts.npm.max-publish-size` is the same kind of number for a second route, and stating it
is not optional: `BodyHandler.create()` defaults to 10 MiB, and an npm publish document carries the
tarball **base64-inflated by 4/3** inside JSON, so 32M here is roughly a 24M tarball ceiling. It
answers one question — how large an npm tarball this deployment is willing to hold — and the shared
jar applies it to a cache's inbound stream as well, which is qits-platform-mirror's copy of the knob
rather than a second meaning for this one.

**One family of key is absent from that table and still resolves**, because its defaults ship in a
jar this service consumes: the cache keys (`qits.artifacts.oci.mirror.*`,
`qits.artifacts.npm.proxy.*`, `qits.artifacts.maven.proxy.*`). They are not this deployment's to
set — the caches are qits-platform-mirror's — and setting one here changes nothing this service can
reach, with the single exception named under "The pull-through mirror": the OCI mirror's TTL and
timeouts are still read by wire code the excluded profile does not unregister.

The git host's keys (`qits.repositories.git.*`, `qits.ci.intake-url`, `qits.projects.intake-url`,
`qits.projects.name-resolver-url`) do not even resolve here any more — they left with
qits-githost-service,
along with the `quarkus.oidc-client` extension whose only user was the post-receive bearer.

`quarkus.http.enable-compression` is in the shipped `application.properties` and **cannot be moved to
a deployment's environment**: it is `BUILD_AND_RUN_TIME_FIXED`, so an env var on the container is
read, accepted and ignored, with no warning anywhere. The value that ships is the value that runs.
The SPA bundle is 206 kB uncompressed and about 60 kB gzipped, and the explorer's JSON compresses
harder still.

`quarkus.http.compress-media-types` is deliberately **left unset**. Setting it *replaces* Quarkus'
default list rather than extending it, so naming one type would silently stop compressing the rest —
and the default is already right: `text/*`, `application/json`, `application/javascript` and the XML
family, none of which is an image layer, a tarball or a git packfile. Those are already-compressed
bytes served with `sendFile`, and re-compressing them would cost CPU to grow them.

`quarkus.rest.path=/artifacts/api` and `quarkus.http.non-application-root-path=/artifacts/q` are
**not** shipped from the jar's defaults: they are the deployable's own decision and live in
`service/src/main/resources/application.properties`. The suite inherits that file rather than
carrying a copy, so the absolute paths the tests assert are the ones the process serves.

## What is deliberately *not* here

- **Git, in every sense.** Cloning, branches, commits, submodules, the smart-HTTP host and the
  post-receive fan-out are **qits-githost-service**'s; the projects and repositories context, the
  GitHub backup and the alias table are
  [qits-projects-service](https://github.com/QuicklyIterateTheSoftware/qits-projects-service)'. This
  repo's git history holds the host's files, which is the only trace left of it here.
- **CI.** Pipelines, runners and the intake live in
  [qits-ci-service](https://github.com/QuicklyIterateTheSoftware/qits-ci-service). It reaches this service as an
  ordinary client — pushing images, publishing packages, downloading a daemon binary.
- **Building images.** The registry stores and serves them; nothing here produces one. qits-ci step
  containers get no docker socket by design, so `docker build` inside a step fails today and keeps
  failing — consuming this registry works immediately, producing from within a step needs an
  unprivileged builder story of its own. Until then a producer is a host with a docker daemon; no
  credential, since the registry is tokenless and pushes stay inside the deployment.
- **Client-facing deletion.** Garbage collection executes now (see "Garbage collection"), but only
  as an operator's `POST /artifacts/api/gc/sweep` — the registries' `DELETE` endpoints stay
  unimplemented and row-less blobs stay untouchable.
- **Building packages.** The npm registry stores and serves them; nothing here runs `npm pack`. A
  producer is a CI step that runs `npm publish` over plain HTTP to `qits-platform-artifacts:8080` — no docker
  socket, no credential, since the registry is tokenless and publishes stay inside the deployment.
- **A deployable.** See "Layout" above.
