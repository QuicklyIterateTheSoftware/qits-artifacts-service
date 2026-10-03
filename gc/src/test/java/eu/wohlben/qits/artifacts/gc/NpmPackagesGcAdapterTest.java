package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.entity.NpmDistTag;
import eu.wohlben.qits.artifacts.entity.NpmVersion;
import eu.wohlben.qits.artifacts.control.NpmPackagesProfile;
import eu.wohlben.qits.artifacts.control.SbomProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The own engine over npm's facts, case by case, against real rows.
 *
 * <p><b>This suite is the port of {@code NpmPackagesGcStrategyTest}</b>, and the version strings are
 * the measured ones wherever a case allows it — {@code @qits/ui-components} really does hold two
 * calver releases and four {@code -main.g<sha7>} builds, and the two builds sharing the {@code
 * 2026.801.85149} core are what makes the ordering rule worth testing at all: they differ only in a
 * hex sha, which semver compares as ASCII. A case written with {@code -rc.1} and {@code -rc.2} would
 * order correctly for the wrong reason.
 *
 * <p>What changed with the settlement is what condemns a version: not "a newer main build exists"
 * but "nothing has installed it inside the window". So every case says how old its rows are, and the
 * releases that used to be kept forever are now kept as the last two — with the third surviving on
 * <em>use</em>, which is the case the structural rule could not have had.
 *
 * <p><b>The window is P0D since 2026-09-05</b>, and what replaced it was never a shorter window: it
 * is {@code npmDependencies} — the versions repositories' package.json files still resolve to — and
 * the dist-tags. Both have cases of their own below, and the two cases that used to prove "an
 * install keeps a version alive" now prove the opposite doctrine under their own names: being warm
 * is not a keep, being NAMED is.
 */
@QuarkusTest
class NpmPackagesGcAdapterTest extends GcFixture {

  /** The configured window for this type — zero since 2026-09-05, so nothing is kept by age. */
  private static final Duration WINDOW = Duration.ZERO;

  private static final String UI = "@qits/ui-components";
  private static final String APP = "@qits/app";

  @Inject NpmPackagesGcStrategy strategy;
  @Inject GcPlanner planner;
  @Inject eu.wohlben.qits.artifacts.control.JpaContentHashLedger ledger;

  @Test
  void anOldReleaseNothingNamesIsCondemnedWhileTheNewestTwoAreKept() throws Exception {
    // qits-740: a release is collected again when nothing kept reaches it. Four releases of one
    // package, every one cold past a year, nothing pinned, nothing depending on them, no dist-tag:
    // the newest two stay on the belt and the two below it go.
    hosted();
    String oldest = version(UI, "0.0.1", 11, daysAgo(400));
    String older = version(UI, "0.0.4", 12, daysAgo(380));
    version(UI, "2026.801.63140", 13, daysAgo(360));
    version(UI, "2026.801.85149", 14, daysAgo(340));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of(UI + "@0.0.1", UI + "@0.0.4"),
        identities(plan.dead()).stream().sorted().toList());
    assertEquals(Set.of(oldest, older), plan.blobsReleased());
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), UI + "@2026.801.63140"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), UI + "@2026.801.85149"));
  }

  @Test
  void aReleaseReachedOnlyThroughAKeptVersionsDependenciesIsKeptNamingTheReferrer()
      throws Exception {
    // The closure. @qits/app's newest release depends on @qits/ui ^1.0.0; ui 1.0.0 sits below ui's
    // own belt and nothing pins it, so only the edge keeps it — and the line names the referrer.
    // ui 2.0.0 satisfies nothing anyone kept and goes.
    hosted();
    version(APP, "1.0.0", 121, daysAgo(400));
    version(APP, "1.1.0", 122, daysAgo(400), null, dependencies(UI, "^1.0.0"));
    version(UI, "1.0.0", 123, daysAgo(400));
    version(UI, "2.0.0", 124, daysAgo(400));
    version(UI, "3.0.0", 125, daysAgo(400));
    version(UI, "4.0.0", 126, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        NpmKeepClosure.DEPENDENCY_OF + APP + "@1.1.0" + NpmKeepClosure.WHICH_IS_KEPT,
        ruleFor(plan.kept(), UI + "@1.0.0"));
    assertEquals(List.of(UI + "@2.0.0"), identities(plan.dead()));
  }

  @Test
  void aReleaseAKeptVersionsSbomNamesIsKept() throws Exception {
    // The SBOM half of the closure, through the real document store: the range would pick the
    // newest, the build locked an older one, and the document says which.
    hosted();
    repositoryService.ensure("sboms", SbomProfile.KEY);
    version(APP, "1.0.0", 131, daysAgo(400));
    version(UI, "1.0.0", 132, daysAgo(400));
    version(UI, "2.0.0", 133, daysAgo(400));
    version(UI, "3.0.0", 134, daysAgo(400));
    version(UI, "4.0.0", 135, daysAgo(400));
    String document =
        store(
            ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.5\",\"components\":"
                    + "[{\"purl\":\"pkg:npm/%40qits/ui-components@2.0.0\"}]}")
                .getBytes(StandardCharsets.UTF_8));
    sbomRow("sboms", "npm", APP, "1.0.0", document, daysAgo(400), null);

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        NpmKeepClosure.NAMED_BY_SBOM + APP + "@1.0.0" + NpmKeepClosure.WHICH_IS_KEPT,
        ruleFor(plan.kept(), UI + "@2.0.0"));
    assertEquals(List.of(UI + "@1.0.0"), identities(plan.dead()));
  }

  @Test
  void aClosureThatCannotBeCompletedCollectsNothingNpmThatRun() throws Exception {
    // Fail closed. A kept version depends on a hosted package through a spec this rule cannot read,
    // so nothing says which version it installs — and therefore nothing says what is safe to
    // delete. Every identity stays, a prerelease included, and every line names the version and
    // the reason. A manifest pin still reports itself first.
    hosted();
    version(APP, "1.0.0", 141, daysAgo(400), null, dependencies(UI, "file:../ui"));
    version(UI, "1.0.0", 142, daysAgo(400));
    version(UI, "2.0.0", 143, daysAgo(400));
    version(UI, "3.0.0", 144, daysAgo(400));
    version(UI, "4.0.0-main.gab854a1", 145, daysAgo(400));

    GcStrategy.Plan plan = strategy.plan(census.take(), referencing(UI + "@2.0.0"));

    assertEquals(List.of(), plan.dead(), "a partial closure is never a deletion");
    String rule = ruleFor(plan.kept(), UI + "@1.0.0");
    assertTrue(rule.startsWith("npm collects nothing this run: " + APP + "@1.0.0: "), rule);
    assertTrue(rule.contains("file:../ui"), "and the reason: " + rule);
    assertEquals(rule, ruleFor(plan.kept(), UI + "@4.0.0-main.gab854a1"));
    assertEquals(GcPins.BY_MANIFEST, ruleFor(plan.kept(), UI + "@2.0.0"));
  }

  @Test
  void noPrereleaseSurvivesAZeroWindowNotEvenTheOnePublishedAMomentAgo() throws Exception {
    // Prereleases earn no belt — own-ness protects releases, and a main build is not one — so with
    // the window at zero there is nothing left for one to survive on except a dist-tag or a
    // dependency pin, each of which has its own case. Four builds, aged from a year to a minute, and
    // all four go: the one installed yesterday and the one published a minute ago included.
    //
    // That is the realignment stated at its sharpest, and it is safe for the reason the doctrine
    // gives: `@main` is what a consumer of a build resolves through, and a dist-tag names it.
    hosted();
    version(UI, "2026.801.85149", 31, daysAgo(200));
    String coldest = version(UI, "2026.801.63140-main.gab854a1", 32, daysAgo(400));
    String cold = version(UI, "2026.801.85149-main.g21655ba", 33, daysAgo(300));
    String installedYesterday = version(UI, "2026.801.85149-main.g0fe7780", 34, daysAgo(400), daysAgo(1));
    String newest = version(UI, "2026.801.85149-main.gd43d710", 35, Instant.now());

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(
        Stream.of(
                UI + "@2026.801.63140-main.gab854a1",
                UI + "@2026.801.85149-main.g0fe7780",
                UI + "@2026.801.85149-main.g21655ba",
                UI + "@2026.801.85149-main.gd43d710")
            .sorted()
            .toList(),
        identities(plan.dead()).stream().sorted().toList());
    assertEquals(
        List.of(UI + "@2026.801.85149"),
        identities(plan.kept()),
        "the release keeps its belt slot, which is the only keep left in this store");
    assertEquals(
        Set.of(coldest, cold, installedYesterday, newest), plan.blobsReleased());

    // And through the substrate: a tarball is named by exactly one version, so every released blob
    // loses its last reference and the whole reclaim is real.
    assertEquals(
        Stream.of(coldest, cold, installedYesterday, newest).sorted().toList(),
        planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  @Test
  void aVersionSomeRepositorysLockfileStillResolvesIsKeptUnderThePinItIsNamedBy() throws Exception {
    // The invariant, stated where it can be read: anything a lockfile on main resolves to survives
    // — since qits-740 including a lockfile reached through a service's frontend submodule gitlink,
    // which qits-maintenance reports as that service's pin. An old release below the belt lives on
    // the pin alone, and the pin is asked FIRST, because a reviewer of the report wants to know
    // which repository builds against a version.
    //
    // The pin joins on the identity UNCHANGED: "name@version" is what a lockfile resolves to and
    // what this adapter spells its identities with, so both sides of the case are the same string.
    hosted();
    version(UI, "0.0.1", 111, daysAgo(400));
    version(UI, "0.0.4", 112, daysAgo(400));
    version(UI, "2026.801.63140", 113, daysAgo(360));
    version(UI, "2026.801.85149", 116, daysAgo(350));

    GcStrategy.Plan referenced = strategy.plan(census.take(), referencing(UI + "@0.0.4"));

    assertEquals(
        List.of(UI + "@0.0.1"),
        identities(referenced.dead()),
        "the pinned old release outlives the unpinned one below the belt");
    assertEquals(GcPins.BY_MANIFEST, ruleFor(referenced.kept(), UI + "@0.0.4"));

    // And the half that matters most: the pin reaches a PRERELEASE too, which is the one class this
    // type still collects. A cold build some lockfile still resolves outlives its unpinned neighbour.
    version(UI, "2026.801.85149-main.gab854a1", 114, daysAgo(400));
    version(UI, "2026.801.85149-main.g21655ba", 115, daysAgo(400));

    GcStrategy.Plan pinnedBuild =
        strategy.plan(census.take(), referencing(UI + "@2026.801.85149-main.gab854a1"));

    assertEquals(
        GcPins.BY_MANIFEST, ruleFor(pinnedBuild.kept(), UI + "@2026.801.85149-main.gab854a1"));
    assertEquals(
        Stream.of(UI + "@0.0.1", UI + "@0.0.4", UI + "@2026.801.85149-main.g21655ba")
            .sorted()
            .toList(),
        identities(pinnedBuild.dead()).stream().sorted().toList(),
        "and the unpinned build beside it still goes — as does 0.0.4, no longer pinned");
  }

  @Test
  void aDistTagKeepsAVersionTheWindowWouldHaveCondemned() throws Exception {
    // Belt and braces, made to matter: `main` is pointed at a build nothing has installed in a year,
    // which is what a pinned pipeline or a rolled-back release leaves behind. A packument whose
    // dist-tags names a version its versions object does not list is a broken package to every npm
    // client, and no access timestamp should be the only thing standing between here and there.
    hosted();
    version(UI, "0.0.1", 40, daysAgo(600));
    version(UI, "0.0.2", 44, daysAgo(600));
    version(UI, "2026.801.63140", 45, daysAgo(300));
    version(UI, "2026.801.85149", 41, daysAgo(200));
    String tagged = version(UI, "2026.801.85149-main.g11111aa", 42, daysAgo(400));
    version(UI, "2026.801.85149-main.g22222bb", 43, daysAgo(400));
    distTag(UI, "latest", "2026.801.85149");
    distTag(UI, "legacy", "0.0.1");
    distTag(UI, "main", "2026.801.85149-main.g11111aa");

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        NpmPackagesGcAdapter.keptByDistTag("main"),
        ruleFor(plan.kept(), UI + "@2026.801.85149-main.g11111aa"));
    assertEquals(
        NpmPackagesGcAdapter.keptByDistTag("legacy"),
        ruleFor(plan.kept(), UI + "@0.0.1"),
        "an old release below the belt, kept because a pointer names it");
    assertEquals(
        Stream.of(UI + "@0.0.2", UI + "@2026.801.85149-main.g22222bb").sorted().toList(),
        identities(plan.dead()).stream().sorted().toList(),
        "only what is neither on the belt nor named");
    assertTrue(plan.blobsRetained().contains(tagged));
  }

  @Test
  void theBeltIsPerPackageAndOnePackagesReleasesNeverSpendAnothers() throws Exception {
    // The rule reads "per package" and the tables are keyed that way, so this is the case that would
    // catch a group that forgot the package name. The belt half went with the release rule — no
    // number of releases can push another package's off anything now — and the grouping still has to
    // be right, because it is what the enumeration reads dist-tags per and what a report attributes.
    hosted();
    version("@qits/angular", "0.0.1", 51, daysAgo(600));
    version(UI, "1.0.0", 52, daysAgo(400));
    version(UI, "1.1.0", 53, daysAgo(390));
    version(UI, "1.2.0", 54, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(UI + "@1.0.0"), identities(plan.dead()));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "@qits/angular@0.0.1"),
        "a package with one release keeps it, however cold, whatever another package holds");
  }

  @Test
  void aVersionThatIsNotSemverIsNeverAReleaseAndAtAZeroWindowOnlyAPointerSavesIt()
      throws Exception {
    // What cannot be ordered cannot be proved to be the last two of anything, so it earns no belt.
    // The window used to be what kept such a version alive while anything installed it; at P0D that
    // is gone, and the honest answer is that an unrecognised coordinate lives exactly as long as
    // something POINTS at it. A dist-tag is that pointer, and the packument would be broken without
    // it — which is why the belt-and-braces keep is what carries this case now.
    hosted();
    version(UI, "1.0.0", 61, daysAgo(400));
    version(UI, "1.1.0", 62, daysAgo(390));
    version(UI, "nightly", 63, daysAgo(1));
    String coldNightly = version(UI, "rolling", 64, daysAgo(400));

    GcStrategy.Plan onAccessAlone = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Stream.of(UI + "@nightly", UI + "@rolling").sorted().toList(),
        identities(onAccessAlone.dead()).stream().sorted().toList(),
        "installed yesterday is not a keep, and neither is being unrecognised a kill of its own");
    assertTrue(onAccessAlone.blobsReleased().contains(coldNightly));

    distTag(UI, "nightly", "nightly");
    GcStrategy.Plan pointed = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        NpmPackagesGcAdapter.keptByDistTag("nightly"), ruleFor(pointed.kept(), UI + "@nightly"));
    assertEquals(List.of(UI + "@rolling"), identities(pointed.dead()));
  }

  @Test
  void aCondemnedVersionLosesItsRowAndGainsItsTombstoneInTheSameTransaction() throws Exception {
    // The tombstone is npm's alone and it STAYS: deleting a version row re-opens that version's
    // name for a publish with different bytes, which is one coordinate resolving to two tarballs
    // over its lifetime. The guarantee is the funnel's rather than this rule's, and the property
    // asserted here is that the collector really goes through it.
    //
    // Written over a PRERELEASE since 2026-09-05, because a release cannot be condemned any more and
    // a case whose subject is what happens to a condemned version needs one that can be.
    hosted();
    String doomed = version(UI, "1.0.0-main.gab854a1", 81, daysAgo(400));
    version(UI, "1.1.0", 82, daysAgo(390));
    version(UI, "1.2.0", 83, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(UI + "@1.0.0-main.gab854a1"), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne("npm", UI, "1.0.0-main.gab854a1").isEmpty(), "the row is gone");
    assertTrue(
        npmVersionTombstones.findOne("npm", UI, "1.0.0-main.gab854a1").isPresent(),
        "and the name can never be silently republished");
  }

  @Test
  void aCollectedVersionTakesItsContentHashRowWithItAndAKeptOneKeepsIts() throws Exception {
    // epic qits-620: the row is metadata about a version this store serves, so it is deleted in the
    // same transaction as the version row and its tombstone.
    hosted();
    version(UI, "1.0.0-main.gab854a1", 181, daysAgo(400));
    version(UI, "1.1.0", 182, daysAgo(390));
    ledger.record("npm", "npm", UI, "1.0.0-main.gab854a1", "v1:sha256:" + "a".repeat(64));
    ledger.record("npm", "npm", UI, "1.1.0", "v1:sha256:" + "b".repeat(64));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(UI + "@1.0.0-main.gab854a1"), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    assertTrue(ledger.find("npm", "npm", UI, "1.0.0-main.gab854a1").isEmpty(), "the row went too");
    assertTrue(ledger.find("npm", "npm", UI, "1.1.0").isPresent(), "the kept release keeps its");
  }

  @Test
  void aTarballInsideTheGraceWindowWithholdsTheWholeIdentityRowIntactAndNoTombstone()
      throws Exception {
    // The strand hazard: a row deleted over a young file leaves the file row-less, and row-less
    // blobs are untouchable by construction. No tombstone either — the version is not collected yet.
    // Over a prerelease, for the reason the case above gives.
    hosted();
    String doomed = version(UI, "1.0.0-main.gab854a1", 91, daysAgo(400));
    version(UI, "1.1.0", 92, daysAgo(390));
    version(UI, "1.2.0", 93, daysAgo(380));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> blobId.equals(doomed));

    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of(UI + "@1.0.0-main.gab854a1"), identities(applied.withheldByGraceWindow()));
    npmVersions.getEntityManager().clear();
    assertTrue(npmVersions.findOne("npm", UI, "1.0.0-main.gab854a1").isPresent(), "the row stays");
    assertTrue(npmVersionTombstones.findOne("npm", UI, "1.0.0-main.gab854a1").isEmpty(), "no tombstone");
  }

  @Test
  void anEmptyRepositoryPlansNothingRatherThanFailing() throws Exception {
    hosted();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(NpmPackagesProfile.KEY).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureNothingDiesAndTheRetainedSetIsTheCensusSet() throws Exception {
    // The substrate's store: one package at 1.0.0 and 1.1.0, both releases, and one of the two
    // tarballs is the same blob an image layer uses. Nothing dies — and the set handed back is
    // exactly the census's own npm-packages live set, which is what makes "one census, two readers"
    // true here rather than merely intended.
    seed();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("@qits/thing@1.0.0", "@qits/thing@1.1.0"), identities(plan.kept()));
    assertEquals(taken.live(NpmPackagesProfile.KEY).keySet(), plan.blobsRetained());
    assertEquals(List.of(), planner.plan(taken, List.of(strategy), GcPins.none()).sweep().blobIds());
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void hosted() {
    repositoryService.ensure("npm", NpmPackagesProfile.KEY);
  }

  /** The aggregate a run would have read, with these npm coordinates named by a manifest on main. */
  private static GcPins referencing(String... coordinates) {
    return new GcPins(
        java.util.Map.of(),
        "",
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(coordinates),
        Set.of(),
        Set.of(),
        List.of());
  }

  private String version(String packageName, String version, int size, Instant createdAt)
      throws IOException {
    return version(packageName, version, size, createdAt, null);
  }

  private String version(
      String packageName, String version, int size, Instant createdAt, Instant accessedAt)
      throws IOException {
    return version(packageName, version, size, createdAt, accessedAt, "{}");
  }

  /** A manifest whose {@code dependencies} name one package at one spec. */
  private static String dependencies(String packageName, String spec) {
    return "{\"dependencies\":{\"" + packageName + "\":\"" + spec + "\"}}";
  }

  /** A version whose tarball is a real blob, with both of V11's timestamps under the case's control. */
  private String version(
      String packageName,
      String version,
      int size,
      Instant createdAt,
      Instant accessedAt,
      String manifestJson)
      throws IOException {
    String blobId = store(filled(size, (byte) (size % 251)));
    // Aged past the sweep's grace window, so a case asserts on the reconciliation rather than on
    // what was withheld for being written a moment ago.
    backdate(blobId, Duration.ofDays(30));
    versionRow("npm", packageName, version, blobId, createdAt, accessedAt, manifestJson);
    return blobId;
  }

  private void versionRow(
      String repository,
      String packageName,
      String version,
      String blobId,
      Instant createdAt,
      Instant accessedAt,
      String manifestJson) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmVersion row = new NpmVersion();
              row.repository = repository;
              row.packageName = packageName;
              row.version = version;
              row.tarballBlobId = blobId;
              row.manifestJson = manifestJson;
              row.createdAt = createdAt;
              row.accessedAt = accessedAt;
              npmVersions.persist(row);
            });
  }

  private void distTag(String packageName, String tag, String version) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              NpmDistTag row = new NpmDistTag();
              row.repository = "npm";
              row.packageName = packageName;
              row.tag = tag;
              row.version = version;
              row.updatedAt = Instant.now();
              npmDistTags.persist(row);
            });
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> identities, String identity) {
    return identities.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError(identity + " is in neither list: " + identities));
  }
}
