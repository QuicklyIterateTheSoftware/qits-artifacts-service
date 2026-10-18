-- The garbage collector's condemnations (bug qits-1172): a sweep no longer deletes an identity on the
-- run that finds it dead, it CONDEMNS it here, and a later run purges it only once the condemnation
-- is older than qits.artifacts.gc.condemn-grace-period (P1D) and that run's keep-set still leaves it
-- dead. Until then the identity is whole and served — its rows are untouched, so its blobs stay
-- referenced — and a run whose keep-set names it again deletes its row here. One wrong pin answer
-- used to be one run away from an unrecoverable deletion; it is now a day away from one, in plain
-- sight on GET /gc/condemned, with a restore door beside it.
--
-- ONE TABLE KEYED BY (type, repository, identity), rather than a condemned_at column on each type's
-- table, for three reasons:
--   1. An identity is not a row in most types. A maven identity is a COORDINATE folded from several
--      maven_artifact paths, an OCI identity is a tag that rides a manifest closure, a docs identity
--      is a site version with a file table under it. A column per table would have to be written on
--      every row of a coordinate and read back as "all of them, or the identity is half condemned".
--   2. Most of those tables are mapped by the qits-registries-* jars, and qits-mirror-service maps
--      the same entities over a schema of its own (V4's reason, again): a mapped column would need a
--      migration there for a value the mirror never holds.
--   3. The collector's state belongs to the collector. The `artifacts` code does not know a
--      collector exists, and with a table of its own it still does not — only the gc module reads
--      or writes this one, through plain JDBC on the shared datasource. The lineage is the one place
--      the schema can live, which is the only reason the DDL sits here.
--
-- type is the STORED key (DAEMON_BINARIES), matching artifact_repository.type; identity is the
-- adapter's own coordinate verbatim (name@version, g:a:v, image:tag, pkgType/name@version), which
-- may carry '@', '/' and ':'. run_id names the sweep receipt that condemned it.
--
-- The foreign key cascades: a repository removed takes its condemnations with it, because a
-- condemnation of an identity in a repository that no longer exists names nothing.
create table gc_condemned (
    type         varchar(64)   not null,
    repository   varchar(255)  not null,
    identity     varchar(1024) not null,
    condemned_at timestamptz   not null,
    run_id       varchar(64)   not null,
    primary key (type, repository, identity)
);

alter table gc_condemned
    add constraint fk_gc_condemned_repository
    foreign key (repository) references artifact_repository (name) on delete cascade;
