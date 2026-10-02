package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.MavenRegistryCollection;
import eu.wohlben.qits.artifacts.entity.MavenArtifact;
import eu.wohlben.qits.artifacts.control.MavenPackagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The own engine over maven's facts, and the two things this type has that no other own type does: a
 * <b>coordinate made of several rows</b>, and a derived document that redirects a resolver.
 *
 * <p>This suite replaces {@code MavenPackagesGcStrategyTest}, whose whole subject was "nothing dies,
 * said out loud". That posture was a decision with a condition attached — {@code
 * maven-repository-plan.md} §3.6 named a cleanup rule and never priced it — and the settlement
 * priced every own type at once, so the pin the old suite held is replaced deliberately rather than
 * eroded.
 *
 * <p><b>Half of it came back on 2026-09-05, and on purpose.</b> The settlement's access rule ran
 * against this type for one night and deleted 67 published coordinates, breaking every gating build
 * on the platform. {@code noPublishedReleaseIsEverAgeCollected…} is the case that would have caught
 * it and {@code theReleaseKeepIsARuleAboutReleases…} is the case that stops the answer widening into
 * "maven never collects anything": superseded snapshot sets still go, which is the one class of
 * content here that is build output rather than an artifact of record.
 *
 * <p>The two cases that carry the type's structural promises are {@code
 * theNewestTimestampedSnapshotSetIsAlwaysKept…} — {@code maven-metadata.xml} is computed from the
 * surviving rows at read time, so a resolver asking for {@code 1.0.1-SNAPSHOT} is sent to whatever
 * the document says is newest, and deleting that one would point the document at a file the store no
 * longer has — and {@code aCoordinateIsRemovedWholeOrNotAtAll…}, which is the version-atomicity the
 * identity model always claimed and the delete loop did not have.
 *
 * <p><b>And the window is P0D since 2026-09-05</b>, which for this type changes nothing about
 * releases and everything about the rest: a superseded snapshot set goes on the run that finds it,
 * and no row anywhere here is kept by being warm. Every "how old is this row" in a fixture below
 * decides only how honest the case reads — a coordinate lives because a pin or a belt names it, or
 * because it is a published release, and for no other reason.
 */
@QuarkusTest
class MavenPackagesGcAdapterTest extends GcFixture {

  private static final String GROUP_ID = "eu.wohlben.qits";
  private static final String ARTIFACT_ID = "qits-eventstream";
  private static final String COORDINATE = GROUP_ID + ":" + ARTIFACT_ID + ":";

  @Inject MavenPackagesGcStrategy strategy;
  @Inject MavenPackagesGcAdapter adapter;
  @Inject MavenRegistryCollection collection;
  @Inject GcPlanner planner;
  @Inject eu.wohlben.qits.artifacts.control.JpaContentHashLedger ledger;

  @Test
  void noPublishedReleaseIsEverAgeCollectedHoweverColdAndHoweverDeepInTheVersionOrder()
      throws Exception {
    // The rule the 2026-09-05 outage bought, and the case that would have caught it. Six releases
    // of one artifact, every file of every one of them more than a year cold, nothing pinned and
    // four of them below a belt of two. Under the settlement's original pricing four coordinates
    // died here; on 2026-09-05T01:58Z the same rule took 67 of them out of the live store and every
    // gating build on the platform stopped resolving.
    //
    // Nothing dies now, and the rule sentence says why on each line — a reviewer reading the report
    // is told the store is the artifact of record rather than left to infer it from an empty list.
    maven();
    for (String version : List.of("1.0.0", "1.1.0", "2.0.0", "2.1.0", "3.0.0", "3.1.0")) {
      release(version, "jar", 11 + version.hashCode() % 7, daysAgo(400));
      release(version, "pom", 21 + version.hashCode() % 7, daysAgo(400));
    }

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead(), "a published release is never a candidate");
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(6, plan.kept().size());
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"),
        "the oldest one too, and under the release rule rather than under a belt slot");
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "3.1.0"));
  }

  @Test
  void theReleaseKeepIsARuleAboutReleasesRatherThanAboutMavenNothingEverDying() throws Exception {
    // The other direction, so the rule above is pinned as a rule rather than as "this type stopped
    // collecting". One release and one superseded timestamped snapshot set, identically cold,
    // identically unpinned, in the same repository: the release stays and the snapshot goes. If the
    // release keep ever widens into "no maven row is collected" this fails, which is the point.
    maven();
    release("1.0.0", "jar", 31, daysAgo(400));
    snapshot("1.1.0", "20260601.101010", 1, "jar", 32, daysAgo(400));
    snapshot("1.1.0", "20260802.123456", 2, "jar", 33, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(COORDINATE + "1.1.0-20260601.101010-1"),
        identities(plan.dead()),
        "build output this store regenerates, superseded on its own line");
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_RESOLVABLE_SNAPSHOT,
        ruleFor(plan.kept(), COORDINATE + "1.1.0-20260802.123456-2"));
  }

  @Test
  void mavensOwnVersionOrderIsStillTotalEvenThoughNoReleaseIsCollectedByIt() throws Exception {
    // 1.0.10 is above 1.0.9 and a -SNAPSHOT of a version is below its release, which a lexical
    // comparison gets backwards. The belt no longer decides anything for this type — every release
    // is kept before it is consulted — but the engine's contract still asks for a total order over
    // an adapter's identities, and losing the coverage with the belt would leave that answer
    // unwatched. So the comparator is asserted directly, and the plan is asserted to keep all three.
    maven();
    release("1.0.10", "jar", 21, daysAgo(400));
    release("1.0.9", "jar", 22, daysAgo(400));
    release("1.0.2", "jar", 23, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        List.of(COORDINATE + "1.0.10", COORDINATE + "1.0.2", COORDINATE + "1.0.9"),
        identities(plan.kept()));
    assertEquals(
        List.of(COORDINATE + "1.0.2", COORDINATE + "1.0.9", COORDINATE + "1.0.10"),
        adapter.enumerate().stream()
            .sorted(adapter.byAge())
            .map(GcCandidate::identity)
            .toList(),
        "oldest release first, by maven's own version order rather than lexically");
  }

  @Test
  void aReleaseIsKeptUnderTheReleaseRuleRatherThanUnderTheAccessWindow() throws Exception {
    // What the access window used to be doing for a library, and no longer has to. A version three
    // deep with one warm file used to be saved by the resolve; it is saved by being a release now,
    // and the rule sentence has to say so — a report naming the window beside a coordinate that
    // would be kept stone cold is claiming a rule that is not what saved it. At P0D there is no
    // window left to claim at all, which is why this case is written as one plan and not two: the
    // release rule is the only thing between these four files and a delete.
    maven();
    release("1.0.0", "jar", 41, daysAgo(400));
    release("1.0.0", "pom", 42, daysAgo(400), daysAgo(1));
    release("1.1.0", "jar", 43, daysAgo(380));
    release("2.0.0", "jar", 44, daysAgo(360));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"));
  }

  @Test
  void aVersionSomeRepositorysPomStillReferencesIsKeptUnderThePinItIsNamedBy() throws Exception {
    // The invariant, stated where it can be read: anything a main pom pins survives. It is belt and
    // braces now — a pinned release would be kept for being a release anyway — and it is asked
    // FIRST deliberately, because a reviewer of the report wants to know which repository still
    // builds against a version rather than that it happens to be a release.
    //
    // The pin joins on the identity UNCHANGED: "g:a:v" is what a pom writes and what this adapter
    // folds its rows into, so the case is written with the same string on both sides deliberately.
    maven();
    release("1.0.0", "jar", 101, daysAgo(400));
    release("1.0.0", "pom", 102, daysAgo(400));
    release("1.1.0", "jar", 103, daysAgo(390));
    release("2.0.0", "jar", 104, daysAgo(380));

    GcStrategy.Plan referenced = strategy.plan(census.take(), referencing(COORDINATE + "1.0.0"));

    assertEquals(List.of(), referenced.dead(), "a referenced version is never a candidate");
    assertEquals(GcPins.BY_MANIFEST, ruleFor(referenced.kept(), COORDINATE + "1.0.0"));

    // And the half that matters most: the pin reaches through to a SNAPSHOT too, which is the one
    // class of maven content this type still collects. A superseded timestamped set that some
    // manifest names outlives the window that takes its unpinned neighbour.
    snapshot("1.2.0", "20260601.101010", 1, "jar", 105, daysAgo(400));
    snapshot("1.2.0", "20260701.202020", 2, "jar", 106, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "jar", 107, daysAgo(400));
    String pinnedSnapshot = COORDINATE + "1.2.0-20260601.101010-1";

    GcStrategy.Plan pinned = strategy.plan(census.take(), referencing(pinnedSnapshot));
    assertEquals(GcPins.BY_MANIFEST, ruleFor(pinned.kept(), pinnedSnapshot));
    assertEquals(
        List.of(COORDINATE + "1.2.0-20260701.202020-2"),
        identities(pinned.dead()),
        "and the unpinned superseded set beside it still goes");
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
    literalSnapshot("1.0.3", "jar", 62, daysAgo(400));
    snapshot("1.0.3", "20260802.123456", 1, "jar", 63, daysAgo(400));

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
    // Two questions wearing one group field, and this is the case that would catch them colliding.
    // The belt half of it went with the release rule — no number of snapshot builds can push a
    // release anywhere now — but the other direction is still live and still load-bearing: three
    // releases of this artifact must not make the snapshot line's newest set eligible, which is
    // exactly what a shared group key would do.
    maven();
    release("1.0.0", "jar", 71, daysAgo(400));
    release("1.1.0", "jar", 72, daysAgo(390));
    snapshot("1.2.0", "20260601.101010", 1, "jar", 73, daysAgo(400));
    snapshot("1.2.0", "20260701.202020", 2, "jar", 74, daysAgo(400));
    snapshot("1.2.0", "20260802.123456", 3, "jar", 75, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(COORDINATE + "1.2.0-20260601.101010-1", COORDINATE + "1.2.0-20260701.202020-2"),
        identities(plan.dead()));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.0.0"));
    assertEquals(
        MavenPackagesGcAdapter.KEPT_HOSTED_RELEASE, ruleFor(plan.kept(), COORDINATE + "1.1.0"));
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
    // Written over a superseded snapshot set since 2026-09-05, because a release cannot be
    // condemned any more and a case whose subject is what happens to a condemned coordinate needs
    // one that can be.
    maven();
    String youngPom = snapshot("1.0.1", "20260601.101010", 1, "pom", 82, daysAgo(400));
    snapshot("1.0.1", "20260601.101010", 1, "jar", 81, daysAgo(400));
    snapshot("1.0.1", "20260802.123456", 3, "jar", 83, daysAgo(400));

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
    // and releases are never collected — so the row is written here by hand for a superseded
    // snapshot set, which is the one coordinate this adapter does condemn. What is under test is the
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

  /** One deployed file, with both of V11's timestamps under the case's control. */
  private String row(String path, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
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
