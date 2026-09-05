# qits-artifacts-service — working notes

Read `README.md` first: it defines what this repo owns (the hosted byte plane — the OCI registry at
`/v2`, the npm registry at `/artifacts/npm`, the maven repository at `/artifacts/maven`, the daemon
binaries, the docs bundles and the CI media, plus the GC over all of them), the one port, and the
config surface. This file is the working conventions on top of it.

**Git hosting is not here.** The smart-HTTP host, its DFS storage over blobs and its post-receive
fan-out are **qits-githost-service**'s. No `eu.wohlben.qits.githost` package, no `git-storage` module and no
JGit dependency remain in this tree, so anything below that names a git route, a pack table or a
push hook is a fact about that repository and not about this one. The lineage no longer *creates*
its three tables either — the PostgreSQL baseline dropped them; see "Schema changes".

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, no pom declares a `eu.wohlben:*`
dependency it cannot resolve from the published registry, and every wire suite synthesises its own
fixture content in memory instead of reaching for a checked-in one.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and
qits-gateway carry, and it extends the clone-alone rule rather than qualifying it: `.sdkmanrc` names
`25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw verify -Dnative` produces
`service/target/qits-artifacts` in about a minute with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. This repo has
  already paid that bill four times over — see "Native" below — so treat `PackagedProcessIT`, not
  `mvn verify`, as the gate for anything that touches an outbound `HttpClient`, Jackson-serialised
  DTOs or the datasource url.

## Native

`-Dnative` lives in `service/pom.xml`, not the root pom: only `service` is an application. It flips
`skipITs` so the build runs `PackagedProcessIT` against the binary rather than skipping past it.
`quarkus.package.output-name` and failsafe's `native.image.path` spell `qits-artifacts` twice and
must move together, or the native IT launches nothing and passes.

Everything that had to be declared, and the symptom each one produces if it is dropped. **Several
`Where` entries name classes that now live in the `qits-registries-*` and `qits-blobstore` jars
rather than in this tree** — `npm/NpmUpstream`, `maven/MavenUpstream`, `registry/MirrorUpstream` and
its config. They are still compiled into this binary, so the constraints still bind; the file to fix
one in is the library's, not this repository's.

| Where | What | Symptom without it |
|---|---|---|
| `dto/UploadResult` | `@RegisterForReflection` | every upload 500s: the type is behind a `Response` return, so nothing registers it |
| `npm/NpmUpstream` | the `HttpClient` is an instance field, not static | build fails: an `HttpClientFacade` frozen into the image heap |
| `maven/MavenUpstream` | the `HttpClient` is an instance field, not static | same as above; the sixth outbound client, and the rule has still not changed. It reads and writes only `String`/`byte[]` and needs nothing else declared — the maven stack, like `registry` and `npm`, still adds zero native-image configuration |
| `gc/CdHttpDeploymentPins`, `gc/CiHttpDaemonPins`, `gc/MaintenanceHttpDependencyPins`, `gc/ConfigurationHttpImagePins`, `gc/WorkspacesHttpLaunchPins`, `gc/ProjectsHttpLaunchPins` | the `HttpClient` is an instance field, not static | same as above; the third, fifth, seventh, eighth, ninth and tenth outbound clients, and the rule has not changed. It moved with the class when GC became its own module — the rule travels with the client, not with the package. The two pin readers added on 2026-09-04 and the two added on 2026-09-05 carry it for the same reason and add no other native-image configuration: they read `JsonNode`, never a bound type |
| `registry/MirrorUpstream` | the `HttpClient` is an instance field, not static — and so is `MirrorBearerTokens`' `ObjectMapper`, which is reachable from one | same as above; the fourth outbound client, and the rule still has not changed |
| artifacts' `microprofile-config.properties` | the `QITS_RESOURCE_DB_*` triple with **no defaults** | nothing — and that is the point: an unset variable dies at Flyway naming the missing one, rather than opening a fallback store. It replaced an H2 file url that resolved `${user.home}` through `getpwuid` and came out as `jdbc:h2:file:?/…` under UID 1001 |
| `registry/MirrorUpstream`'s config | `endpoint-override` injected as `Optional<String>`, not `String` | the binary dies at boot on `Failed to load config value of type java.lang.String` — SmallRye reads a **configured-empty** value as absent, and that key ships blank. `defaultValue = ""` does not help. Invisible to `mvn verify`, where every test sets a real value |

The `HttpClient` rows are build-time failures. The rest are green builds that fail in production,
which is why the IT exists and why it drives real pushes and pulls rather than asserting a status
code.

**`quarkus.native.additional-build-args` is gone, and the deletion is the entry worth knowing.** It
carried nothing but `--initialize-at-run-time` flags for JGit statics that native-image refuses to
freeze into the image heap — `FileUtils`, `WorkQueue`, `WindowCache`, `DfsBlockCache` — and JGit
left with the git host. Nothing here needs the key today. Anything that brings a static cache or a
started thread back onto the classpath needs it again, and the symptom is a *build* failure naming
the class, which is the friendly half of this table.

## Paths

The machine surface is served under the `/artifacts` segment — the edge path-routes it verbatim on
every host, so an unprefixed route is normally unreachable, on `qits-net` as much as through the
edge. The **client** is the exception: this service has a host of its own,
`registry.<env>.<domain>`, and the client is what that host serves at `/`.

| Prefix | Machinery | Moves with |
|---|---|---|
| `/` | the Angular SPA, built and served by Quinoa from the `src/main/webui` submodule | `quarkus.quinoa.ui-root-path` **and** the client's own `baseHref` |
| `/artifacts/api/**` | JAX-RS | `quarkus.rest.path` |
| `/artifacts/q/**` | Quarkus' non-application root (openapi, swagger-ui, health) | `quarkus.http.non-application-root-path` |
| `/artifacts/npm/**` | raw Vert.x routes in `NpmRoutes` (the npm registry; only the hosted type is registered here) | **nothing** — a literal, and `NpmPaths.BASE` is the only place it is spelled |
| `/artifacts/maven/**` | raw Vert.x routes in `MavenRoutes` (the maven repository; only the hosted type is registered here) | **nothing** — a literal, and `MavenPaths.BASE` is the only place it is spelled |
| `/artifacts/daemons/**` | raw Vert.x routes in `DaemonRoutes` (the platform's own daemon binaries) | **nothing** — a literal, and `DaemonPaths.BASE` is the only place it is spelled |
| `/artifacts/docs/**` | raw Vert.x routes in `DocsRoutes` (published documentation bundles) | **nothing** — a literal, and `DocsPaths.BASE` is the only place it is spelled |
| `/artifacts/sboms/**` | raw Vert.x routes in `SbomRoutes` (the published SBOM store) | **nothing** — a literal, and `SbomPaths.BASE` is the only place it is spelled |
| `/v2/**` | raw Vert.x routes in `RegistryRoutes` (the OCI Distribution API) | **nothing** — a literal, and not under `/artifacts` at all |

`/artifacts/npm` is *not* forced on us the way `/v2` is: npm accepts a registry URL of any depth, so
it sits inside the segment the edge already routes here and needs no `routes:` entry of its own. The first path segment after it is the `artifact_repository` row, the same
first-segment rule the OCI registry uses. `/artifacts/maven` is the same case verbatim: maven
accepts a repository URL of any depth, and `MavenPaths.BASE` is the only place the segment is
spelled.

The SPA takes the *whole host*, so it is the one that can swallow every other stack. Quinoa's SPA
re-route is a catch-all at the ui root registered near-last, so anything with a real route in front
of it still wins — but a request matching **no** route is rerouted to `index.html` and answers
`200 text/html`. `quarkus.quinoa.ignored-path-prefixes` is what stops that, and since the ui root
moved to `/` its values are **absolute**: `/artifacts,/v2`.

Two entries cover everything, because the match is by prefix. `/artifacts` claims the JAX-RS base,
the framework root and all five wire stacks under the segment; `/daemons` is the least forgiving of
those five — a bootstrap script `curl`s a daemon binary and execs it, so `index.html` at 200 becomes
an executable that is a web page. Setting the key REPLACES Quinoa's derivation
(`/artifacts/api`, `/artifacts/q`) rather than extending it, which is why the first entry is spelled
by hand.

`/v2` used to be in that list although **nothing is mounted at `/artifacts/v2`**, as the one entry
ignoring a path no route serves. It now does real work in the other direction: the registry is at
the host root, which is *inside* the ui root, so without the entry a mistyped registry path answers
the page. A 404 tells a registry client "not a registry here", while the SPA answers 200
`text/html` with no `Docker-Distribution-Api-Version` header.
`PackagedProcessIT.theRegistryIsMountedAtTheHostRootNotUnderTheArtifactsSegment` asserts both
directions.

The client's `baseHref` is `/`, matching `ui-root-path`, and lives in another repo. Quinoa mounts
the files; the `baseHref` is what makes `index.html` ask for them at the right url. A mismatch serves
a page whose every asset 404s, and no test here would see it.

**`webui/WebUiRedirect` is gone.** It served bare `/artifacts` as a 301 to `/artifacts/` because
Quinoa mounted the SPA at `/artifacts/*` and missed the bare segment. `/artifacts` is the machine
segment now and the client is at the root, so the bare spelling means nothing to bounce.

`/v2` is the exception to the segment rule and it is forced on us: docker and podman resolve an image
reference against `<host>/v2/` and accept no path prefix. `.config/qits/deployments.yml` names it as
a second `routes:` prefix rather than a service of its own, and `host: registry` gives the plane the
public name docker already uses — one deployment, one host, both surfaces.

The last five lines are the ones that bite: no config key moves those routes, and no JAX-RS test
covers them. `RegistryTest`, `NpmRegistryTest`, `MavenRegistryTest`, `DaemonRegistryTest`,
`DocsRegistryTest` and `SbomRegistryTest` are the only things that would catch them drifting, which
is why they all spell their paths out absolutely.

**The post-receive fan-out is not an address this service holds any more.** `qits.ci.intake-url`,
`qits.projects.intake-url` and `qits.post-receive.retry-delays` went to qits-githost-service with the hook
that fired them, and there they are durable domain events rather than an HTTP call. Setting any of
them on a deployment of this service does nothing.

## Package and module conventions

One top-level package with a subpackage kept deliberately apart from it, plus the wire stacks:

- `eu.wohlben.qits.artifacts.*` — what this repository owns: the docs and daemon registries, the
  store explorer, the live blob census and the seeder. `artifacts/` holds `entity`, `persistence`,
  `dto`, `mapper`, `control`, `error`; `service/` holds only `api`. Entities are Panache
  active-record with public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
  - The blob store itself is **not** in this package any more. It lives in the `qits-blobstore` jar
    under `eu.wohlben.qits.blobstore.*` (same six subpackages) since the store folded into the
    qits-registries reactor. Its config keys stay `qits.artifacts.*`. The three format jars keep
    `eu.wohlben.qits.artifacts.*`, so both prefixes are on the classpath and a package claim that
    names one name is claiming half.
  - `eu.wohlben.qits.artifacts.gc` and `.gc.dto` (module `gc`) are **garbage collection** — a
    process modelled from within this service rather than artifacts domain (the 2026-08-05 GC
    settlement). A subpackage rather than a sibling top-level name, and
    deliberately **not** `artifacts.control` in a second jar: adapters sharing a package with the
    code they extend is the split package Quarkus' `SplitPackageProcessor` warns about on every
    build.
  - **The dependency runs one way: `gc` → `artifacts`, never back.** `artifacts` does not know a
    collector exists, which is the property that keeps a retention rule out of the write path.
    `service` depends on both and hosts GC's only web surface, `api/GcPlanController`.
  - Where a strategy needs one of the store's package-private funnels, `artifacts` opens a **narrow
    public door** — `BlobReclaim` (over `BlobStore.delete`/`lastWrittenAt`/`blobGracePeriod`),
    `OciRegistryCollection` (`collectTag`/`collectManifest`), `NpmRegistryCollection` (`collect`) —
    each javadoc'd as the gc module's alone, plus `DaemonRegistryCollection`,
    `MavenRegistryCollection`, `DocsRegistryCollection` and `SbomRegistryCollection` (`collect`). The funnels
    stay package-private: widening them to public would hand their constraints to every package on
    the classpath to serve one module. `MavenRegistryCollection` opens **three** methods rather than
    one — `collect` for the hosted type, `evictProxiedArtifact`/`evictProxiedMetadata` for the
    cache — and the split is the point: one table holds both maven types' rows, so the doors differ
    by which repository type they refuse.
- `eu.wohlben.qits.registry`, `eu.wohlben.qits.npm`, `eu.wohlben.qits.maven`,
  `eu.wohlben.qits.daemon`, `eu.wohlben.qits.docs` and `eu.wohlben.qits.sbom` — the protocol
  wire stacks. Only the last three are written here; the first three arrive as beans on the
  qits-registries jars and register their own routes. They all *share* the blob store, so the split
  is by layer rather than by context: every byte and every row goes through a control-layer service
  (`OciRegistryService`, `NpmRegistryService`, `MavenRegistryService`, `DaemonRegistryService`,
  `DocsRegistryService`, `SbomRegistryService`, `BlobStore`), and the
  wire package holds routes, error envelopes and — for npm and maven — the outbound upstream
  client. A wire package that touched a Panache repository directly would be the drift to watch for.

`artifacts` carries its own `error/` package (`ArtifactsException` and the four status-carrying
subtypes) rather than the monorepo's `domain/error/*`. It always did — this is one of the few
places where the duplicate-now register in `migration-plan.md` §5 was already satisfied at import.

## Adding a dependency on another context

Don't. Declare a port in the package that needs it, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README.

**The six GC pin ports are the only ones left, and they break the rule in both halves on purpose.**
`CdDeploymentPins` (`GET /platform-deployments/api/pins`), `CiDaemonPins` (`GET /ci/api/daemon`),
`MaintenanceDependencyPins` (`GET /maintenance/api/pins`), `ConfigurationImagePins` (`GET
/configuration/api/pins`), `WorkspacesLaunchPins` (`GET /workspaces/api/pins`) and
`ProjectsLaunchPins` (`GET /projects/api/pins`) are ports this repo
also implements, as plain GETs on qits-net, and absent is *not* a supported configuration: they
throw, and a run that cannot read a pin deletes nothing at all. All of them were decided rather than
drifted into (the GC settlement's ⚖4, the P3D windows of 2026-09-04 for the third and fourth, and
the effective-pin addendum of 2026-09-05 for the last two), and all
live in the `gc` module, which
narrows the exception rather than removing it: the `artifacts` library dials nothing and is
domain-blind again, and the six outbound calls belong to the process that needs them. The keep-sets
are "which image coordinates would a restart or a rollback pull", "which daemon would a run launch",
"which internal versions do repositories' manifests on main still reference", "which container
images is the platform configured to launch" and — twice — "which image would THIS service pull if
somebody started something right now"; those six services are the only things that know, and
the alternative — a driver assembling those
lists and handing them in — puts a safety-critical input outside the service that acts on it, where
the two drift and the drift deletes something live. They are fetched **once per run** and never
cached: a cached pin list is a plan on stale facts, and two fetches inside one run can disagree.
**The last four are what make a P0D window possible at all**: with the window at zero the keep-set
IS the retention policy, which is only honest while what is in use is named outright rather than
inferred from a pull that may or may not have happened lately.
**And the last two are the fourth in a different TENSE**, not a duplicate of it: qits-configuration
holds what the NEXT deploy of a launching service will be handed, while that service keeps launching
what it was deployed with — so for the length of that gap the configured coordinate names an image
nobody pulls and the one everybody pulls is named by nothing. The consumer that pulls is the only
thing that can close it.

**Neither policy is re-derived here.** cd answers with a set of shas per application, and this repo
keeps all of them under one rule. It used to derive "ACTIVE plus the previous distinct sha" from raw
deployment rows, and the derivation was wrong — it stopped at the first older row of any status, so
an `ACTIVE(A) / FAILED(C) / DECOMMISSIONED(B)` history pinned an attempt that never served and
dropped the sha a rollback restores. A keep-set defined twice is a keep-set waiting to disagree.

Never add a JPA relation to another context's entity, and never a foreign key. Blobs address the
world by **string metadata**, and whatever they name is in a different database.

## Schema changes

`artifacts/src/main/resources/db/artifacts/postgresql/`, hand-written, its own lineage on its own
named datasource. **PostgreSQL only.** The H2 chain that used to live at `db/artifacts/migration`
went with the H2 driver in the postgres-blobs campaign; git history keeps its fourteen migrations,
and the one-time H2+disk → PostgreSQL copy is an ops tool that ships this schema rather than
replaying them. A new location as well as a new file, so a deployment carrying an H2
`flyway_schema_history` cannot half-apply the baseline. Never touch the monorepo's `db/migration`;
that is a different database.

**V1 is a fresh baseline and it IS the retired chain's end state.** Translation was mechanical where
it could be (`clob` → `text`, `timestamp(6) with time zone` → `timestamptz`) and every named foreign
key, index and check kept its name, because those names are what the suite's refusal assertions
read. Four decisions are departures from a literal translation, and the file's header carries the
argument for each:

1. **The three git-host tables are gone** — `git_pack`, `git_pack_file` and
   `git_repository_protection`. They survived in the H2 chain only because applied history cannot be
   rewritten; qits-githost owns that data in its own database, and a fresh baseline is the one place
   they could go.
2. **The four cache tables stay, and stay empty** — `oci_mirror_upstream`, `oci_mirror_tag_check`,
   `npm_proxy_packument`, `maven_proxy_metadata`. The caches went to qits-mirror-platform-service, but their
   repositories are live beans on the qits-registries jars: excluding a profile from bean discovery
   does not unregister a DAO. `OciRegistryService.resolveForPull` reads the upstream table on every
   pull whose first segment names no repository row, and `collectTag` deletes a freshness row for
   HOSTED tags too. Dropping either turns an unknown-image pull into a `500` where the client needs
   `404 NAME_UNKNOWN`. That was the retired V14's lesson and it is not re-learnable cheaply.
3. **No prefill of any kind.** The old V7 wrote three `oci-mirror` rows and their upstreams and V14
   took them out again, because a standing row is what kept the mirror path reachable after the code
   left. Every repository row this service needs comes from `ArtifactsRepositorySeeder` at startup,
   and no migration may embed a live-platform digest.
4. **`ck_artifact_repository_type` lists the EIGHT types this service registers**, not the ten the
   carried chain accepted — seven as V1 wrote it, eight since V3 re-enumerated it for `SBOMS`. This
   is where the set the code enforces and the set the database accepts
   became one set: `RepositoryTypeProfiles` indexes exactly these eight, `ArtifactRepositoryService
   .ensure` refuses anything else with a 400, and the constraint says the same thing one layer down.
   A migration copying rows in from a pre-split H2 store must **skip cache-type repositories**.

Widening the constraint is still a one-liner (drop by name, re-add naming every key) and the rule is
unchanged: re-enumerate the whole list from the profiles as they stand in the tree, never append.
`MigrationLineageTest` pins all four decisions plus the constraint's two directions — it owns a
database of its own on the suite's embedded postgres and runs Flyway over the real directory,
because every `@QuarkusTest` here wipes the tables, so what the chain leaves behind is invisible to
all of them.

**The blob store's three tables are in this lineage too** (`blob`, `blob_content`, `blob_chunk`),
copied verbatim from `qits-registries-javalib`'s `blobstore/src/main/resources/db/blobstore-tables.sql` — which is what
that library tells its consumers to do, and which its own suite applies unedited so the DDL is
exercised on every build of the jar. Keep the text identical, so a later diff between the two is
readable. `qits.artifacts.blobs-datasource=artifacts` is what points the store at them.

The persistence unit is the artifacts jar's own: `quarkus.hibernate-orm.artifacts.packages` names
`eu.wohlben.qits.blobstore.entity` AND `eu.wohlben.qits.artifacts.entity` in that jar's
`microprofile-config.properties` — the store's entities and the formats' plus this repo's — and
`service` no longer overrides it. The key **replaces** rather than extends, so every name it must
cover is spelled on that one line.

**`@Lob` is banned on every entity this service maps.** On H2 it was a clob and everything agreed;
on PostgreSQL it means a LARGE OBJECT, which Hibernate binds as an oid and the driver refuses to
read outside a transaction — so serving a cached document would 500 with "Large Objects may not be
used in auto-commit mode". The library entities that carry one are already bound with
`@JdbcTypeCode(SqlTypes.LONGVARCHAR)`; no entity in this tree has one, and V1's columns are `text`
to match.


## Garbage collection

All of it is the **`gc/` module** (`eu.wohlben.qits.artifacts.gc`, DTOs in `.gc.dto`) except the
route: `api/GcPlanController` stays in `service` with every other route. `README.md`'s "Garbage
collection" section is the contract; these are the rules that get "helpfully" refactored away.

- **One census.** `LiveBlobCensus` is what the store summary reads *and* what a GC plan reads. A
  second computation of "what is live" is a set the UI reports and a set a sweep protects, drifting
  until a sweep deletes something the page called referenced. `ArtifactExplorerService.storeSummary`
  delegates to it; the explorer's own tests are the byte-exactness proof.
- **Per repository is a FILTER over the one plan, never a second planner.** `GET /gc/repositories`
  and `GET /gc/repositories/{repo}/plan` exist, and neither applies a rule of its own: a
  repository's figures are `GcStrategy.Plan.scopedTo(name)` — its share of its type's plan —
  reconciled against the same census. That is sound because **no rule of either engine reaches
  across two repositories**: both own-engine adapters qualify their identity group with the
  repository name (`OciImagesGcAdapter.group()` is `repository + "/" + image`), and the cache engine
  is per candidate. A second planner would be a second policy over one type, which is the mistake
  the whole design refuses. The one subtraction that must never happen is spelled in `scopedTo`'s
  javadoc and pinned by `GcScopedPlanTest`: a blob condemned in repositories A **and** B stays
  **retained** in each scoped plan, because the other one's row is standing in that view. So
  `blobsRetained` is widened, never `blobsReleased` narrowed — and the scoped plan still states the
  type's whole post-plan live set, which is what lets `BlobSweep` consume it unchanged.
- **A scoped sweep narrows what is planned, never what is protected.**
  `POST /gc/repositories/{repo}/sweep` deletes that repository's identity rows through the same
  per-type funnels — the adapters needed no change, because every `GcIdentity` carries its
  repository and their delete loops already dispatch on it — and then runs the *same* whole-store
  blob reconciliation with only that plan applied. Three belts, none of which knows what a
  repository is. Two rules that look like candidates for a per-repository relaxation and are not:
  the **whole-run abort** on an unreadable pin source holds identically at repository scope (one
  repository is not a smaller blast radius at the blob layer — its released bytes can be the last
  local reference to content a pin names by digest), and a repository whose type nobody collects
  answers a **receipt with its reason and zeros**, not an error status. The one ordering
  difference from the whole-store run is deliberate: the *name* is resolved before the pins are
  read, because a repository that does not exist is a fact about the request and an aborted receipt
  for it would claim a run was attempted.
- **The per-repository figures do not add up, and that is the honest answer.** Σ(per-repo) ≤ the
  global sweep figure: a blob two repositories both condemn dies in a whole-store run and in
  neither scoped one. Proportional attribution was rejected — a fraction of a blob frees nothing,
  and it would be the first number in this feature matching no operation anyone can run. Both
  figures ship per repository: `structural` (no grace — what the rule condemns) and grace-applied
  (what a run now would unlink, plus what the window withholds). The listing column shows the
  structural one, matching `GcTypePlan`'s per-type semantics.
- **Row-less blobs are untouchable, structurally.** A blob becomes a candidate only by *losing* its
  last identity row to a strategy's own deletion, so one that never had a row cannot be reached. This
  is not an allowlist and must not become one: 124 MiB of this store has no row, and one of those
  blobs is the CI daemon binary every build downloads by digest.
- **Two engines, and this supersedes the "no shared policy" rule.** Settled by user decision
  2026-08-05 (the GC settlement; history in the superproject's git). The rules live in
  `CacheEvictionStrategy` (a
  cache holds re-fetchable content, so everything unaccessed past the window goes) and
  `OwnArtifactsStrategy` (own artifacts keep the last 2 released versions per identity group, the
  rest ages out), mapped onto types by configuration. **Only `OwnArtifactsStrategy` is in this
  repository** — the cache engine and its three adapters went to qits-mirror-platform-service with
  the types they collect, in phase 4 of the byte-plane split. The doctrine is stated in both halves anyway,
  because the rule below is what stops the surviving engine being re-split. **The superseded rule was "one bespoke
  strategy per type, no shared policy code, no retention-rule framework"** — it is history, and
  re-splitting an engine back into per-type rules is now the wrong change, in the same words the
  old rule used against merging them. Two things it said still hold and are not softened: a type
  has exactly **one** policy (two beans claiming it is a collision, reported and never merged), and
  the per-type *facts* stay in that type's own `GcTypeAdapter` — no engine may switch on
  `RepositoryType`, no adapter may carry a window or a keep-count.
- **The grace window gates identity rows, not only blob unlinks.** A blob can only be swept by
  *losing* its last row, so a row deleted while the blob's file is inside the window would strand
  the blob — row-less, untouchable, never reclaimed. `GcStrategy.apply` therefore withholds an
  identity whole when any blob it releases is still in grace; `GcSweepExecutor` (behind
  `POST /artifacts/api/gc/sweep`) applies only plans it computed in the same request, and on a
  store younger than the window a sweep provably deletes nothing.

- **Live pins are read once per run, and a source that cannot answer aborts the whole run.**
  `GcPinSources` reads six sources at the start of every plan and every sweep, never cached, and
  folds them into one `GcPins`: qits-platform-deployments (`GET /platform-deployments/api/pins`,
  what is serving), qits-ci (`GET /ci/api/daemon`, what a runner would launch),
  qits-platform-maintenance (`GET /maintenance/api/pins`, which internal maven/npm/docker versions
  repositories' manifests still reference on main), qits-configuration
  (`GET /configuration/api/pins`, which container images the platform is configured to launch), and
  qits-workspaces / qits-projects (`GET /workspaces/api/pins`, `GET /projects/api/pins`, which
  images each of them would pull TODAY, out of the configuration it is actually running with).
  The last four answer for **consumption** where the first two answer for execution, and they are
  what the P0D windows rest on. The two launch answers are kept as **separate keep-sets** —
  `workspaceLaunchImages` and `projectLaunchImages`, rules `BY_WORKSPACE_LAUNCH` /
  `BY_PROJECT_LAUNCH` — because both services launch `qits/workspace` and a folded set could not say
  which of them saved a tag. `launches` on such a pin is provenance: parsed, carried, deciding
  nothing. Their coordinates are spelled exactly as the adapters spell
  identities — `g:a:v`, `name@version`, and for images the FULL name (`qits/workspace-base:<tag>`,
  repository row plus image, which cd's `applicationName` does not carry) — so every lookup is an
  equality test and never a translation. The maintenance answer's `repositories` array is scan
  provenance: shape-checked and dropped, because how stale an inventory may be is maintenance's call
  and it makes it as a 503. An ecosystem this store cannot file is **refused**, not skipped. `GcSweepExecutor`
  returns a receipt with `aborted` and deletes nothing — before the census — when any source failed;
  this replaces the old per-type fail-closed for the sweep. The rule is all-or-nothing because blobs
  dedupe globally: a tarball one type releases may be the last reference to bytes a pinned image also
  names. `GET /gc/plan` must **never** 500 on it — it answers `executable: false` with
  `pinFailures` and the pin-dependent types refused.
- **Pins may be supplied in the request, and the parsers are shared.** `POST /gc/plan` (the `GET`'s
  twin) and both sweeps take an optional
  `{"pins":{"deployments":…,"ciDaemon":…,"dependencies":…,"configuredImages":…,
  "workspaceLaunches":…,"projectLaunches":…}}`, each member the
  peer's response verbatim, read by `CdHttpDeploymentPins.parse` / `CiHttpDaemonPins.parse` /
  `MaintenanceHttpDependencyPins.parse` / `ConfigurationHttpImagePins.parse` /
  `LaunchImagePins.parse` — the
  same code the HTTP readers use, so one document cannot be read two ways. The last of those is
  shared by BOTH launch readers, which is allowed only because it takes the answering service's name
  and puts it in every refusal — a message saying "a launch service" would send a debugger to the
  wrong one of two. **The envelope grew from
  two members to four on 2026-09-04 and to six on 2026-09-05, which is why this service ships LAST in
  each of those rollouts**: an orchestrator still sending an older envelope supplies a partial
  keep-set, and a missing member is fail-closed. qits-platform-orchestrator
  sends it: one platform-wide pin set, read once per run, given to every deleter. A missing member
  is that source **unanswered**, not "nothing is pinned", so the run refuses as before; no body is
  the old call exactly. `POST /gc/plan` allows `qits:admin` **or** `qits:system` (the `GET` stays
  admin-only, the sweeps stay `qits:system`): the orchestrator is a machine, holds
  `qits:system,qits-platform:system`, and is already allowed to run the sweep this plan feeds. As a
  write method it also sits inside `AdminWriteGuard`. **`GET /store/summary` allows the same pair**,
  for the same caller and the same reason: the orchestrator reads the store summary before and after
  a run so the run's own receipt states what the sweep cost, and a machine allowed to execute the
  sweep but not to read the number it moved would be the strange posture. The readers are the no-body fallback and
  **send no credential**, so they `401` on an authenticated platform — a known gap, fixed elsewhere,
  not here.
- **Two pin semantics that look like bugs if you "fix" them.** A blank `daemonVersion` is an
  *answer* meaning "no daemon is pinned" (the shipped default) and must not abort a run; a 64-hex
  daemon version pins the **blob** at that digest as well as any row, because the pin has been a
  sha256 digest since the daemon shipped and may name bytes no row exists for.
- **The pin config keys are `qits.artifacts.gc.pins.cd-*`, `.ci-*`, `.maintenance-*`,
  `.configuration-*`, `.workspaces-*` and `.projects-*`** (`-base-url` and `-timeout` each), the
  first renamed from
  `qits.artifacts.gc.oci.cd-*`, and they live in the `gc` jar's own
  `META-INF/microprofile-config.properties`. A deployment carrying the old spelling silently loses
  the value.
- **The settlement's numbers are `P0D` for all six own types and `PT6H` for the blob grace period**
  since 2026-09-05, down from `P3D`/`P2D` the day before and `P30D`/`P90D`/`P7D` before that. The
  windows are in the `gc` jar's config, the grace period in the `artifacts` jar's.
  **Zero is not "collect harder", it is the statement that RETENTION IS THE KEEP-SET**: an identity
  lives because a pin source or a belt names it, and access — the one input here nobody owns —
  decides nothing. It is safe because per-push CI is retired: every QA build resolves the octopus
  FOLD with current main, and main's manifests are what the maintenance dependency pins name, so a
  branch's build resolves the keep-set by construction. The one branch that can miss hand-pins an
  internal version three releases stale, which fails loudly and is re-bumped in a commit.
  **`maven-packages` is where the window decides least, and that was bought at the price of an
  outage**: since 2026-09-05 no published release is age-collected at any window, so zero reaches
  only superseded snapshot sets there. See the bullet below.
  **The grace period is the only clock left and it is race safety, not retention**: it gates
  identity rows as well as blob unlinks, so bytes pushed minutes ago — an image racing its
  deployment row, a lib racing its consumer's fold — are immune whole for six hours while their pin
  appears. Raising a window is a decision about disk; removing a pin source is a window owed.
  If the Phase-A env overrides `QITS_ARTIFACTS_GC_TYPE_OCI_IMAGES_WINDOW` and
  `QITS_ARTIFACTS_GC_BLOB_GRACE_PERIOD` are still set on qits-configuration, REMOVE them — they
  shadow these defaults.
- **The dry-run report is the review surface, and four of its parts are load-bearing.** `summary`
  is first in `GcPlanReport` because it is what a human reads first — executable yes/no, the
  reclaim in bytes and in units, one line per type — and it is **derived** by `GcSummary` from the
  rest of the report, never re-computed: a summary that decided for itself what would die would be
  a second policy. `pins` (`GcPinSource`, built in `GcPinSources` and carried on `GcPins`) gives
  each source its url, outcome, duration, pin count and the keep-identities it produced, and the
  **sweep receipt carries the same section**, an aborted one included. Excluded types say
  `GcRules.EXCLUDED_NOTE` on their own line as well as in the configuration echo — `dead: []`
  beside a claimed strategy otherwise reads as a rule that ran and found nothing.
- **The own engine is live over every configured type.** The settlement of 2026-08-05 replaced the
  bespoke strategies with `CacheEvictionStrategy` + `OwnArtifactsStrategy`,
  mapped onto types by `qits.artifacts.gc.type.<wire-name>.strategy|window` (`GcTypeConfig`).
  Here: `oci-images`, `daemon-binaries`, `npm-packages`, `maven-packages`, `docs` and `sboms` run
  the own engine, and only the two CI types are `excluded` — a decision rather than a gap. **The three cache
  types have no entry at all**, because their profiles are not registered in this service; a
  `RepositoryType` constant is not enough to need a line, a registered profile is.
  `GcTypeConfigTest` is the guard, and it is edited
  **deliberately, once per workstream**: the moving types' new dead sets are written out there, and
  every other type stays identity-for-identity as it was.
- **The binder is `OwnGcStrategy`; read it before touching a live type** (`CacheGcStrategy` went
  with the cache engine). It is wiring, not policy: read the configured window, refuse if a deployment
  reconfigured the type onto the other engine, hand the engine a `GcPinned`, route `apply` back to
  the adapter. **Every type on either engine declares `readsPins()`** — a pin can name a **blob** by
  digest and blobs dedupe globally, so a run with an unreachable qits-cd or qits-ci reports them
  refused rather than planning them against "nothing is pinned". `OwnGcStrategy` composes two
  keep-classes in order: `GcTypeAdapter.pinnedBy` (the type's own coordinates) first, the digest
  check as the floor under it.
- **`GcTypeAdapter.pinnedBy` takes the whole enumeration, and that is not an accident.** A
  coordinate pin is often a fact about a *group* — "this image's newest build" cannot be answered
  from one tag — and reading the rows once per run is what keeps a plan judged against one snapshot.
  `OwnArtifactsStrategy` has an overload taking the candidates for the same reason: the binder
  enumerates once and passes the list on.
- **`oci-images` has a second structural belt: a tag literally named `latest` is always kept.**
  CI step recipes pull `qits/build-images/*:latest` by name; `latest` is not a calver so no release
  rule covers it, and the host-side keep-prefix suppresses exactly the pulls that would keep it
  access-warm — so the one coordinate most used is the one most likely to look cold. Deleting it
  404s a fresh host's first pull of a step image, which takes out every pipeline on that machine,
  and keeping it costs nothing because `latest` shares the newest push's blobs.
- **The newest-build-tag belt is `oci-images`' one derived pin, and it reads `updated_at`.** It is
  the pull the *next* deploy will make, which qits-cd cannot answer for because it has not happened
  — the whole safety net for an image cd has never deployed (`qits-spa-home`, measured). Reading the
  candidate's access time instead would disarm it exactly where it is needed: a cold, never-deployed
  image is the case it exists for.
- **A manifest under a tag a run condemns is collected on the NEXT run.** A tagged manifest's
  identity is its tag and an index child rides on the index's closure, so neither is a candidate of
  its own — the mirror's rule, now `oci-images`' too. The bytes are safe in the meantime because the
  sweep's pre-unlink re-census sees the surviving row; what runs a run ahead of reality is the
  dry-run's per-type figure, not the store.
- **`maven-packages`' identity is a COORDINATE, not a row.** A version is a set of files, and half
  a version is a broken resolve, so `MavenPackagesGcAdapter` folds rows into
  `groupId:artifactId:version` (timestamped snapshots into their own resolvable coordinate) and the
  grace window withholds the whole set. Its one derived belt is **the newest deployable set of every
  snapshot line** — what `maven-metadata.xml` redirects `1.0.1-SNAPSHOT` to; deleting it would point
  the document at a file the store no longer has. No N-per-line rule was invented: §3.6 named the
  shape and never priced it, so the window decides.
- **`npm-packages` DOES NOT AGE-COLLECT PUBLISHED RELEASES EITHER. Same rule, ported the same day.**
  Withdrawn the evening of 2026-09-05, hours after maven's, when the zero-window sweep took
  `@qits/ui-components` from eight versions to three and `@qits/angular` to two — breaking fifteen
  frontend lockfiles and failing two services' release runs on `npm ci`. The maven reasons all
  transfer (an install is served from `node_modules` and a warm cache, not from here; ten megabytes
  against 28.8 GB of images) and one is sharper: **no pin source on this platform can see an npm
  pin**, because a frontend's lockfile is not on the service's main — it is reached through a
  SUBMODULE GITLINK a release tag freezes. Fifteen services' gitlinks name frontend commits pinning
  five different versions of one package. The keep is `NpmPackagesGcAdapter.pinnedBy`, the
  correction rides `NpmPackagesGcStrategy.note()`, and the window now governs **prereleases only**
  (the per-push `-main.g<sha>` builds — npm's analogue of maven's snapshots). No whole-or-nothing
  repair was needed: an npm identity is one row and one tarball, removed with its tombstone in one
  transaction.
- **A collected npm version can be RESTORED with the bytes it had.** `NpmRegistryService.publish`
  compares the incoming tarball's blob id against the tombstone's `tarball_blob_id`: equal means a
  restore (the stone is cleared in the same transaction), anything else is the 403 it always was,
  and a stone with a null blob id refuses everything. The tombstone protects one property — a
  coordinate must never come to mean DIFFERENT bytes — and an identical republish cannot violate it.
  This works because `npm pack` is reproducible (pacote normalises mtime/uid/gid): repacking a
  source tag reproduces the published tarball byte for byte, measured on tag `2026.904.202810` of
  `@qits/ui-components` against the integrity fifteen lockfiles carry. Shipped in
  qits-registries-javalib `2026.905.211257`.
- **`maven-packages` DOES NOT AGE-COLLECT PUBLISHED RELEASES. Do not put the belt back.** Withdrawn
  2026-09-05 after the `P3D` access rule deleted 67 published `eu.wohlben.qits` coordinates at
  `01:58Z` and every gating build on the platform stopped resolving. The three reasons, so this is
  not re-derived the next time the store looks large: a jar is fetched **once** and answered out of
  local `~/.m2` caches forever after, so age here measures cache warmth rather than need; the
  dependency pin names what main's manifests **directly** reference (13 coordinates against hundreds
  held) and is a floor under nothing; and the whole hosted maven repository rounds to nothing beside
  28.8 GB of images, so the trade was a platform-wide outage for a few megabytes. The keep is
  answered in `MavenPackagesGcAdapter.pinnedBy` — the adapter's own facts, so `OwnArtifactsStrategy`
  and the other three own types are untouched — and `MavenPackagesGcStrategy.note()` carries the
  correction onto every report line, because the configuration echo is the engine's sentence and
  still describes a belt. The window governs **superseded timestamped snapshot sets only**.
- **A maven coordinate is removed inside ONE transaction.** `MavenRegistryService.collect` is
  `@Transactional` per *file*, so the delete loop used to commit path by path: a throw on the second
  file left the first one deleted, and since paths sort `.jar` before `.pom` the shape it leaves is a
  version whose pom answers 200 and whose jar answers 404 — the half-sweep the identity model claims
  to prevent. `MavenPackagesGcAdapter.delete` wraps each coordinate in `QuarkusTransaction`; a
  failure is a no-op reported against an identity that is still whole.
- **`daemon-binaries` has no tombstone and must not grow one.** npm has one because a deleted
  version re-opens its name under somebody's lockfile; a daemon version is resolved by a pin a
  bootstrap re-reads, so a re-release at a collected version is legitimate. Its funnel is
  `DaemonRegistryCollection` → package-private `DaemonRegistryService.collect`, the fourth narrow
  door. Adopted rows carry the **digest hex** as their version, so the adapter's version order ranks
  those below every calver one — comparing 64 hex characters as a number ranks the oldest thing
  there as the newest.
- **`sboms`' identity is `packageType/packageName@version` and nothing pins one.** The coordinate is
  the `SoftwareRelease` identity verbatim, so a report line needs no translation; the version comes
  last and is parsed with `lastIndexOf('@')`, because a package name carries `@`, `/` and `:` of its
  own while `SbomPaths`' `VERSION` charset admits no `@`. The belt counts per **package**, not per
  repository — every artifact on the platform publishes into the one `sboms` root. `pinnedBy` is
  deliberately not overridden: qits-platform-maintenance re-reads a live artifact's document and
  that read moves `accessed_at`, so "what maintenance tracks" already reaches the engine as access,
  and a pin source restating it would be the same keep-set decided twice. Its funnel is
  `SbomRegistryCollection` → package-private `SbomRegistryService.collect`, the sixth narrow door,
  and it writes **no** tombstone: a collected identity re-opens for a republish, as the daemon's
  does.
- **The hosted adapters still filter by the repository row's type, and that line stays.** `npm_version`
  and `maven_artifact` are shared with the cache half in the `qits-registries-*` jars. No cache row
  can exist in this database any more, but a leftover one would otherwise be collected under the
  hosted rules — and a filter dropped because the other type moved out is a filter nobody restores
  when it comes back. Asserted from the hosted side in `NpmPackagesGcStrategyTest` and
  `MavenPackagesGcAdapterTest`. The proxy eviction doors themselves
  (`evictProxiedVersion`/`evictProxiedPackument`, `evictProxiedArtifact`/`evictProxiedMetadata`)
  are the mirror's, with the engine that called them.
- **Engines hold rules, `GcTypeAdapter` holds facts, and neither may grow the other's half.** No
  engine may switch on `RepositoryType`; no adapter may carry a window or a keep-count. The facts
  are: what identities exist, what a release is here, which of two is older, and how a row is
  deleted. Effective access time is `max(created/published/fetched, accessed_at)` — creation counts
  as the first access, folded in by the adapter — so a freshly published artifact is young rather
  than never-read.
- **Pins are a keep-class checked before the access rule** (`GcPinned`), and the rule comes back as
  a sentence so the report names which service saved an identity. A pin is the one fact no timestamp
  implies: a container running untouched for months still pulls its image sha on restart.
- **Every `RepositoryType` needs a configuration entry.** `GcTypeConfig.of` refuses rather than
  defaulting to `excluded`, so adding a constant means adding two lines to the `gc` jar's
  `META-INF/microprofile-config.properties`. The mapping's prefix is `qits.artifacts.gc.type`, not
  `qits.artifacts.gc`: a mapping rooted at the wider prefix would claim `blob-grace-period` and the
  pin urls, which other classes read.

Eight strategy classes exist, one per registered type, and **six of them are thin binders rather
than rules** — `OciImageGcStrategy`, `DaemonBinariesGcStrategy`, `NpmPackagesGcStrategy`,
`MavenPackagesGcStrategy`, `DocsGcStrategy` and `SbomGcStrategy` on the own engine, each naming its
`*GcAdapter` and nothing else. The three cache binders left with their engine. A class that is four lines long is doing its
job; a rule appearing in one is the settlement being unpicked one type at a time. The two CI stubs
(`CiScreenshotsGcStrategy`, `CiVideosGcStrategy`) are the exception and are deliberately two
classes, because their intended rules diverge in kind (branch-scoped against byte-budgeted) and one
shared base for two unimplemented rules would decide their shape before either was designed. Both
types are `excluded` in configuration, the stubs plan `nothingDies` at zero rows under a `note()`
naming the intended rule, and they **fail closed the day rows exist** — a plan over rows with no
implemented rule is a guess. A few things the strategies share cost time otherwise.

- **All of them are `@Singleton`, not `@ApplicationScoped`, and the reason is the report.** `GcPlanner`
  names a strategy by its class's simple name, and a normal-scoped bean would answer that through its
  client proxy — `OciImageGcStrategy_ClientProxy`, a name in no source file. `@Singleton` is a
  pseudo-scope, so there is no proxy and still one instance. `GcPlanner.nameOf` also unwraps a proxy
  if it gets one, so a strategy that forgets is merely inconsistent rather than misreported; both
  names are asserted.
- **No strategy reads the census any more.** The census carries blobs, not identities, so every
  rule is computed from the type's own rows — `oci_tag`/`oci_manifest` plus `OciManifestFootprints`
  for one, `npm_version`/`npm_dist_tag` for the next, `maven_artifact` and `daemon_binary` for the
  other two. The two blob sets they return are in the census's vocabulary, which is what the
  substrate reconciles over, and each suite asserts `blobsRetained` equals the census's own live set
  for the type when nothing dies.
- **No strategy performs an HTTP call any more** — every type on an engine declares `readsPins()`
  and is handed the run's `GcPins`. See "Adding a dependency on another context" above; the suites
  point all six of `qits.artifacts.gc.pins.{cd,ci,maintenance,configuration,workspaces,projects}-base-url`
  at a closed port, so `GcPinsTest` and `GcPlanControllerTest` assert the refusal path rather than avoiding it.
  A new pin source therefore needs a closed-port line in **three** test `application.properties`
  (`gc`, `artifacts`, `service`) and in `PackagedProcessIT`'s overrides, or the suite dials a real
  host. Only the two CI stubs plan
  on a run whose pins failed, which is what keeps such a report readable at all.

Two things are npm's alone, and the plan is explicit that docker needs neither:

- **The republish tombstone (V6, `npm_version_tombstone`).** Version immutability is enforced by
  looking for the row, so deleting a version re-opens its name for a publish with different bytes.
  `NpmRegistryService.publish` checks the tombstone as well as the row and answers a 403 that says
  *garbage-collected*, not *immutable* — a pusher told "immutable" goes looking for a version that is
  not there. A separate table rather than a flag on `npm_version`, because the packument is assembled
  from those rows at read time and a marker column would need a `where` clause in every reader.
- **`NpmRegistryService.collect` is package-private with exactly one caller**,
  `NpmPackagesGcAdapter.delete`, which reaches it across the jar boundary through the
  `NpmRegistryCollection` facade: it is the only way a version row is ever removed, it writes the
  tombstone in the same transaction, and it refuses a version a dist-tag still names (a dist-tag
  pointing at a version the packument no longer lists is a broken package to every npm client). It
  shipped ahead of its caller so the tombstone was never a step someone had to remember.
  The proxy's twins — `evictProxiedVersion`/`evictProxiedPackument`, which write **no** tombstone —
  are still on the shared jar but have no caller here; they went to qits-mirror-platform-service
  with the cache engine.
  `DaemonRegistryService.collect` is the daemon twin behind `DaemonRegistryCollection`, and it
  deliberately writes **no** tombstone.
  `OciRegistryService.collectTag`/`collectManifest` are the OCI twins — package-private behind
  `OciRegistryCollection`, called by `OciImagesGcAdapter.delete`, and `collectManifest` refuses a
  manifest a tag still names. `collectTag` also deletes the tag's `oci_mirror_tag_check` row, which
  costs nothing for a hosted tag and is what let both OCI types come through one door: an auxiliary
  row cleaned inside the funnel cannot be forgotten by a caller.

No strategy is left whose whole rule is "nothing dies" — `maven-packages` was the last, and the
settlement priced it with every other own type.

**The hosted adapters filter `npm_version` and `maven_artifact` by the repository row's type**, and
that stays although no cache row can exist here: both tables are shared with the cache half in the
`qits-registries-*` jars, and a leftover row must not be collected under the hosted rules. Asserted
from the hosted side by `NpmPackagesGcStrategyTest` and `MavenPackagesGcAdapterTest`.

`BlobStore.delete` is package-private for the same reason `promote` is the one write funnel: the
constraints (the grace window off the blob's `stored_at` column, and the pre-unlink guard taken
under the same advisory lock `promote` takes) only hold if there is one way in. Adding a second caller,
or widening it to public, removes them without failing anything. The `gc` module reaches it through
`BlobReclaim`, and its two siblings `OciRegistryCollection` and `NpmRegistryCollection` do the same
for the registries' collect funnels — three named doors with one documented owner each, which is why
none of the three funnels had to become public when GC moved out.

## Authentication

User authentication happens at `qits-gateway`. This service resolves a principal from a trusted
header (`X-Qits-User`, read by `ForwardAuthMechanism` in the published `qits-auth-core` jar) and
authenticates no user itself.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.

**The `@RolesAllowed` answers differ by suite, and the packaged ITs must play the gateway.** Under
`@QuarkusTest` the `%test`-scoped synthetic dev-user answers every role check, which is why the
in-JVM suites need no headers. The **packaged** process runs `LaunchMode.NORMAL`, where
`ForwardAuthMechanism` deliberately stays anonymous — so every `/artifacts/api` read 401s unless
the request carries the `X-Qits-User`/`X-Qits-Roles` pair the gateway would assert.
`PackagedProcessIT.asOperator()` is that pair for RestAssured, and `stories.support.StoryBrowser
.asOperator(flow)` sets the same headers on the Playwright browser context before the first
navigate — one copy, used by all seven explore stories; the wire cases stay header-free on
purpose, because tokenless-on-qits-net is their contract. `StoryBrowser` is the only thing in
`stories/support/` that touches auth, and it uses `Flow.page()` rather than a recorded step
precisely because it is harness plumbing and not a step in anybody's story.

- `mvn verify` runs 290 tests (49 in `artifacts/`, 115 in `gc/`, 126 in `service/`) in about a
  minute — counted from the surefire reports — and then the failsafe ITs against the packaged
  fast-jar: **39 tests across 24 IT classes**, of which the three `qits`-category stories and
  `OciConformanceIT` skip without their gates. The `service` module opts back into ITs
  (`skipITs=false` in its pom), because `TokenValidationBootstrapIT` and the 21 story classes under
  `eu.wohlben.qits.stories` are **userflows** and `target/userstories/` is a build product a plain
  verify regenerates. The non-gating half of `.config/qits/ci-event-release-request.yml` runs the
  same verify at the release-request fold in ONE step on `userflows-base` (Maven + baked Chromium + skopeo,
  `user: pwuser` for zonky's initdb, the step-image contract since qits-build-images-oci 2026.828.162434) — one step because
  step containers share no workspace (each clones for itself), so the SPA bundle must be built in
  the same container that packages it: the lockfile origin swap runs in the script and Quinoa's
  managed node (the pinned 22.22.0) does the real `npm ci` + build with `npm_config_*` registries
  from the environment. The 120s settle-hold it used to open with is gone: no QA run deploys
  anything, so there is no container swap to wait out. It publishes the reports as the
  `@userflows/qits-artifacts` docs site, version = the fold's merged sha, and it declares
  `gating: false` — a red story fails the run and shows red without holding the release request's
  build gate. **Per-push CI is retired**: this repository has neither `ci-post-receive.yml` nor
  `ci-event-build.yml` nor `ci-event-userflows.yml`, and nothing builds a branch. Two pipelines are
  left — `ci-event-release-request.yml`, the QA pipeline above, which reacts to a release request's
  octopus fold (`release/<id>`) and is discovered at `main`'s head so a fold cannot edit the CI that
  gates it; and `ci-event-release.yml`, which builds the release tag on `SCMRelease` and publishes
  the CalVer coordinate. The maintenance bump train's release call is not a trigger here either: it
  opens a release REQUEST against qits-projects, with its own credential, on the push that creates
  the bump.
- **Every module's suite runs on a REAL PostgreSQL**, spawned as a child process from zonky binaries
  that resolve as ordinary Maven artifacts. Not a container: the clone-alone rule forbids one, and
  the store's only engine is postgres now — `bytea`, an advisory lock, a partial index and an
  on-conflict promote exist on no other, and the lineage is a PostgreSQL lineage. Each module owns a
  copy of `testdb/EmbeddedPg` + `EmbeddedPgConfigSource` (registered through `META-INF/services`,
  the only hook that runs before Quarkus reads config) and its own database name — `artifacts_test`,
  `artifacts_gc_test`, `artifacts_svc_test`, plus `artifacts_lineage_test` for the lineage suite and
  `artifacts_it` / `artifacts_conformance_it` for the two ITs. Copies rather than a shared class,
  because the modules share no test classpath; distinct databases, because two suites in one build
  must not wipe each other's rows. The url is never written down — the instance takes a free port,
  so the config source supplies it at ordinal 500, over the unresolvable `QITS_RESOURCE_DB_*` the
  shipped config carries.
- **`quarkus.http.test-port=0` in every module.** Port 8081 is the platform's own npm registry on
  these hosts, so a fixed test port fails with "Port already bound: 8081" against a service that has
  nothing to do with the build. The registry, npm and maven
  wire suites went to the qits-registries jars with the code they drive, so what runs here is the
  hosted docs and daemon wires, the explorer, GC and the JAX-RS boundary. Nothing here
  needs docker — and that is the constraint that shapes the registry suite: `docker`, `podman` and
  `skopeo` may not be assumed present, so `registry/OciClient` + `registry/TinyImage` synthesise a
  real image in memory and drive a full push/pull over the JDK `HttpClient`. **That rule is
  unchanged for every unit suite and `registry/OciClient` is unchanged with it**; it is now
  *qualified* at the IT layer only. `userflows-base` ships a skopeo (since 2026.829.153130), and
  the three `qits`-category stories drive that real binary — gated `@EnabledIf` on
  `Cli#skopeoPresent`, so on a workstation without one they emit nothing rather than failing. A
  machine having skopeo is never assumed anywhere; a machine *not* having it is still green. It uses that rather than
  RestAssured for one reason that matters: `BodyPublishers.ofInputStream` sends a body with no
  `Content-Length`, which is the chunked path docker actually uses and the one the wire ceiling does
  not gate. RestAssured also percent-encodes the colon in a digest, so any assertion carrying one
  needs `urlEncodingEnabled(false)` or it tests RestAssured rather than the registry.
- The npm suite is the same shape and the same rule: `npm/TinyPackage` + `npm/NpmClient` synthesise
  a real gzipped tarball and a publish document and drive the round trip. RestAssured is unusable
  for the packument routes specifically — it re-encodes a path, and the whole question there is
  whether `@qits%2fangular` reaches the router with its escape intact. **There is no network**, and
  there is no proxy suite here any more — `StubNpmRegistry`, `StubMavenRepository` and
  `StubOciRegistry` went to qits-mirror-platform-service with the cache halves they stand in for. The rule
  they were built on still governs anything new: a stub is driven over HTTP rather than by touching
  its fields, because Quarkus instantiates a `QuarkusTestProfile` in **two** classloaders, so a
  static singleton exists twice and the application ends up talking to a different instance than the
  test configures.
- The maven suite is the hosted half of that shape again: `maven/TinyArtifact` synthesises a real
  jar in memory and `maven/MavenClient` drives the deploy/resolve round trip over the JDK
  `HttpClient` — no RestAssured, no maven binary, no network, because the path grammar and the
  encoding questions are the point. `MavenSnapshotTest` drives the ⚖1 flow the way a real client
  does: timestamped files PUT as ordinary paths, then the derived version-level metadata read back.
  Both name their own artifact per case, because the service module's suite has no table reset and
  releases are immutable — a shared coordinate would be order-dependent in exactly the way the
  registry exists to refuse.
- **`qits.artifacts.oci.mirror.endpoint-override` still ships pointed at a closed port in the test
  config, and V14 is not a reason to remove it.** The rows are gone, so nothing resolves into the
  mirror path any more — but the `qits-registries-oci` jar still carries the miss path, and any test
  or fixture that puts a mirror row back would dial a real public registry with no key in the way.
  It costs one line and it is the only thing standing between this suite and the internet.
  `src/test/resources/application.properties` points it at `http://localhost:1`, and
  `PackagedProcessIT` passes the same value to the launched binary.
- **Fixture content must be unique per RUN, not merely per test**, and the reason narrowed rather
  than disappeared when blobs moved into the database. `ArtifactsTestSupport.reset` and `GcFixture
  .reset` now wipe `blob` and `blob_content` per test (in that order — the identity row points at
  the content, and the content cascades to its chunks), so the `service` module is the one left
  without a blob wipe. Blobs dedupe globally and content-addressed: reuse an earlier case's image
  content there and its layer is already stored, so any count over stored bytes comes out one short
  with nothing in the failure to say why. It is the one thing to check first if a count is off by
  one. `backdate` moves the blob's `stored_at` column, which is the same clock a production sweep
  reads.
- `mvn verify -Dnative` runs those against the compiled binary instead of the fast-jar.
  `PackagedProcessIT` was the first suite to start a **process** rather than an in-JVM Quarkus; its
  siblings do too — `TokenValidationBootstrapIT` with the rollout gate on against a recording mock
  idp (the two `authentication` userflows, and the only stories with a profile of their own), and
  the 21 classes under `eu.wohlben.qits.stories` all sharing `PackagedProcessIT.TargetDirState` so
  **one** boot serves every one of them and `PackagedProcessIT` besides — two launched processes in
  the whole IT phase, not twenty-four. Seven of the stories drive headless Chromium over the
  embedded SPA (the UI ships inside the jar, so the browser runs through the service). It is where the
  route stacks are proved to coexist and where the binary is proved to boot at all.
  It is also the **only** place the web UI can be tested at all: Quinoa logs "Quinoa is disabled by
  default in tests" and registers neither the static resources nor the SPA re-route, so a
  `@QuarkusTest` asserting anything about `/` passes against a process that has no client in it. Two of them are that, and they are the guard on
  `quarkus.quinoa.ignored-path-prefixes`.
  Its `@TestProfile` hands the launched process a database, because the shipped config deliberately
  has none: the `QITS_RESOURCE_DB_*` expressions are unresolvable outside a deployment, so without
  the profile the binary dies at Flyway. **It also turns the access log on** — that is the story
  catalogue's network tap, and the whole of it is in `stories/support/AccessLogSource`; see "The
  network diagram is observed, never narrated" below. It passes the suite's own embedded-postgres url, username
  and password as `-D` flags. That works across the **two classloaders** a `QuarkusTestProfile` is
  instantiated in because `EmbeddedPg` publishes its port to a system property, which the two copies
  of that class share; a static field alone would start a second postgres. Do not add a build-time
  property there — an IT cannot re-augment, which is also why `db-kind` stays what the jar ships and
  only url/username/password move.
  **`mvn verify` is not the gate for datasource config; the binary is.** That rule predates the
  postgres move and survived it.
- `OciConformanceIT` runs the **upstream** OCI distribution-spec suite against the packaged process,
  and it is the only test here that can falsify this repo's own reading of the spec — `RegistryTest`
  and `PackagedProcessIT` drive a client written from that same reading. It is gated on
  `-Doci.conformance-binary=<path>` and **skips** without it, which is load-bearing: `-Dnative`
  flips `skipITs`, so a failing gate would make a native build need Go. See README, "Conformance",
  for how to build the binary and why three capability flags are declared `false`. It currently
  reports 586 run, 0 failures.
  Its first run found **two genuine non-conformances**, both since fixed, and both worth knowing as a
  pattern: each was a deliberate design decision whose consequence was the wrong status code — an
  out-of-order final chunk `PUT` answered 400 instead of the mandated 416, and an invalid digest in a
  manifest reference missed the route and answered 404 instead of 400. Neither was reachable by
  `RegistryTest`, because that suite drives a client written from the same misreading. **The fixes are
  guarded by unit tests, not by this IT** — it is opt-in and needs Go, so anything it proves must also
  be provable by `mvn verify` alone or it is not actually guarded.
- `ArtifactsTestSupport` (in `artifacts/`) and `ArtifactsTestMedia` (in `service/`) are separate on
  purpose: the modules share no test classpath, the same way they do not in the monorepo. `gc/`'s
  `GcFixture` and `artifacts/`'s `SeededStoreFixture` are the same rule and the same seeding, copied
  rather than shared — the alternative is a published test jar and a package-private support class
  widened across a jar boundary, which couples three suites to one classpath to save a seeding
  method. Either copy may grow the cases its own module needs; neither is the other's contract.
- **The explorer's size math is proved in `artifacts/`, its wire behaviour in `service/`, and the
  split is not cosmetic.** `ArtifactExplorerServiceTest` builds two images over five content blobs
  arranged so per-tag, per-image and store-wide unions all give *different* answers over the same
  content, plus one blob no row references — because a bug that sums where it should merge produces a
  number that still looks plausible, and only arithmetic that names the double-counted layer catches
  it. `ArtifactBrowseControllerTest` proves the three kinds of name in this service that contain a
  slash (an OCI image name, a scoped npm package, a docs site like `@userflows/qits-artifacts`)
  resolve in both spellings, encoded and literal, which is a
  property of the path templates and of nothing else. Both must hold; neither implies the other. It
  also pins the union arithmetic one level down from the store summary: a daemon whose two versions
  are the same bytes sizes as one blob, and a site's two versions sharing a font count it once.

### The story catalogue

Twenty-one classes under `service/src/test/java/eu/wohlben/qits/stories/`, plus the two
`authentication` stories in `TokenValidationBootstrapIT` — which are the only ones with a
`@TestProfile` of their own (the mock idp with the rollout gate on). Everything else shares
`PackagedProcessIT.TargetDirState`, so the whole catalogue plus `PackagedProcessIT` costs **two**
launched processes.

**Seven categories, each named after the repository row it addresses** — `npm`, `maven`, `daemons`,
`docs`, `qits` (the OCI registry; the category is the repository name, not the protocol), and the
two CI media planes `ci-screenshots` and `ci-videos`. Every category is the same three-link chain:

| | publish | consume | explore |
|---|---|---|---|
| `npm` | `NpmPublishIT` | `NpmInstallIT` | `NpmExploreIT` |
| `maven` | `MavenDeployIT` | `MavenResolveIT` | `MavenExploreIT` |
| `daemons` | `DaemonPublishIT` | `DaemonDownloadIT` | `DaemonExploreIT` |
| `docs` | `DocsPublishIT` | `DocsReadIT` | `DocsExploreIT` |
| `qits` | `OciPushIT` | `OciPullIT` | `OciExploreIT` |
| `ci-screenshots` | `ScreenshotPublishIT` | `ScreenshotFetchIT` | `ScreenshotExploreIT` |
| `ci-videos` | `VideoPublishIT` | `VideoFetchIT` | `VideoExploreIT` |

- **The chaining rule, and it is asymmetric on purpose.** The consume story carries
  `@UserflowPrecondition(<publish>)`; the explore story carries **the same precondition plus**
  `@UserflowRunsAfter(<consume>)`. So explore is ordered behind consume but gated on **publish
  only**: the browser story asserts what the store now holds, and a missing CLI on the consuming
  side must not take out the one story that would still have run. Reading it the other way round —
  gating explore on consume — is the mistake this shape exists to prevent.
- **The subject travels in `UserflowContext`, never re-declared.** The publish story `put`s its
  name, version and digest under `story.<category>.*`; the consume and explore stories `require`
  them. A story that respells the coordinate is a story that can pass against something the chain
  did not produce.
- **Missing CLI is a SKIP, and the skip must happen before the story starts.** The gate is
  class-level `@EnabledIf("eu.wohlben.qits.stories.support.Cli#<tool>Present")` — `npmPresent`,
  `mvnPresent`, `skopeoPresent`, `curlPresent`, `curlAndTarPresent` — and the first line of every
  `@AfterAll` guards on the same predicate, because JUnit still runs the lifecycle callback of a
  disabled class. **`assumeTrue` inside a story body is wrong and not merely uglier**: the userflows
  extension has already opened a report by the time the body runs, so an aborted test writes a
  FAILED report, and that report is then published in the bundle. A skipped class emits nothing at
  all, which is the honest answer for "this machine has no image client".
- **`stories/support/` is five classes and no more.** `Cli` resolves each program once per JVM
  (explicit `-D` from the pom → Quinoa's managed node dir → `PATH`); `StoryTarget` holds the single
  copy of every wire prefix, in both its URL shape (what a story hands a tool) and its `*_PATH`
  shape (what the access log records, and therefore what a static `@AfterAll` asserts); `StoryBrowser`
  plays the gateway on the browser context; `StoryMedia` synthesises the PNG/webm/tarball fixtures
  and hashes them; `AccessLogSource` is the network tap — see below.

### The network diagram is observed, never narrated

Every story emits a `## Network` section beside its steps, and **no story draws it**. `Interactions`
records notes only — its old `happened(from, to, description)` verb was removed from the framework in
qits-userflows `2026.829.201516` (the pin in the root pom), because an edge that an author typed is
a claim and a diagram must be evidence. Two taps feed it, and which one a story gets follows from
where its traffic actually is:

- **`TokenValidationBootstrapIT`** talks to the launched process through RestAssured, in this JVM, so
  the framework's own `NetworkTaps.restAssured("qits-artifacts")` — installed from that class's
  `@BeforeAll`, which is what bounds the tap to the stories it belongs to — observes every request it
  makes, and `MockIdp`'s recording is registered as a cumulative `NetworkCapture.source` for what the
  service sent *out*. Both ends, both assertable. Its two stories are `@Order`ed, and that is
  load-bearing: a cursor attributes each recorded request to exactly one story, so the **startup**
  JWKS fetch lands in whichever story drains first and that must be the story about it.
  **The tap is the framework's, not this repository's, and no local copy of it may come back.** Four
  service repositories had each hand-copied the same twenty lines as a `StoryNetworkFilter` beside
  their own IT before `2026.829.201516` shipped one; this repository went straight to the shipped
  tap and never committed a copy. Its default skip is any path with a `/q/` **segment**, right for
  `quarkus.http.non-application-root-path=/artifacts/q` — the overload taking a `Predicate<String>`
  is what a service that moved its probes would need instead. Do **not** install it from anywhere but
  a story class's `@BeforeAll`: `RestAssured.filters` is JVM-global across the whole failsafe fork,
  so an installation with no story border around it observes traffic that drains into no story.
- **The 21 story classes** drive a real external tool — `npm`, `mvn`, `skopeo`, `curl` — over a socket
  this JVM is not on. Nothing here can see that traffic, so the observation is the **server's own
  access log**: `PackagedProcessIT.TargetDirState` turns on `quarkus.http.access-log.*` with the
  pattern `%m %U %s` into an absolute directory under `service/target/`, and
  `stories/support/AccessLogSource` parses it back into edges. Five facts about it are load-bearing:
  - **`%U` is `HttpServerRequest.uri()`, so it carries the query string.** That is deliberate: the CI
    media plane's golden lookup *is* a query, and `%R` (path only) would have collapsed the predicate
    that matches and the one that does not into one arrow. Use `%R` only if that stops mattering.
  - **Every access-log key is RUNTIME config**, so it reaches an already-built artifact as a `-D`
    flag. A build-time key there would be silently ignored.
  - **A story calls `AccessLogSource.attribute(actor, kind)` once, before its first request.** Both
    halves are read when the framework *drains*, at story end — so a story gets one initiator and one
    kind for all of its lines, which is exactly right for one tool doing one job and is the mechanism's
    real limitation. They travel in one call because the framework resets the actor at every story
    border and nothing can reset a kind this repository invented: setting only the actor would
    silently inherit the previous story's kind.
  - **`kind` is `package` for every artifact flow** — npm, maven, OCI, daemon binaries, docs bundles —
    whatever transport carries it, and **`http`** for the CI media plane (a JSON API a run POSTs to,
    nothing resolved or pinned by a client) and for the browser stories.
  - **A floor is taken when the first story attributes anything**, so the log's earlier lines —
    `PackagedProcessIT`'s, and any previous build's — belong to no story. That depends on
    `PackagedProcessIT` sorting ahead of every story class, which `UserflowClassOrderer`'s by-name
    tiebreak gives us (`eu.wohlben.qits.PackagedProcessIT` < `eu.wohlben.qits.stories.…`). **A new
    non-story IT under this profile whose name sorts after `stories` would land its traffic in the
    first story's diagram** — that is the one thing to re-check when one is added.
- **`assertEdgeCount` is used where the count is this repository's to promise and nowhere else.** A
  daemon publish, a docs publish and both CI intakes are one request and say so; a docs read is
  exactly four. It is deliberately absent from the npm, maven and OCI stories, and the reason is the
  same each time — how many requests a publish or a resolve *is* belongs to the client. Measured:
  `npm` also fetches the package named `npm` from whatever registry it is pointed at (its
  update-notifier), answering 200 in one invocation and 404 in the next against the same store; a
  `deploy-file` is eleven requests including a checksum sidecar per algorithm; a push may skip a blob
  upload it finds already stored. Those diagrams record what really happened and the assertions pin
  the edges that *are* the story.
- **Where the count is the client's, `assertOnlyEdgesFrom` is what closes the diagram instead**, and
  every story without an `assertEdgeCount` carries one. It says nobody else initiated anything —
  which is still exactly this repository's promise when the *number* of requests is npm's or maven's
  or skopeo's — and the failure it catches is the one no presence check can: a story that forgot
  `AccessLogSource.attribute` leaks the framework's default `a caller` into its diagram while every
  other assertion in the class still passes. Six stories use it that way, and
  `TokenValidationBootstrapIT` uses it over the two actor sets its two stories admit.
- **The browser stories pin no edge BY NAME**, and that is a decision: a `*ExploreIT` fetches the
  SPA's own bundle beside the reads it is about, and those filenames carry a build hash. What they
  do pin is their initiator — `assertOnlyEdgesFrom(CATEGORY, SLUG, List.of(ACTOR))`, so the diagram
  provably reads `an operator -> qits-artifacts` rather than `a caller`. That is the whole network
  claim a browser story can honestly make, and it is the one a screen assertion cannot make for it.
- **A step id survives the migration.** Where a story used to close with `happened(…).as("x-recorded")`
  it now closes with `note(…).as("x-recorded")` — the narrative the wire cannot carry (which package,
  why an open surface is safe, that a filter matching nothing is still an answer) — and the edge is
  asserted in `@AfterAll` instead. `(qits-net trust)` used to ride inside an edge label in
  `TokenValidationBootstrapIT`; a posture is a reason, not an observation, so it is a note now.
- **The fingerprint rule for `Commands` templates: programs and URLs are `{}` ARGUMENTS, never
  spelled into the template.** A story writes `commands.run("{} publish --registry {}", Cli.npm(),
  target.npmRegistry())`. The display line then shows the real program and the real URL while the
  recorded fingerprint keeps `{}`, so a story's definition hash is identical on a workstation, in CI
  and in a container resolving the tool somewhere else — and it survives the launched process taking
  a different random port on every run. Inlining either value silently makes every run a new story.
- **`ci-screenshots` and `ci-videos` document an INTENDED intake — no producer exists yet.** Nothing
  in the platform has ever uploaded a golden screenshot or a run recording: the repository rows are
  seeded, the validating upload path and the newest-per-branch-and-flow query are built, the two GC
  stubs refuse to plan the day a row appears, and the two ends have never met. These six stories are
  that contract written as something that runs, so the capture loop has a shape to build against.
  Keep the caveat attached wherever these categories are summarised — a reader who takes them for a
  live feature will go looking for the producer.
- **npm's two flags are not simplifiable.** `npm` on `PATH` inside a qits workspace container is a
  shim that re-execs the real npm through `env "npm_config_@qits:registry=…"`, so a story trusting
  the environment would publish to the *platform's* registry. npm ranks the command line above the
  environment, so every npm story passes both `--registry=` and `--@qits:registry=` explicitly.

## The store's own first release — what actually went red, observed

The release that BRINGS `/artifacts/sboms` was expected to fail once on the PUT (the step runs
against the store as DEPLOYED, which for exactly one release is the store without the route). On
2026.902.45658 it failed EARLIER and better: the sbom export stage's COPY found no
`service/target/sbom.json`, because cyclonedx-maven-plugin silently skips a module that skips
maven-deploy — and the service module does, it ships as an image. The fix is
`-Dcyclonedx.skipNotDeployed=false` on the builder's mvnw line (the `cyclonedx.`-prefixed
spelling; the bare property is ignored). The bootstrap-ordering quirk remains true for a fresh
platform — one red release run, once, ever — and cannot be inverted without weakening the
fail-the-build rule for every ordinary release. The env/dev sha deploy behind the red run brings
the new store up either way, and the next release publishes clean.

## What not to "fix"

- `AdminWriteGuard` matches on `getUriInfo().getPath()` against a **set** of prefixes —
  `repositories`, `store` and `gc` (`mirror-upstreams` was a fourth and went with its controller) — relative to the JAX-RS base, so it holds
  whatever `quarkus.rest.path` is. It was `artifacts` until the resource `@Path`s dropped that segment (the gateway segment
  carries it now). **A resource added outside those prefixes is unguarded** — extend the set, do not
  assume it is covered. `store` holds only the read-only store summary today and is listed so that
  stays a choice rather than an accident. It guards writes only, by design, and is a no-op while the
  rollout gate `qits.auth.machine.required` is off.
  **It is JAX-RS, so it runs on no raw Vert.x route** — adding a prefix for one would look exactly
  like guarding it and guard nothing. **All four wire surfaces are unguarded on purpose** (qits-net
  trust): `/v2`, `/artifacts/npm`, `/artifacts/maven` and the daemon publish `PUT`. The daemon one
  is the one to expect a proposal about, because it carries the platform's own executables — a
  `DaemonPublishGuard` calling `HttpAuthenticator.attemptAuthentication` under the `MachineAuth`
  keys existed for one commit and was removed as a decision, not a simplification. What replaces
  write auth is what replaces it on the other three: versions are immutable (`409` on republish), so
  an open publish can add a version and never change one, and consumers pin the digest the route
  echoes. **No publish surface is gated piecemeal** — machine auth arrives wholesale with qits-platform-idp,
  for all of them at once. `RegistryOpenPushTest`, `NpmOpenPublishTest` and `DaemonOpenPublishTest`
  each run the gate on and assert their route stays open.
- `service` ships `quarkus.http.limits.max-body-size=1088M`, which is a **global** ceiling — every
  route in the process, not just the upload. Tracked as an open tradeoff in
  `docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`, which now
  lives in **this** repo (its five references used to point at a monorepo path no clone contains).
  Two things about it are counter-intuitive and both are settled empirically by
  `BodyCeilingProbeTest`:
  - **It only gates a declared `Content-Length`.** Quarkus installs the check as a route at order
    −2; with no `Content-Length` it merely stashes the limit under `io.quarkus.max-request-size` for
    a body *reader* to apply. A raw Vert.x route reading `HttpServerRequest` itself is not gated at
    all — which is why `RegistryRoutes` and `MavenRoutes` read through
    `registry/OciRequestBody` (Quarkus' own
    `VertxInputStream`, the one thing on the classpath that honours that key) and never off the
    request. A hand-rolled stream here would silently remove the registry's only wire limit.
  - **It is deliberately *above* `qits.artifacts.oci.max-layer-size`, not equal to it.** Equal values
    make the wire 413 preempt the application 413, and the client gets an empty body and a reset
    connection instead of the OCI error envelope. It must also never drop below 64M, or `ci-videos`
    breaks silently.
- **`quarkus.vertx.worker-pool-size=40` is sized for BLOB DOWNLOADS, not for request concurrency.**
  Serving a blob pulls its chunks on the calling worker thread (`BlobSender`, in the
  qits-registries common jar) where `sendFile` handed a file region to Netty and released the thread
  at once. Every blob route is a `blockingHandler`, so a pull holds a worker for the whole transfer
  and one connection's **pipelined requests serialize** — a named trade-off, not an oversight. The
  alternatives were a second copy of every blob on disk (two sources of truth) or an event-loop pump
  (which would still hold a connection out of a pool of the same size). It moves with
  `quarkus.datasource.artifacts.jdbc.max-size=20` in the artifacts jar, which is what queues a burst.
- **`quarkus.datasource.health.enabled=true` is spelled out, and it departs from the platform's
  "readiness independent of external dependencies" stance on purpose.** PostgreSQL is not an external
  dependency of this service, it is the store — both the rows and the bytes — so a process that
  cannot reach it serves 500 to everything. A ready-but-storeless container is one the deployer's
  health gate would cut over onto, swapping the registry every build and every deploy pulls from for
  one that answers nothing.
- **The docs publish stages into a `ScratchBlob`, and `openRead()` SEALS it.** Sealing flushes the
  final short chunk, so it happens exactly once and after the last write — a short chunk in the
  middle breaks the `seq = position / chunk_size` arithmetic every read depends on. Nothing promotes
  that archive: a bundle is not a stored blob, only its entries are, so `close` discards it and the
  try-with-resources is the only thing that does.
- **`BodyHandler.create()` is not unlimited** — vertx-web defaults it to 10 MiB, and a route that
  takes the default silently 413s everything larger with nothing in the log to say which limit did
  it. Any new `BodyHandler` needs its limit stated. It must *not* be the global ceiling: a `BodyHandler`
  buffers into memory, so a route that uses one wants a much lower number than a route that streams
  to disk. The npm publish `PUT` is the third such route (`qits.artifacts.npm.max-publish-size`), and
  the one where the number is least obvious: a publish document carries its tarball
  **base64-inflated by 4/3** inside JSON, so 32M there is roughly a 24M tarball.
  **The daemon publish `PUT` is the one that would have been caught by this trap and was not**: the
  binary is 43 MB against the 10 MiB default, so a `BodyHandler` there would have 413'd every real
  publish while passing every test small enough to be quick. It streams through `OciRequestBody`
  instead, bounded by `qits.artifacts.daemon.max-binary-size` (256M) — `DaemonBinaryCapTest` lowers
  the knob rather than uploading a quarter of a gigabyte, and asserts the chunked case too, because
  a body with no `Content-Length` is gated by that knob and by nothing else.
- App-level config lives in `service/src/main/resources/application.properties` — the shipped copy —
  and the tests **inherit** it: Quarkus merges main's `application.properties` into the test config
  rather than letting the test one shadow it. So never re-declare an app-level setting
  (`quarkus.rest.path`, `quarkus.http.non-application-root-path`, the openapi settings, ...) in
  `src/test/resources`. A second copy only has to drift once for the suite to be green against a
  value the packaged process never sees. That file is for genuine test-only overrides: the Flyway
  clean-at-start, the disabled dev services, the zero test port and the closed-port pin urls. The
  datasource itself is not in it — it cannot be, because the port is chosen at run time.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml` from `/artifacts/q/openapi`
  (`./mvnw -pl service test -Dtest=OpenApiSchemaExportTest`). **`paths: {}` is correct output here**:
  every artifacts operation carries `@Operation(hidden = true)`, as in the monorepo's own document,
  and the wire routes are Vert.x, so they appear in no OpenAPI document at all. Committed anyway so
  unhiding an operation shows up as a diff.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- **The blob store no longer has a closed `RepositoryType` enum, and adding a type is still not a
  config knob.** Registration is open: a `RepositoryTypeProfile` is an `@ApplicationScoped` CDI bean
  and `RepositoryTypeProfiles` resolves a stored key to whichever bean claims it, so the core
  enumerates nothing — qits-blobstore ships the two CI profiles, qits-registries ships npm, maven
  and OCI, and a service owning a format of its own contributes that one. `key()` is the **stored**
  form written verbatim into `artifact_repository.type` (the screaming-snake spelling the old
  constants had, so existing rows keep their meaning), which is why contributing a profile is a
  schema change as well as a code change: the key has to be in this lineage's
  `ck_artifact_repository_type`. Since V2 that constraint is named, so widening it is a one-liner
  (V3 is that one-liner, twice over) — re-enumerate the whole list from the registered profiles,
  never append.
- **The protocol types' profiles are empty and their `maxBytes()` is `0`, and that is not an
  oversight.** `OCI_IMAGES`, `NPM_PACKAGES`, `MAVEN_PACKAGES`, `DAEMON_BINARIES`, `DOCS` and
  `SBOMS` —
  and the three cache profiles the shared jars carry, which `quarkus.arc.exclude-types` keeps out of
  bean discovery here — never
  flow through
  `BlobService` — their
  bytes arrive on their own wire routes and go straight to `BlobStore` — so there is no media type to
  sniff (a gzipped tar sniffs to nothing and would 400) and no metadata to require. The empty
  media-type set is what makes the zero cap safe: `accepts()` rejects a stray JSON-API upload before
  anything reads the cap. The real caps are `qits.artifacts.oci.max-layer-size`,
  `qits.artifacts.npm.max-publish-size`, `qits.artifacts.maven.max-artifact-size`,
  `qits.artifacts.daemon.max-binary-size` and `qits.artifacts.sbom.max-size`, config knobs
  because they have to move with the wire ceiling.
The five bullets that follow are about the OCI **mirror** path. It is not this service's feature any
more — the type, the upstream admin API and the eviction went to qits-mirror-platform-service — but the code
is on this classpath regardless, in the `qits-registries-oci` jar, and excluding a profile does not
unregister a route's `@Inject`. **V14 took the rows away, which is what makes the path unreachable
rather than merely unwanted** — no `oci-mirror` row resolves and `mirrors.hub()` answers nothing, so
the remap never fires. The code is still there, so a row put back would arm it again. Read the
bullets as constraints on the running binary, not as a description of this
repository's sources.

- **`/v2` has two resolution seams and they are not interchangeable.**
  `OciRegistryService.requireOciRepository` is the **write** one: it demands an `oci-images` row and
  refuses an `oci-mirror` one with `405`. `resolveForPull` is the **read** one: it also accepts a
  mirror namespace, normalises a Hub single-component image to `library/<name>`, and remaps a first
  segment with no repository row at all into the Hub namespace. Routing a write through the read seam
  compiles, passes anything that does not push to a mirror, and quietly lets a client write into a
  cache of somebody else's registry. The precedence inside `resolveForPull` is load-bearing too: an
  existing repository always wins its segment, so `/v2/qits/…` can never be shadowed by the remap.
- **The mirror miss path deliberately does NOT call `requireReferencesExist`.** A pulled index binds
  with none of its children present, because pull order is the reverse of push order — the index
  first, children by digest afterwards, each its own miss. Applying the push path's rule here would
  make every multi-arch pull fail on the first request, and un-lazying it would pay an upstream for
  architectures nobody asked for (a multi-arch pull counts once per architecture *fetched*). A mirror
  index referencing a child with no local row is the **normal** state, and BW's census was made to
  tolerate it on purpose.
- **`recordMirrorTagCheck` must never be called on a failed check.** Serving stale is a decision to
  hand out old bytes *now* while still trying next time; touching `checked_at` on an unreachable
  upstream would turn one outage into a whole TTL of silence, and would look exactly like a working
  cache.
- **The mirror verifies digests and the push path's `finalizeUpload` does not discard on mismatch —
  the two are different on purpose.** A push comes from inside qits-net, so wrong bytes are promoted
  under their own true digest and cost nothing. An upstream is not trusted, so a stream that does not
  hash to the digest requested is deleted from the staging area and refused with `502`. The temp file
  is the caller's to remove: `BlobStore` hands out a staging path and never deletes one.
- **A mirror error is a 502, not a 404 and never a 500, and which one is not cosmetic.** `502` means
  "the upstream could not be asked" and `404` means "the upstream was asked and has no such thing".
  Collapsing them sends whoever is debugging a failed build to the wrong registry — the single most
  expensive wrong answer this service can give, since after the `FROM` rewrite it sits under every
  build.
- **Wire routes must not return DTOs.** The `dto/UploadResult` lesson is worse on a raw Vert.x
  route: behind a JAX-RS `Response` there is at least a provider chain for the build to see the type
  through, but nothing sees a type serialised only inside a Vert.x handler. Responses are built with
  `JsonObject` (or a Jackson `ObjectNode` written out as text) and inbound documents are read as
  `JsonNode`, precisely so no `@RegisterForReflection` is needed and the failure mode is
  unavailable. `registry` and `npm` together add **zero** native-image configuration; if either ever
  seems to need some, something reflective has crept in.
- **Wire handlers carry `@ActivateRequestContext`/`@Transactional` on `OciRegistryService`,
  `NpmRegistryService` and `MavenRegistryService`.** A raw Vert.x route has no CDI request context
  and no transaction. Drop an annotation and those routes fail
  with `ContextNotActiveException` at runtime only. The same fact has a test-side consequence worth
  knowing: inside a `@QuarkusTest` a request context is *already* active, so two of these calls in a
  row share one Hibernate session and a read after a bulk update can see the pre-update row. That is
  a property of the test, not of the service — but it will look like a lost write.
- **npm's `latest` dist-tag only moves forward**, by semver precedence
  (`NpmRegistryService.requireLatestMayMoveTo`, ordering in `NpmSemver`). A bare `npm publish` means
  `--tag latest`, so without this a main build publishing `<release>-main.g<sha>` would move
  `latest` onto a prerelease permanently. Three edges are deliberate: only `latest` is ordered, the
  first assignment is always allowed, and a version that does not parse as semver is **refused**
  rather than passed through. The refusal is thrown inside `publish()`'s transaction, so it takes
  the whole publish with it — the same shape the immutability refusal has, and the reason a publish
  never half-lands. A pipeline publishing prereleases needs `npm publish --tag main`; the message
  says so.
- **`quarkus.http.enable-compression=true` lives in `application.properties` and cannot move to a
  deployment's env.** It is `BUILD_AND_RUN_TIME_FIXED`, so an env var is read, accepted and ignored
  with no warning anywhere — the quietest kind of misconfiguration. And
  `quarkus.http.compress-media-types` must stay **unset**: setting it REPLACES Quarkus' default list
  rather than extending it, so naming one type silently stops compressing the other seven.
- **The explorer's one cache is content-addressed, and its other reading is not cached at all.**
  `OciManifestFootprints` is keyed by `(repository, image, digest)`, so an entry can never become
  wrong and nothing clears it; the *aggregates* built from it are deliberately not cached either.
  `BlobDiskIndex` used to be the third thing here — a 60-second snapshot of a directory walk that
  `BlobStore.promote` had to invalidate. It is one indexed query over `blob` now, so the snapshot
  and its `invalidate()` are gone rather than ported, and the write-signal coupling went with them.
  The name stayed; there is no disk.
- **`NpmUpstream`'s `HttpClient` is an instance field, not a static one** — the constraint the
  Native table lists, and the reason it lists it. It is also this process'
  only outbound TLS, which no test can exercise (no network); a deployment smokes it once by hand.
