-- One opaque content-hash value per published maven GAV / npm version (epic qits-620). Computed and
-- sent by the qits CLI; this service validates the shape only and never recomputes it. A version
-- with no row simply has none, which the CLI reads as "changed". Immutable: first write wins.
--
-- A table of its own rather than a column on npm_version / maven_artifact: those entities ship in
-- qits-registries-javalib, and qits-mirror-service maps the same jars over a schema of its own, so a
-- new mapped column would need a migration there too for a value the mirror never holds. The
-- library owns the port (ContentHashLedger); this service owns the table.
--
-- package_name is varchar(512) for sbom_document's reason: a maven coordinate is groupId AND
-- artifactId in one value. No foreign key to the version row, because a maven "version" is a set of
-- maven_artifact paths rather than one row — the GC adapters delete this row together with the
-- version they collect instead.
create table content_hash (
    repository   varchar(255) not null,
    ecosystem    varchar(8)   not null,
    package_name varchar(512) not null,
    version      varchar(128) not null,
    value        varchar(160) not null,
    created_at   timestamptz  not null,
    primary key (repository, ecosystem, package_name, version)
);

alter table content_hash
    add constraint ck_content_hash_ecosystem check (ecosystem in ('maven','npm'));

alter table content_hash
    add constraint fk_content_hash_repository
    foreign key (repository) references artifact_repository (name);
