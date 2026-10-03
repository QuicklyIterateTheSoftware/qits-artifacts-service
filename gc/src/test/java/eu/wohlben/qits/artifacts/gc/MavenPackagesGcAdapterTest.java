package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The own engine over maven's facts, and the things this type has that no other own type does: a
 * <b>coordinate made of several rows</b>, a derived document that redirects a resolver, and a
 * <b>closure</b> that keeps whatever a kept coordinate needs to resolve.
 *
 * <p><b>Releases are collected again since 2026-10-03 (qits-739)</b>, and this suite is where that
 * decision is held to its terms. On 2026-09-05 the access rule deleted 67 published coordinates in
 * one night and broke every gating build on the platform, and the three reasons it gave — transitive
 * dependencies nobody pins, parent poms and BOMs no SBOM lists, consumers that have not bumped — are
 * each a case here: {@code aVersionNamedOnlyInAKeptCoordinatesSbomIsKept…}, {@code
 * theParentPomOfAKeptCoordinateIsKept…} and {@code anImportScopedBom…}, and {@code
 * aVersionSomeRepositorysPomStillReferences…}. {@code anUnreadablePomOfAReachedCoordinate…} is the
 * fail-closed rule: a closure this adapter could not finish is never the keep-set a deletion runs
 * against.
 *
 * <p>The two cases that carry the type's structural promises are unchanged — {@code
 * theNewestTimestampedSnapshotSetIsAlwaysKept…}, because {@code maven-metadata.xml} is computed from
 * the surviving rows at read time, and {@code aCoordinateIsRemovedWholeOrNotAtAll…}, the
 * version-atomicity the identity model claims.
 *
 * <p><b>Every coordinate in these fixtures carries a real pom</b>, because the closure reads one for
 * every coordinate it reaches and a coordinate without one is a reason to keep everything. {@link
 * #row} writes XML for every {@code .pom} path for exactly that reason. The window is {@code P0D}:
 * a coordinate lives because a pin, the closure or a belt names it, and for no other reason.
 */
@QuarkusTest
class MavenPackagesGcAdapterTest extends GcFixture {

  private static final String GROUP_ID = "eu.wohlben.qits";
  private static final String ARTIFACT_ID = "qits-eventstream";
  private static final String COORDINATE = GROUP_ID + ":" + ARTIFACT_ID + ":";
  private static final String SBOM_REPO = "sboms";

  @Inject MavenPackagesGcStrategy strategy;
  @Inject MavenPackagesGcAdapter adapter;
  @Inject MavenRegistryCollection collection;
  @Inject GcPlanner planner;
  @Inject eu.wohlben.qits.artifacts.control.JpaContentHashLedger ledger;

  @Test
  void anOldReleaseNothingReferencesIsCondemnedAndTheNewestTwoAreKeptByTheBelt() throws Exception {
    // The rule itself. Six releases of one artifact, every file a year cold, nothing pinned and no
    // document or pom naming any of them: the newest two are the belt, and the four below it are
    // condemned — and applied, every file of each goes.
    maven();
    for (String version : List.of("1.0.0", "1.1.0", "2.0.0", "2.1.0", "3.0.0", "3.1.0")) {
      release(version, "jar", 11 + Math.floorMod(version.hashCode(), 7), daysAgo(400));
      release(version, "pom", 0, daysAgo(400));
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(
        List.of(COORDINATE + "1.0.0", COORDINATE + "1.1.0", COORDINATE + "2.0.0", COORDINATE + "2.1.0"),
        identities(plan.dead()));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "3.0.0"),
        "a belt release nothing else reaches is kept by the belt, and says so");
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "3.1.0"));
    assertEquals(4, applied.deleted().size());
    mavenArtifacts.getEntityManager().clear();
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, releasePath("1.0.0", "jar")).isEmpty());
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, releasePath("1.0.0", "pom")).isEmpty());
    assertTrue(mavenArtifacts.findOne(MAVEN_REPO, releasePath("3.0.0", "pom")).isPresent());
  }

  @Test
  void theBeltCountsByMavensOwnVersionOrderRatherThanLexically() throws Exception {
    // 1.0.10 is above 1.0.9, which a string compare gets backwards — and with releases collectable
    // again, the comparator decides which version a belt slot protects.
    maven();
    for (String version : List.of("1.0.10", "1.0.9", "1.0.2")) {
      release(version, "pom", 0, daysAgo(400));
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.2"), identities(plan.dead()));
    assertEquals(
        List.of(COORDINATE + "1.0.2", COORDINATE + "1.0.9", COORDINATE + "1.0.10"),
        adapter.enumerate().stream().sorted(adapter.byAge()).map(GcCandidate::identity).toList(),
        "oldest release first, by maven's own version order rather than lexically");
  }

  @Test
  void aVersionSomeRepositorysPomStillReferencesIsKeptUnderThePinItIsNamedBy() throws Exception {
    // The unbumped consumer: some repository's main still builds against 1.0.0, three releases
    // behind. The pin joins on the identity UNCHANGED — "g:a:v" is what a pom writes and what this
    // adapter folds its rows into — and it is asked first, so the receipt names the repository
    // rather than any weaker reason.
    maven();
    for (String version : List.of("1.0.0", "1.1.0", "2.0.0", "3.0.0")) {
      release(version, "jar", 101 + version.length(), daysAgo(400));
      release(version, "pom", 0, daysAgo(400));
    }

    GcStrategy.Plan referenced = strategy.plan(census.take(), referencing(COORDINATE + "1.0.0"));

    assertEquals(GcPins.BY_MANIFEST, ruleFor(referenced.kept(), COORDINATE + "1.0.0"));
    assertEquals(List.of(COORDINATE + "1.1.0"), identities(referenced.dead()));

    // And the pin reaches through to a SNAPSHOT too: a superseded timestamped set some manifest
    // names outlives the window that takes its unpinned neighbour.
    snapshot("1.2.0", "20260601.101010", 1, "pom", 105, daysAgo(400));
    snapshot("1.2.0", "20260701.202020", 2, "pom", 106, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "pom", 107, daysAgo(400));
    String pinnedSnapshot = COORDINATE + "1.2.0-20260601.101010-1";

    GcStrategy.Plan pinned = strategy.plan(census.take(), referencing(pinnedSnapshot));
    assertEquals(GcPins.BY_MANIFEST, ruleFor(pinned.kept(), pinnedSnapshot));
    assertTrue(
        identities(pinned.dead()).contains(COORDINATE + "1.2.0-20260701.202020-2"),
        "and the unpinned superseded set beside it still goes: " + pinned.dead());
  }

  @Test
  void aVersionNamedOnlyInAKeptCoordinatesSbomIsKeptAndItsPomDependenciesAreNotRead()
      throws Exception {
    // The transitive answer. qits-consumer's only release is a belt release, and its SBOM names
    // qits-eventstream:1.0.0 — two releases below the belt, named by nothing else on the platform.
    // The pom beside that SBOM names 0.9.0, and is NOT followed: a coordinate with a document is
    // read from the document, which is what its build actually resolved.
    maven();
    sbomRepository();
    for (String version : List.of("0.9.0", "1.0.0", "2.0.0", "3.0.0")) {
      release(version, "pom", 0, daysAgo(400));
    }
    deploy("qits-consumer", "1.0.0", dependency(ARTIFACT_ID, "0.9.0", null));
    sbom("qits-consumer", "1.0.0", purl(ARTIFACT_ID, "1.0.0"));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenKeepClosure.NAMED_BY_SBOM + GROUP_ID + ":qits-consumer:1.0.0",
        ruleFor(plan.kept(), COORDINATE + "1.0.0"));
    assertEquals(List.of(COORDINATE + "0.9.0"), identities(plan.dead()));
  }

  @Test
  void theParentPomOfAKeptCoordinateIsKeptEvenBesideAnSbom() throws Exception {
    // The case live on the platform: eu.wohlben.qits:qits-githost-events:2026.910.103045 is pinned
    // by a manifest and names qits-githost:2026.910.103045 as its <parent>. No SBOM lists a parent,
    // so without this edge the parent — three releases down — would go, and every build resolving
    // qits-githost-events would fail on the pom it inherits from.
    maven();
    sbomRepository();
    String events = GROUP_ID + ":qits-githost-events:2026.910.103045";
    deploy("qits-githost-events", "2026.910.103045", parent("qits-githost", "2026.910.103045"));
    sbom("qits-githost-events", "2026.910.103045");
    for (String version : List.of("2026.901.1", "2026.910.103045", "2026.920.1", "2026.930.1")) {
      deploy("qits-githost", version, "");
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), referencing(events));

    assertEquals(GcPins.BY_MANIFEST, ruleFor(plan.kept(), events));
    assertEquals(
        MavenKeepClosure.PARENT_OF + events,
        ruleFor(plan.kept(), GROUP_ID + ":qits-githost:2026.910.103045"));
    assertEquals(List.of(GROUP_ID + ":qits-githost:2026.901.1"), identities(plan.dead()));
  }

  @Test
  void anImportScopedBomOfAKeptCoordinateIsKept() throws Exception {
    maven();
    deploy(
        "qits-app",
        "1.0.0",
        "<dependencyManagement><dependencies>"
            + dependency("qits-bom", "1.0.0", "import")
            + "</dependencies></dependencyManagement>");
    for (String version : List.of("0.9.0", "1.0.0", "2.0.0", "3.0.0")) {
      deploy("qits-bom", version, "");
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenKeepClosure.IMPORTED_BY + GROUP_ID + ":qits-app:1.0.0",
        ruleFor(plan.kept(), GROUP_ID + ":qits-bom:1.0.0"));
    assertEquals(List.of(GROUP_ID + ":qits-bom:0.9.0"), identities(plan.dead()));
  }

  @Test
  void aCoordinateWithNoSbomKeepsItsPomDependenciesButNotItsTestScopedOnes() throws Exception {
    // No document is never a reason to delete what a coordinate uses: its flattened pom's
    // dependencies stand in. A test dependency is not something a consumer resolves, so that edge
    // keeps nothing.
    maven();
    deploy(
        "qits-app",
        "1.0.0",
        "<dependencies>"
            + dependency("qits-lib", "1.0.0", null)
            + dependency("qits-testkit", "1.0.0", "test")
            + "</dependencies>");
    for (String version : List.of("1.0.0", "2.0.0", "3.0.0")) {
      deploy("qits-lib", version, "");
      deploy("qits-testkit", version, "");
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenKeepClosure.DEPENDENCY_OF
            + GROUP_ID
            + ":qits-app:1.0.0"
            + MavenKeepClosure.WHICH_HAS_NO_SBOM,
        ruleFor(plan.kept(), GROUP_ID + ":qits-lib:1.0.0"));
    assertEquals(List.of(GROUP_ID + ":qits-testkit:1.0.0"), identities(plan.dead()));
  }

  @Test
  void theClosureRunsToAFixpointAcrossTwoHops() throws Exception {
    // A pinned app's SBOM names lib:1.0.0; lib has no SBOM, so its pom is read, and names
    // deep:1.0.0. Neither is pinned and both are below their belts; both stay, each naming the
    // coordinate that reached it.
    maven();
    sbomRepository();
    String app = GROUP_ID + ":qits-app:1.0.0";
    deploy("qits-app", "1.0.0", "");
    sbom("qits-app", "1.0.0", purl("qits-lib", "1.0.0"));
    for (String version : List.of("1.0.0", "2.0.0", "3.0.0")) {
      deploy(
          "qits-lib",
          version,
          "<dependencies>" + dependency("qits-deep", version, null) + "</dependencies>");
    }
    for (String version : List.of("0.5.0", "1.0.0", "2.0.0", "3.0.0")) {
      deploy("qits-deep", version, "");
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), referencing(app));

    assertEquals(
        MavenKeepClosure.NAMED_BY_SBOM + app, ruleFor(plan.kept(), GROUP_ID + ":qits-lib:1.0.0"));
    assertEquals(
        MavenKeepClosure.DEPENDENCY_OF
            + GROUP_ID
            + ":qits-lib:1.0.0"
            + MavenKeepClosure.WHICH_HAS_NO_SBOM,
        ruleFor(plan.kept(), GROUP_ID + ":qits-deep:1.0.0"));
    assertEquals(List.of(GROUP_ID + ":qits-deep:0.5.0"), identities(plan.dead()));
  }

  @Test
  void anUnreadablePomOfAReachedCoordinateCondemnsNothingMavenThatRun() throws Exception {
    // Fail closed. The same store as the belt case, one superseded snapshot set beside it — and the
    // pom of a belt release is not XML. The closure cannot say what that coordinate's parent is, so
    // it cannot say what is safe to delete: nothing is condemned, releases and the snapshot alike,
    // and every line names the coordinate and the reason.
    maven();
    for (String version : List.of("1.0.0", "2.0.0", "3.0.0")) {
      release(version, "pom", 0, daysAgo(400));
    }
    rowBytes(
        releasePath("3.1.0", "pom"), "not a pom".getBytes(StandardCharsets.UTF_8), daysAgo(400));
    snapshot("4.0.0", "20260601.101010", 1, "pom", 0, daysAgo(400));
    snapshot("4.0.0", "20260802.123456", 2, "pom", 0, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead(), "a partial closure is never a deletion");
    String rule = ruleFor(plan.kept(), COORDINATE + "1.0.0");
    assertTrue(rule.startsWith("maven collects nothing this run"), rule);
    assertTrue(rule.contains(COORDINATE + "3.1.0"), "names the coordinate: " + rule);
    assertTrue(rule.contains("does not parse"), "and the reason: " + rule);
    assertEquals(rule, ruleFor(plan.kept(), COORDINATE + "4.0.0-20260601.101010-1"));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "4.0.0-20260802.123456-2"));
  }

  @Test
  void aRowWhosePathThisLayoutCannotReadIsNeverCollected() throws Exception {
    // A path that is not <group>/<artifact>/<version>/<file> is its own identity under its own path
    // spelling, which makes it the one identity here that is a FILE rather than a version. This
    // adapter cannot say which coordinate it belongs to, so it cannot promise that deleting it is
    // not deleting half of something — and half a version is the failure this type exists to
    // prevent. The wire refuses an unparseable path at the door, so these are rows that predate a
    // rule; a collector that cleaned them up would be guessing about the one thing it cannot read.
    maven();
    row("eu/wohlben/maven-metadata.xml", 51, daysAgo(400), null);
    release("1.0.0", "jar", 52, daysAgo(400));
    release("1.0.0", "pom", 53, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        MavenPackagesGcAdapter.KEPT_UNREADABLE_PATH,
        ruleFor(plan.kept(), "eu/wohlben/maven-metadata.xml"));
  }

  @Test
  void theNewestTimestampedSnapshotSetIsAlwaysKeptSoAResolverNeverResolvesToADeletedFile()
      throws Exception {
    // The property this type must never break. Every timestamped set here is a year cold, so the
    // window alone would take all three — and the newest one is the coordinate the derived
    // version-level maven-metadata.xml redirects 1.0.1-SNAPSHOT to. It is kept under its own rule,
    // and after the plan is applied every file of it is still a row.
    maven();
    snapshot("1.0.1", "20260601.101010", 1, "jar", 41, daysAgo(400));
    snapshot("1.0.1", "20260601.101010", 1, "pom", 42, daysAgo(400));
    snapshot("1.0.1", "20260701.202020", 2, "jar", 43, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "jar", 44, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "pom", 45, daysAgo(400));
    String newest = COORDINATE + "1.0.1-20260802.123456-3";

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(
        List.of(COORDINATE + "1.0.1-20260601.101010-1", COORDINATE + "1.0.1-20260701.202020-2"),
        identities(plan.dead()));
    assertEquals(List.of(newest), identities(plan.kept()));
    assertEquals(MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT, plan.kept().get(0).rule());
    assertEquals(2, applied.deleted().size());

    mavenArtifacts.getEntityManager().clear();
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260802.123456", 3, "jar"))
            .isPresent(),
        "the file the metadata resolves to");
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260802.123456", 3, "pom"))
            .isPresent(),
        "and its pom, because a coordinate is removed whole or not at all");
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "jar"))
            .isEmpty());
    assertTrue(
        mavenArtifacts.findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "pom"))
            .isEmpty());
  }

  @Test
  void twoDeploysInsideOneSecondAreRankedByBuildNumberAsANumber() throws Exception {
    // The one case where a string compare would point the metadata at the earlier deploy: build 10
    // sorts before build 9 lexically. The timestamp dominates in every other case, which is exactly
    // why this one has to be written down.
    maven();
    snapshot("1.0.1", "20260802.123456", 9, "jar", 51, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 10, "jar", 52, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 10, "pom", 53, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(COORDINATE + "1.0.1-20260802.123456-9"), identities(plan.dead()));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.1-20260802.123456-10"));
  }

  @Test
  void aLiteralSnapshotSetIsTheNewestDeployableSetOnlyWhenTheLineHasNoTimestampedDeploys()
      throws Exception {
    // uniqueVersion=false: the client asks for a-1.0.2-SNAPSHOT.jar by name, with no metadata to
    // redirect it, so on a line with nothing else that set is what a resolver would break without.
    // Beside timestamped deploys it is an ordinary candidate and ages out like one.
    maven();
    literalSnapshot("1.0.2", "jar", 61, daysAgo(400));
    literalSnapshot("1.0.2", "pom", 64, daysAgo(400));
    literalSnapshot("1.0.3", "jar", 62, daysAgo(400));
    snapshot("1.0.3", "20260802.123456", 1, "jar", 63, daysAgo(400));
    snapshot("1.0.3", "20260802.123456", 1, "pom", 65, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.2-SNAPSHOT"),
        "the only deployable set of its line");
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.0.3-20260802.123456-1"));
    assertEquals(List.of(COORDINATE + "1.0.3-SNAPSHOT"), identities(plan.dead()));
  }

  @Test
  void aSnapshotLineAndItsArtifactsReleasesNeverCollideInTheOneGroupField() throws Exception {
    // Two questions wearing one group field, and this is the case that would catch them colliding:
    // snapshot builds must not take a release's belt slot, and releases of this artifact must not
    // make the snapshot line's newest set eligible — exactly what a shared group key would do.
    maven();
    release("1.0.0", "jar", 71, daysAgo(400));
    release("1.0.0", "pom", 76, daysAgo(400));
    release("1.1.0", "jar", 72, daysAgo(390));
    release("1.1.0", "pom", 77, daysAgo(390));
    snapshot("1.2.0", "20260601.101010", 1, "jar", 73, daysAgo(400));
    snapshot("1.2.0", "20260701.202020", 2, "jar", 74, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "jar", 75, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "pom", 78, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(COORDINATE + "1.2.0-20260601.101010-1", COORDINATE + "1.2.0-20260701.202020-2"),
        identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.1.0"));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.2.0-20260802.123456-3"),
        "the snapshot line kept its own newest set under its own rule, beside two releases");
  }

  @Test
  void oneFileInsideTheGraceWindowWithholdsTheWholeCoordinateEveryRowIntact() throws Exception {
    // The strand hazard, and this type's own version of it: deleting the mature rows of a version
    // and leaving the young one would produce exactly the half-version the identity model exists to
    // prevent, on top of stranding the young blob as row-less and therefore untouchable forever.
    //
    // Written over a superseded snapshot set, the coordinate this type has condemned throughout.
    maven();
    String youngPom = snapshot("1.0.1", "20260601.101010", 1, "pom", 82, daysAgo(400));
    snapshot("1.0.1", "20260601.101010", 1, "jar", 81, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "jar", 83, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "pom", 84, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(youngPom));

    assertEquals(List.of(), applied.deleted());
    assertEquals(
        List.of(COORDINATE + "1.0.1-20260601.101010-1"),
        identities(applied.withheldByGraceWindow()));
    mavenArtifacts.getEntityManager().clear();
    assertTrue(
        mavenArtifacts
            .findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "jar"))
            .isPresent());
    assertTrue(
        mavenArtifacts
            .findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "pom"))
            .isPresent());
  }

  @Test
  void aCoordinateIsRemovedWholeOrNotAtAllWhenOneOfItsFilesCannotBeCollected() throws Exception {
    // The half-sweep, reproduced. MavenRegistryService.collect is @Transactional PER FILE, so the
    // loop that removes a coordinate used to commit path by path: a throw on the second file — a
    // concurrent deploy or collection moving the store between planning and applying is the
    // documented way it happens — left the first file deleted and the rest alive. The paths sort
    // lexically, .jar before .pom, so what that leaves behind is precisely the shape that breaks
    // every resolve: a version whose pom answers 200 and whose jar answers 404.
    //
    // The failure is injected at the collection door rather than raced, because the race is not
    // reproducible and the property under test is not the race — it is that ONE transaction spans
    // the coordinate, so a failure anywhere in it undoes the whole thing.
    maven();
    snapshot("1.0.1", "20260601.101010", 1, "jar", 91, daysAgo(400));
    snapshot("1.0.1", "20260601.101010", 1, "pom", 92, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "jar", 93, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "pom", 94, daysAgo(400));

    MavenRegistryCollection real = ClientProxy.unwrap(collection);
    GcStrategy.Applied applied;
    try {
      QuarkusMock.installMockForInstance(new FailsOnThePom(real), collection);
      GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
      assertEquals(
          List.of(COORDINATE + "1.0.1-20260601.101010-1"),
          identities(plan.dead()),
          "the case only says anything if this set is condemned in the first place");
      applied = strategy.apply(plan, blobId -> false);
    } finally {
      // Installed mid-method, so it outlives the method unless it is put back by hand — and a
      // MavenRegistryCollection that refuses every pom would fail the rest of this suite silently.
      QuarkusMock.installMockForInstance(real, collection);
    }

    assertEquals(List.of(), applied.deleted(), "nothing was removed, so nothing may be reported as");
    assertEquals(1, applied.errors().size(), applied.errors().toString());
    assertTrue(
        applied.errors().get(0).contains("left whole"),
        "the receipt has to say the coordinate survived: " + applied.errors());

    mavenArtifacts.getEntityManager().clear();
    assertTrue(
        mavenArtifacts
            .findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "jar"))
            .isPresent(),
        "the jar goes first in path order — under the old per-file commit it was already gone here");
    assertTrue(
        mavenArtifacts
            .findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260601.101010", 1, "pom"))
            .isPresent());
    assertTrue(
        mavenArtifacts
            .findOne(MAVEN_REPO, snapshotPath("1.0.1", "20260802.123456", 3, "jar"))
            .isPresent(),
        "and the set the metadata resolves to was never a candidate");
  }

  /** A collection door that removes jars and refuses poms — the mid-coordinate failure, on demand. */
  private static final class FailsOnThePom extends MavenRegistryCollection {

    private final MavenRegistryCollection delegate;

    private FailsOnThePom(MavenRegistryCollection delegate) {
      this.delegate = delegate;
    }

    @Override
    public void collect(String repository, String path) {
      if (path.endsWith(".pom")) {
        throw new IllegalStateException(
            "no such maven path " + path + " to collect — the store moved since the plan was"
                + " computed");
      }
      delegate.collect(repository, path);
    }
  }

  @Test
  void aStoreWithNoMavenRepositoryIsAnEmptyPlanRatherThanAFailure() throws Exception {
    // The shipped state of a platform that has deployed no library yet. Nothing here reaches outside
    // this service, so the only thing that can refuse this type is the run's pin aggregate.
    seed();

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(MavenPackagesProfile.KEY, strategy.type());
    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store: one release, a jar and its pom, deployed moments ago — and now ONE
    // identity rather than two, which is the visible half of the change this workstream made. The
    // set handed back is exactly the census's own maven live set, the assertion every claimed type
    // carries for the case where nothing dies.
    seedMaven();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(COORDINATE + "1.0.0"), identities(plan.kept()));
    assertTrue(plan.kept().stream().allMatch(kept -> MAVEN_REPO.equals(kept.repository())));
    assertEquals(taken.live(MavenPackagesProfile.KEY).keySet(), plan.blobsRetained());
    assertEquals(2, plan.blobsRetained().size(), "the jar and the pom");
    assertEquals(List.of(), planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  @Test
  void aCollectedCoordinateTakesItsContentHashRowWithItAndAKeptOneKeepsIts() throws Exception {
    // epic qits-620: the content_hash row is metadata about a version this store serves, so it goes
    // in the same transaction as the coordinate's files. The ledger only ever records a release pom,
    // so the row is written here by hand for a superseded snapshot set. What is under test is the
    // hop from the condemned identity to the ledger's key, not when the ledger writes.
    maven();
    snapshot("1.0.1", "20260601.101010", 1, "pom", 141, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "pom", 142, daysAgo(400));
    release("1.0.0", "pom", 143, daysAgo(400));
    String name = GROUP_ID + ":" + ARTIFACT_ID;
    ledger.record("maven", MAVEN_REPO, name, "1.0.1-20260601.101010-1", "v1:sha256:" + "a".repeat(64));
    ledger.record("maven", MAVEN_REPO, name, "1.0.1-20260802.123456-3", "v1:sha256:" + "b".repeat(64));
    ledger.record("maven", MAVEN_REPO, name, "1.0.0", "v1:sha256:" + "c".repeat(64));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(COORDINATE + "1.0.1-20260601.101010-1"), identities(applied.deleted()));
    assertTrue(ledger.find("maven", MAVEN_REPO, name, "1.0.1-20260601.101010-1").isEmpty());
    assertTrue(ledger.find("maven", MAVEN_REPO, name, "1.0.1-20260802.123456-3").isPresent());
    assertTrue(ledger.find("maven", MAVEN_REPO, name, "1.0.0").isPresent());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void maven() {
    repositoryService.ensure(MAVEN_REPO, MavenPackagesProfile.KEY);
  }

  /** The aggregate a run would have read, with these maven coordinates named by a pom on main. */
  private static GcPins referencing(String... coordinates) {
    return new GcPins(
        java.util.Map.of(),
        "",
        Set.of(),
        Set.of(),
        Set.of(coordinates),
        Set.of(),
        Set.of(),
        Set.of(),
        List.of());
  }

  private static String releasePath(String version, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + version
        + "/"
        + ARTIFACT_ID
        + "-"
        + version
        + "."
        + extension;
  }

  private static String snapshotPath(
      String baseVersion, String timestamp, int buildNumber, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + baseVersion
        + "-SNAPSHOT/"
        + ARTIFACT_ID
        + "-"
        + baseVersion
        + "-"
        + timestamp
        + "-"
        + buildNumber
        + "."
        + extension;
  }

  private static String literalSnapshotPath(String baseVersion, String extension) {
    return "eu/wohlben/qits/"
        + ARTIFACT_ID
        + "/"
        + baseVersion
        + "-SNAPSHOT/"
        + ARTIFACT_ID
        + "-"
        + baseVersion
        + "-SNAPSHOT."
        + extension;
  }

  private String release(String version, String extension, int size, Instant createdAt)
      throws IOException {
    return release(version, extension, size, createdAt, null);
  }

  private String release(
      String version, String extension, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    return row(releasePath(version, extension), size, createdAt, accessedAt);
  }

  private String snapshot(
      String baseVersion, String timestamp, int buildNumber, String extension, int size,
      Instant createdAt)
      throws IOException {
    return row(snapshotPath(baseVersion, timestamp, buildNumber, extension), size, createdAt, null);
  }

  private String literalSnapshot(
      String baseVersion, String extension, int size, Instant createdAt) throws IOException {
    return row(literalSnapshotPath(baseVersion, extension), size, createdAt, null);
  }

  /**
   * One deployed file, with both of V11's timestamps under the case's control.
   *
   * <p>A {@code .pom} path gets a real, empty pom rather than filler bytes — the closure parses the
   * pom of every coordinate it reaches, and filler is a pom that does not parse, which keeps the
   * whole type. The path rides in a comment so two poms are never one blob.
   */
  private String row(String path, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    if (path.endsWith(".pom")) {
      return rowBytes(path, pomXml("<!-- " + path + " -->"), createdAt, accessedAt);
    }
    return rowBytes(path, filled(size, (byte) (size % 251)), createdAt, accessedAt);
  }

  private String rowBytes(String path, byte[] bytes, Instant createdAt) throws IOException {
    return rowBytes(path, bytes, createdAt, null);
  }

  private String rowBytes(String path, byte[] bytes, Instant createdAt, Instant accessedAt)
      throws IOException {
    int size = bytes.length;
    String blobId = store(bytes);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              MavenArtifact artifact = new MavenArtifact();
              artifact.repository = MAVEN_REPO;
              artifact.path = path;
              artifact.blobId = blobId;
              artifact.sizeBytes = size;
              artifact.createdAt = createdAt;
              artifact.accessedAt = accessedAt;
              mavenArtifacts.persist(artifact);
            });
    return blobId;
  }

  /**
   * One release of any artifact in the fixture's group: a jar, and a pom whose body is {@code
   * pomBody} — a parent, dependencies, a dependencyManagement block.
   */
  private void deploy(String artifactId, String version, String pomBody) throws IOException {
    String directory = "eu/wohlben/qits/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
    rowBytes(
        directory + ".jar",
        (artifactId + ":" + version).getBytes(StandardCharsets.UTF_8),
        daysAgo(400));
    rowBytes(
        directory + ".pom",
        pomXml(
            "<groupId>" + GROUP_ID + "</groupId><artifactId>" + artifactId + "</artifactId>"
                + "<version>" + version + "</version>" + pomBody),
        daysAgo(400));
  }

  private static byte[] pomXml(String body) {
    return ("<?xml version=\"1.0\"?><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + body
            + "</project>")
        .getBytes(StandardCharsets.UTF_8);
  }

  private void sbomRepository() {
    repositoryService.ensure(SBOM_REPO, SbomProfile.KEY);
  }

  /** A stored CycloneDX document for one of the fixture group's coordinates, naming these purls. */
  private void sbom(String artifactId, String version, String... purls) {
    StringBuilder components = new StringBuilder();
    for (String purl : purls) {
      components
          .append(components.length() == 0 ? "" : ",")
          .append("{\"type\":\"library\",\"purl\":\"")
          .append(purl)
          .append("\"}");
    }
    String blobId =
        store(
            ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"metadata\":{\"component\":"
                    + "{\"purl\":\""
                    + purl(artifactId, version)
                    + "\"}},\"components\":["
                    + components
                    + "]}")
                .getBytes(StandardCharsets.UTF_8));
    sbomRow(SBOM_REPO, "maven", GROUP_ID + ":" + artifactId, version, blobId, daysAgo(400), null);
  }

  private static String purl(String artifactId, String version) {
    return "pkg:maven/" + GROUP_ID + "/" + artifactId + "@" + version + "?type=jar";
  }

  private static String parent(String artifactId, String version) {
    return "<parent><groupId>" + GROUP_ID + "</groupId><artifactId>" + artifactId
        + "</artifactId><version>" + version + "</version></parent>";
  }

  private static String dependency(String artifactId, String version, String scope) {
    return "<dependency><groupId>" + GROUP_ID + "</groupId><artifactId>" + artifactId
        + "</artifactId><version>" + version + "</version>"
        + (scope == null ? "" : "<scope>" + scope + "</scope>")
        + "</dependency>";
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).sorted().toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
