# qits-artifacts as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# service/target/qits-artifacts locally in about a minute with no container involved. What this adds
# is the image around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain
# OpenJDK) to get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/artifacts:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the artifacts
# client (service/src/main/webui = the qits-spa-artifacts submodule), and the builder stage
# below packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on
# the mvnw line for why the build cannot happen inside that stage.
#
# --- this image is STATELESS, and does NOT run with an empty environment ---------------------------
# There is no volume, no /data and no blob directory. Everything this service stores — metadata rows
# AND blob bytes — is in one PostgreSQL database, so the container's filesystem holds nothing worth
# keeping and a restart loses only in-flight upload sessions. The OCI distribution spec permits
# exactly that: a blob-upload session is resumable state, not content, and a client whose session is
# gone re-POSTs and re-sends. Nothing else is in flight to lose.
#
# `docker run --rm qits/artifacts:latest` therefore still dies before the HTTP listener, and for a
# better reason than it used to:
#
#   FlywaySqlUnableToConnectToDbException: ... Could not expand value QITS_RESOURCE_DB_URL
#
# The datasource maps the platform's generic resource contract with NO defaults:
#
#   QITS_RESOURCE_DB_URL        jdbc:postgresql://<host>:5432/<database>
#   QITS_RESOURCE_DB_USERNAME
#   QITS_RESOURCE_DB_PASSWORD
#
# qits-platform-deployments injects all three — it creates the role and the database on the tier's
# postgres before the successor container starts, driven by the `resources:` declaration in this
# repository's own deployment spec. Nothing about those names is artifacts-specific, which is the
# point: the application maps them in its own config and the deployer stays framework-agnostic.
#
# THE REFUSAL IS THE FEATURE. An unset variable leaves the expressions unresolvable and the process
# dies naming the missing one, rather than opening some fallback store nobody meant. The image used
# to ship an H2 file url under ${user.home}, which was precisely such a fallback and failed in the
# ugliest available way — UID 1001 has no /etc/passwd entry in ubi-minimal and a native image
# resolves user.home through getpwuid, so the url came out as `jdbc:h2:file:?/.qits/...`. A container
# that came up healthy on an ephemeral copy is the failure that hides; one that refuses to start is
# the failure you see.
#
# READINESS IS TIED TO THAT DATABASE, DELIBERATELY. `quarkus.datasource.health.enabled=true` is
# spelled out in service/src/main/resources/application.properties, which departs from the platform's
# "readiness independent of external dependencies" stance — see the comment there. Postgres is not an
# external dependency of this service, it is the store, and a container that reported READY without it
# is one the deployer's health gate would cut over onto: the registry every build pulls from, swapped
# for one that answers 500 to everything.
#
# WHAT ABOUT THE ONE-TIME COPY. Moving a live deployment's H2 rows and blob files into PostgreSQL is
# an OPS TOOL run once against both stores, not this image's boot path: a boot that migrated data
# would have to be given two databases, would run on every restart, and would put a data conversion
# behind a health check. This image only ever meets a database that is empty or that this lineage
# built.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `service` depends on `artifacts`, a 1.0.0-SNAPSHOT this repo
# never publishes (no distributionManagement, no <repositories>), so a builder container with an
# empty ~/.m2 has nowhere else to get it — the sources have to be in the context. Copying module by
# module would mean editing this file every time one is added, and forgetting to would fail the
# build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap — without it this line drags in the ~250 MB of target/
# already sitting here.
COPY . .

#
# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml — only that
# module is an application). Cap native-image RAM so the build is deterministic on small machines.
#
# Tests are skipped because they are not skippable HERE: GitHostTest shells `git init`/`git clone
# --bare` to seed its origins, and the `native` profile flips skipITs so PackagedProcessIT would
# launch the freshly built binary as a second process inside this stage. `./mvnw verify -Dnative` on
# a clone is the gate for both.
#
# `-pl service -am` builds the app and its dependency closure, not the whole reactor. That is not a
# module list — it names the one module this image ships, which the COPY below already names anyway,
# so nothing is gained by leaving it out. What it buys: this repo will publish libraries as well as
# an image, and a reactor then holds modules that exist for consumers rather than for the binary. On
# a whole-reactor build a compile error in one of those would block deploying this service, and build
# time would grow with the published surface instead of with the app. The image build should depend
# on exactly what the image contains.
# --- the Quinoa flags, and why this stage no longer builds the client -----------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a developer machine already has node and should keep using its
# own, and only this stage has to be told, so that no build silently downloads a toolchain. 22.22.0
# is the PLATFORM pin, the node every other qits service's builder stage installs and the version a
# developer's host carries. Note it is deliberately NOT the step container's node: node-docker-base
# is node:24-alpine, and the bundle this image consumes was built there. The two do not have to
# agree, because this stage does not build the bundle — the commands below are neutered to
# `--version`, so the only thing node does here is exist and prove its package manager runs.
#
# The three command overrides are the load-bearing part, and the reason this file deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md in the qits monorepo, which has the
# builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-artifacts, and it depends on @qits/ui-components.
#   That package exists ONLY on the platform's own npm registry — which is THIS SERVICE, and that
#   changes nothing about the problem: a RUN step in a docker build reaches the public internet
#   (Maven Central, npmjs.org, nodejs.org) and can reach the platform registry by NO address, not
#   the qits-net alias, not registry.dev.localhost:8080, not host.docker.internal. Being the
#   registry does not give the build a route to it; the running container is on qits-net and this
#   stage is not. So the install cannot happen here — it hangs on the registry.dev.localhost:8080
#   the submodule's own .npmrc names and then dies on npm's `Exit handler never called!`, which
#   says nothing about why — and an install that "worked" here would have resolved the @qits scope
#   against npmjs.org, where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry answers, and arrives in
# the build context as service/src/main/webui/dist/ (see .dockerignore). `--version` is the null
# command: it proves the package manager is really there and does nothing else.
#
# THIS NO LONGER FAILS QUIETLY, and that is a change in kind. What this comment used to say was that
# a context whose webui submodule is NOT initialised leaves Quinoa with no package.json, so it
# disables itself with a warning and this stage stays GREEN while shipping no client — a service
# that boots, serves /artifacts/ as a 404, and looks deployed. The `test -f` below is the end of
# that: a missing bundle, from a forgotten submodule init or a forgotten `npm run build` in the
# step, is now a red build, stopped before the multi-minute native compile rather than discovered in
# production.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-artifacts/browser`, which names the source
# and explains nothing. A builder stage that *creates* dist itself is immune by accident; ours
# arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once the registry stops answering anonymously -------------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is the whole reason they are not build
# args: this image is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# FILE FORM, NOT `env=`. The mounts are bare (`/run/secrets/<id>`, read into the command's own
# environment by the `cat`s below) rather than the `env=` mount form they used to be. `env=` needs a
# dockerfile frontend that supports it, and under the platform's own buildkitd the frontend is the
# BUILDER's to pick, not this file's; the bare file mount is served identically by every frontend
# version. See qits-buildkit-plan.md in the wrapper.
#
# Absent secrets leave both variables EMPTY — the `|| true` is every hand build's state — which
# `.qits-maven-settings.xml` reads exactly as it read unset: Maven sends credentials only when a
# repository answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    d=service/src/main/webui/dist/qits-spa-artifacts/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on the first release (2026.902.45658):
# the service module skips maven-deploy — it ships as this image, not as a jar — and the plugin
# SKIPS deploy-skipping modules by default, with a green build and one INFO line. The sbom stage's
# COPY then fails on a file nothing wrote. The property spelling matters too: the bare
# -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the store the
# image itself serves. A stage rather than a layer of the runtime image, and BEFORE the runtime
# stage on purpose: the last stage is what a plain `docker build` builds, and that must stay the
# image. The release step reaches this one with `--opt target=sbom --output type=local` using the
# SAME build-args and secrets as the image build, so every layer above is a cache hit and the export
# costs seconds. `makeBom` rides on the mvnw invocation above rather than a second one, for the same
# cache reason — one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-artifacts` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`. That name is spelled a third time in service/pom.xml as
# failsafe's `native.image.path`; all three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-artifacts /work/qits-artifacts
RUN chmod +x /work/qits-artifacts

# NO VOLUME, NO /data, NO OWNERSHIP FIXUP. There used to be a mount point here, created and chowned
# so a fresh named volume would inherit the ownership; the blob directory it existed for is gone, so
# the directory, its chown and the deployment's -v are all gone with it. A `/data` this image still
# made would be an empty directory an operator would eventually mount something into.
#
# Nothing about the store is named here either: the QITS_RESOURCE_DB_* triple belongs to the
# deployment, and this image refuses to start until one supplies it.

# One port for all of it — /artifacts/api/** (JAX-RS), /artifacts/q/** (openapi, swagger-ui) and the
# five raw Vert.x route stacks are one process, not several listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-artifacts", "-Dquarkus.http.host=0.0.0.0"]
