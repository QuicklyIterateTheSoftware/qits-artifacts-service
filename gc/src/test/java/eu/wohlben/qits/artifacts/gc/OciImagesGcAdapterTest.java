package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.LiveBlobCensus;
import eu.wohlben.qits.artifacts.control.OciMediaTypes;
import eu.wohlben.qits.artifacts.entity.OciManifest;
import eu.wohlben.qits.artifacts.entity.OciTag;
import eu.wohlben.qits.artifacts.control.OciImagesProfile;
import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import eu.wohlben.qits.artifacts.gc.dto.GcPlanReport;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The own engine over docker's facts, case by case, against real manifests and the real census.
 *
 * <p><b>This suite is the port of {@code OciImageGcStrategyTest}, and the cases carry over one for
 * one</b> — calver, cd's pins, the newest-release belt, the untagged manifests, the grace window —
 * with the one change the settlement made: what condemns a coordinate is no longer "a newer build
 * exists" but "nothing has pulled it inside the window". Every case therefore has to say how old its
 * rows are, and the ones that used to prove a structural kill now prove an <em>access-gated</em>
 * one. The direction of that change is a loosening: {@code aShaTagSomethingStillPulls…} is the case
 * that could not have existed before.
 *
 * <p><b>The belt's cases moved from the sha to the calver on 2026-09-04</b>, with the deployments
 * they describe: cd creates a deployment from {@code qits/<app>:<version>} now, so "the next
 * deploy's pull target" is the newest release and a sha tag has no claim on that rule any more.
 * Three cases carry the flip directly — {@code theBeltNamesTheNewestCALVER…}, {@code
 * theBeltReadsTheVersionsOwnOrder…} and {@code anImageThatHasNeverBeenReleasedHasNoBeltAtAll} — and
 * a fourth, {@code aReleasedVersionNothingHasDeployedYetSurvives…}, holds the audit that went with
 * it: a released-but-undeployed version is covered by the access rule's fold of {@code updated_at},
 * not by the pin and not by retention.
 *
 * <p><b>Four pin sources reach this type</b>: cd names the images of containers that exist,
 * qits-platform-maintenance names the images a repository's Dockerfile still builds {@code FROM},
 * and qits-workspaces and qits-projects name the images they would launch today. The last three
 * join on the FULL image name, cd on the image half alone, and each has a case of its own below. The fourth belt, {@link
 * OciImagesGcAdapter#KEPT_LATEST}, is structural and reads no pin at all.
 *
 * <p>The pins are handed in as a value rather than fetched: what is under test is the keep-set, and
 * its whole input is "which coordinates does a source pin for this image". Which rows those came from is
 * cd's rule and is tested in cd's own repository. The port's field is still called {@code shas} and
 * its strings are opaque, so cases pin whichever shape they are about.
 *
 * <p>Sha tags are written as full 40-hex strings because that is what per-push CI used to push and
 * what an old deployment row carries; a case using {@code v1} would prove nothing about the
 * classification these facts turn on. Version tags are written as real calvers for the same reason.
 */
@QuarkusTest
class OciImagesGcAdapterTest extends GcFixture {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);
  private static final String SHA_D = "d".repeat(40);

  /** The configured window for this type — zero since 2026-09-05, so nothing here is kept by age. */
  private static final Duration WINDOW = Duration.ZERO;

  @Inject OciImageGcStrategy strategy;
  @Inject GcPlanner planner;

  @Test
  void theLastTwoCalverReleasesOfAnImageStayAndTheThirdOneAgesOut() throws Exception {
    // "Releases stay" in its settled docker spelling: last 2, not every calver tag. All three are
    // equally cold, so only the belt separates them — and the two that survive are the two newest
    // by CALVER ORDER, not by the order the rows were written or last touched.
    repository();
    String config = config();
    tag("qits-stt", "2026.801.85448", image("qits-stt", config, 101), daysAgo(400));
    tag("qits-stt", "2026.1201.5", image("qits-stt", config, 102), daysAgo(300));
    tag("qits-stt", "2026.802.10", image("qits-stt", config, 103), daysAgo(200));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of("qits-stt:2026.801.85448"), identities(plan.dead()));
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-stt:2026.1201.5"),
        "1201 is a later month than 802, which a lexical comparison gets backwards — and the highest"
            + " version is now the belt's tag, so it is kept as the next deploy's pull target rather"
            + " than as one of the last two");
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-stt:2026.802.10"));
  }

  @Test
  void theBeltNamesTheNewestCALVERAndNoLongerTheNewestSha() throws Exception {
    // THE FLIP, stated as one case. Every row here is colder than the window, so nothing survives on
    // access and only a keep-rule can save anything — and the two candidates for "the next deploy's
    // pull target" are side by side: the newest build sha, which is what this belt used to name, and
    // the newest calver, which is what a deployment is actually created from since 2026-09-04.
    //
    // The sha is deliberately the more recently WRITTEN row of the two, so a belt still reading
    // updated_at would pick it and this assertion would fail on the old code for the right reason.
    repository();
    String config = config();
    tag("qits-events", "2026.903.113443", image("qits-events", config, 801), daysAgo(120));
    tag("qits-events", SHA_A, image("qits-events", config, 802), daysAgo(40));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-events:2026.903.113443"));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW),
        ruleFor(plan.dead(), "qits-events:" + SHA_A),
        "nothing deploys a sha coordinate any more, so the newest one is not a pull target and has"
            + " no claim on the belt");
  }

  @Test
  void theBeltReadsTheVersionsOwnOrderAndNotTheOrderTheRowsWereWritten() throws Exception {
    // The belt's ordering is BY_CALVER rather than a timestamp, which matters exactly when a release
    // is pushed out of order — a re-push of an older version, or a release run that lands after a
    // newer one. The highest version is the pull target whenever its row happened to be written.
    repository();
    String config = config();
    tag("qits-ci", "2026.1201.5", image("qits-ci", config, 811), daysAgo(300));
    tag("qits-ci", "2026.802.10", image("qits-ci", config, 812), daysAgo(200));
    tag("qits-ci", "2026.801.85448", image("qits-ci", config, 813), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST,
        ruleFor(plan.kept(), "qits-ci:2026.1201.5"),
        "the oldest row and the highest version — the belt reads the name, not updated_at");
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), "qits-ci:2026.802.10"));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(plan.dead(), "qits-ci:2026.801.85448"));
  }

  @Test
  void aReleasedVersionNothingHasDeployedYetIsNotSavedByItsPushOnceTheWindowIsZero()
      throws Exception {
    // THE AUDIT of 2026-09-04, re-answered on 2026-09-05 — and the answer flipped. It used to be
    // that a released-but-undeployed version was covered by the access rule's fold of updated_at: a
    // release was young for a whole window from its push, deployed or not. At P0D there is no such
    // window, so a third-newest release that no pin names and no belt covers is condemned on the
    // run that finds it, however many minutes ago it was pushed.
    //
    // That is not a hole, it is where the safety moved: what protects a JUST-pushed artifact is the
    // six-hour grace window on its bytes — which withholds the identity whole, rows intact — and
    // what protects a version anything is going to deploy is qits-platform-deployments naming it.
    // Four releases, the newest three pushed today; the third-newest is the one under test.
    repository();
    String config = config();
    tag("qits-configuration", "2026.901.10", image("qits-configuration", config, 821), daysAgo(90));
    tag("qits-configuration", "2026.904.35208", image("qits-configuration", config, 822), daysAgo(0));
    tag("qits-configuration", "2026.904.83611", image("qits-configuration", config, 823), daysAgo(0));
    tag("qits-configuration", "2026.904.94820", image("qits-configuration", config, 824), daysAgo(0));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW),
        ruleFor(plan.dead(), "qits-configuration:2026.904.35208"),
        "pushed minutes ago, pinned by nothing, outside the last two — and therefore condemned");
    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-configuration:2026.904.94820"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "qits-configuration:2026.904.83611"),
        "the belt of two is what a release has left, and it has it whatever its age");
    assertEquals(
        List.of("qits-configuration:2026.901.10", "qits-configuration:2026.904.35208"),
        identities(plan.dead()).stream().sorted().toList());

    // And the pin is the answer for a version anything would deploy: the same store, with the
    // deployer naming the coordinate, keeps it under cd's own sentence.
    assertEquals(
        GcPins.BY_CD,
        ruleFor(
            strategy.plan(census.take(), pinning("qits-configuration", "2026.904.35208")).kept(),
            "qits-configuration:2026.904.35208"));
  }

  @Test
  void aCalverReleaseIsKeptWhenNothingDeploysItAndEveryShaBesideItAgesOut() throws Exception {
    // The release coordinate in docker sits BESIDE the sha tag rather than replacing it, so a
    // release nobody runs is still a release. What changed on 2026-09-04 is the fate of the shas: a
    // deployment is created from the version now, so BOTH cold shas here are superseded coordinates
    // that nothing will pull and the window condemns them together. The one that used to be spared
    // as "the newest build" is SHA_C, and it dies beside SHA_B.
    repository();
    String config = config();
    String release = image("qits-stt", config, 111);
    tag("qits-stt", "2026.801.85448", release, daysAgo(200));
    tag("qits-stt", SHA_B, image("qits-stt", config, 112), daysAgo(60));
    tag("qits-stt", SHA_C, image("qits-stt", config, 113), daysAgo(50));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-stt:2026.801.85448"));
    assertEquals(
        Stream.of("qits-stt:" + SHA_B, "qits-stt:" + SHA_C).sorted().toList(),
        identities(plan.dead()));
    assertTrue(
        plan.blobsRetained().contains(release),
        "the release manifest survives on its version tag alone");
  }

  @Test
  void anActiveDeploymentPinKeepsItsCoordinateEvenWhenAYoungerReleaseExists() throws Exception {
    // The IMAGE_MISSING hazard, priced: the container was created from qits/<app>:<version> and a
    // restart pulls that reference again, however long it has been running untouched. The pinned tag
    // is deliberately neither the newest release nor recently pulled, so only the pin can save it —
    // and the belt is spent on the newest version rather than on it, which is what makes this case
    // about the pin and nothing else.
    //
    // The coordinates are versions because that is what cd deploys and therefore what it pins now;
    // the port carries opaque strings (the field is still called `shas`), so the join is the tag
    // name whatever shape it has.
    repository();
    String config = config();
    tag("qits-artifacts", "2026.801.85448", image("qits-artifacts", config, 201), daysAgo(300));
    tag("qits-artifacts", "2026.802.10", image("qits-artifacts", config, 202), daysAgo(200));
    tag("qits-artifacts", "2026.803.20", image("qits-artifacts", config, 203), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), pinning("qits-artifacts", "2026.801.85448"));

    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-artifacts:2026.801.85448"));
    assertEquals(OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-artifacts:2026.803.20"));
    assertEquals(
        OwnArtifactsStrategy.KEPT_RELEASE,
        ruleFor(plan.kept(), "qits-artifacts:2026.802.10"),
        "second-newest release: retention has it, which is why the pin case needs a third row to be"
            + " about the pin");
  }

  @Test
  void aPinnedCoordinateOutlivesTheWindowWhereAnUnpinnedNeighbourDoesNot() throws Exception {
    // The pin doing the work alone, with no release in the image to arm the belt or retention: three
    // cold sha tags of a deployment that predates version coordinates, one of which cd still names.
    // This is the shape a store carries during the changeover, and the pin is the only rule left
    // that can speak for a sha.
    repository();
    String config = config();
    tag("qits-artifacts", SHA_A, image("qits-artifacts", config, 211), daysAgo(300));
    tag("qits-artifacts", SHA_B, image("qits-artifacts", config, 212), daysAgo(200));
    tag("qits-artifacts", SHA_C, image("qits-artifacts", config, 213), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), pinning("qits-artifacts", SHA_A));

    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-artifacts:" + SHA_A));
    assertEquals(List.of("qits-artifacts:" + SHA_A), identities(plan.kept()));
    assertEquals(
        Stream.of("qits-artifacts:" + SHA_B, "qits-artifacts:" + SHA_C).sorted().toList(),
        identities(plan.dead()),
        "the newest sha has no belt of its own now — being newest among dead coordinates is not a"
            + " claim on anything");
  }

  @Test
  void everyCoordinateCdPinsIsKeptUnderOneRuleAndAnythingElseIsNot() throws Exception {
    // cd answers with a SET of coordinates per application — what serves and what a rollback
    // restores, unioned over every environment — so this type keeps all of them under one rule and
    // derives nothing. The third one is one cd did not name, and it dies: applying cd's rule again
    // here would be the drift the pin port exists to remove.
    //
    // The rows are shas rather than versions on purpose: a store carries both shapes through the
    // changeover, the port's strings are opaque, and a pin must keep whatever coordinate it names.
    // The fourth sha is the one the old belt spared for being newest, and it now dies with the
    // third — the pin is the only rule that speaks for a sha.
    repository();
    String config = config();
    tag("qits-platform-deployments", SHA_A, image("qits-platform-deployments", config, 301), daysAgo(400));
    tag("qits-platform-deployments", SHA_B, image("qits-platform-deployments", config, 302), daysAgo(300));
    tag("qits-platform-deployments", SHA_C, image("qits-platform-deployments", config, 303), daysAgo(200));
    tag("qits-platform-deployments", SHA_D, image("qits-platform-deployments", config, 304), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), pinning("qits-platform-deployments", SHA_A, SHA_B));

    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-platform-deployments:" + SHA_A));
    assertEquals(GcPins.BY_CD, ruleFor(plan.kept(), "qits-platform-deployments:" + SHA_B));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(plan.dead(), "qits-platform-deployments:" + SHA_C));
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW),
        ruleFor(plan.dead(), "qits-platform-deployments:" + SHA_D));
  }

  @Test
  void anImageADockerfileOnMainStillReferencesIsKeptUnderTheManifestRule() throws Exception {
    // qits-platform-maintenance's half, and the hazard it closes: a base image is pulled by the
    // BUILDER rather than by a deploy, so nothing here has an access row to show for it and no
    // deployment names it. Every tag is cold and the belt is spent on the newest release, so the
    // manifest pin is the only rule that can be answering.
    //
    // The join is on the FULL image name — repository row plus image, exactly as a Dockerfile
    // spells it — which is what the two new sources have in common and what cd's applicationName
    // does not carry.
    repository();
    String config = config();
    tag("workspace-base", "2026.902.143920", image("workspace-base", config, 901), daysAgo(120));
    tag("workspace-base", "2026.903.113443", image("workspace-base", config, 902), daysAgo(100));
    tag("workspace-base", "2026.904.94820", image("workspace-base", config, 903), daysAgo(80));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), referencingImage("qits/workspace-base:2026.902.143920"));

    assertEquals(
        GcPins.BY_MANIFEST, ruleFor(plan.kept(), "workspace-base:2026.902.143920"));
    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "workspace-base:2026.904.94820"));
    assertEquals(List.of(), plan.dead());

    // The same store with nothing referencing it: the older base image is an ordinary cold release
    // the belt of two no longer covers, and it goes.
    assertEquals(
        List.of("workspace-base:2026.902.143920"),
        identities(strategy.plan(census.take(), GcPins.none()).dead()),
        "so the keep above is the pin and not the fixture");
  }

  @Test
  void theImageQitsWorkspacesWouldLaunchTodayIsKeptAlthoughNothingElseNamesIt() throws Exception {
    // The workspace image is launched on demand, so there is no deployment row to pin it and no
    // Dockerfile that references it. qits-workspaces still starts containers from 2026.904.160522,
    // a third-newest cold release named by nothing else on the platform.
    repository();
    String config = config();
    tag("workspace", "2026.904.160522", image("workspace", config, 921), daysAgo(60));
    tag("workspace", "2026.904.170000", image("workspace", config, 922), daysAgo(50));
    tag("workspace", "2026.905.100000", image("workspace", config, 923), daysAgo(40));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), launchingFromWorkspaces("qits/workspace:2026.904.160522"));

    assertEquals(
        GcPins.BY_WORKSPACE_LAUNCH, ruleFor(plan.kept(), "workspace:2026.904.160522"));
    assertEquals(List.of(), plan.dead());
    assertEquals(
        List.of("workspace:2026.904.160522"),
        identities(strategy.plan(census.take(), GcPins.none()).dead()),
        "so the keep above is the launch pin and nothing in the fixture");
  }

  @Test
  void theImageQitsProjectsWouldLaunchTodayIsKeptUnderItsOwnSentence() throws Exception {
    // The twin, and the sentence is its own because the two services launch overlapping images: a
    // reviewer deciding whether an agent image may go needs to read WHICH service is still pulling
    // it, not that some launch answer somewhere named it.
    repository();
    String config = config();
    tag("project-agent", "2026.903.090000", image("project-agent", config, 931), daysAgo(70));
    tag("project-agent", "2026.904.160152", image("project-agent", config, 932), daysAgo(55));
    tag("project-agent", "2026.905.110000", image("project-agent", config, 933), daysAgo(45));

    GcStrategy.Plan plan =
        strategy.plan(census.take(), launchingFromProjects("qits/project-agent:2026.903.090000"));

    assertEquals(
        GcPins.BY_PROJECT_LAUNCH, ruleFor(plan.kept(), "project-agent:2026.903.090000"));
    assertEquals(List.of(), plan.dead());
    assertEquals(
        List.of("project-agent:2026.903.090000"),
        identities(strategy.plan(census.take(), GcPins.none()).dead()),
        "so the keep above is the launch pin and nothing in the fixture");
  }

  @Test
  void aTagNamedLatestIsAlwaysKeptHoweverColdAndHoweverManyReleasesSitAboveIt() throws Exception {
    // The structural belt of 2026-09-04. A step image is named `qits/build-images/*:latest` in every
    // CI recipe, so it is pulled constantly — and the host-side keep-prefix suppresses exactly those
    // pulls, so accessed_at under-reports it precisely where it is most used. It is not a calver, so
    // neither retention nor the newest-release belt covers it. Deleting it 404s a fresh host's first
    // pull of a step image, which takes out every pipeline on that machine.
    //
    // Written with a year-cold row and two newer releases beside it, because those are the two rules
    // that would otherwise have to be the ones answering.
    repository();
    String config = config();
    String moving = image("build-images/maven-base", config, 921);
    tag("build-images/maven-base", "latest", moving, daysAgo(400));
    tag("build-images/maven-base", "2026.903.113443", image("build-images/maven-base", config, 922), daysAgo(300));
    tag("build-images/maven-base", "2026.904.94820", image("build-images/maven-base", config, 923), daysAgo(200));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OciImagesGcAdapter.KEPT_LATEST,
        ruleFor(plan.kept(), "build-images/maven-base:latest"),
        "and under its OWN sentence, so a reviewer is not told a pointer is a release");
    assertEquals(List.of(), plan.dead());
    assertTrue(plan.blobsRetained().contains(moving));
  }

  @Test
  void anImageNoDeploymentEverNamedKeepsItsNewestReleaseAndDropsTheColdOnes() throws Exception {
    // qits-spa-home's shape, measured: an image with tags and not a single deployment row, every tag
    // older than the window. Without the belt the whole image would be eligible and the next deploy
    // would pull a tag this run deleted — and the tag that deploy will ask for is the newest
    // VERSION, so that is the one the belt names. cd still cannot answer for it: there is no
    // deployment row here to answer from, which is the entire reason this rule is derived here and
    // not fetched.
    //
    // The sha rows are the same image's per-push history. They are cold, nothing pulls them, and no
    // deploy will ever ask for one again, so this case is also where the flip actually collects
    // something: SHA_C used to be kept for being the newest build.
    repository();
    String config = config();
    tag("qits-spa-home", SHA_A, image("qits-spa-home", config, 401), daysAgo(300));
    tag("qits-spa-home", SHA_B, image("qits-spa-home", config, 402), daysAgo(200));
    tag("qits-spa-home", SHA_C, image("qits-spa-home", config, 403), daysAgo(100));
    String newest = image("qits-spa-home", config, 404);
    tag("qits-spa-home", "2026.903.113443", newest, daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        OciImagesGcAdapter.KEPT_NEWEST, ruleFor(plan.kept(), "qits-spa-home:2026.903.113443"));
    assertEquals(1, plan.kept().size());
    assertEquals(
        Stream.of("qits-spa-home:" + SHA_A, "qits-spa-home:" + SHA_B, "qits-spa-home:" + SHA_C)
            .sorted()
            .toList(),
        identities(plan.dead()),
        "the three cold shas; their manifests are still tagged today and are collected next run");
    assertTrue(plan.blobsRetained().contains(newest));
  }

  @Test
  void anImageThatHasNeverBeenReleasedHasNoBeltAtAll() throws Exception {
    // The honest consequence of naming the belt after the release: an image made only of sha tags
    // has no next-deploy pull target to protect, because nothing can be deployed from it. Every cold
    // tag goes, and the answer is a keep-list that is empty rather than one arbitrary survivor.
    //
    // This is a real loosening and it is stated rather than hidden. It costs nothing on a live
    // store: an image nothing has ever released is an image nothing deploys, and the day it IS
    // released the belt arms itself on that release.
    repository();
    String config = config();
    tag("qits-scratch", SHA_A, image("qits-scratch", config, 411), daysAgo(300));
    tag("qits-scratch", SHA_B, image("qits-scratch", config, 412), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.kept());
    assertEquals(
        Stream.of("qits-scratch:" + SHA_A, "qits-scratch:" + SHA_B).sorted().toList(),
        identities(plan.dead()));
  }

  @Test
  void aShaTagNothingPinsGoesAtAZeroWindowHoweverRecentlyItWasPulled() throws Exception {
    // THE FLIP of 2026-09-05, on the case that used to state the opposite. Three superseded build
    // tags, one of them pulled yesterday: the settlement kept that one and named the window as the
    // rule that saved it. At P0D it does not, and the reasoning is the realignment itself — a pull
    // is a fact about a timestamp, and a timestamp cannot say whether anything will pull again. What
    // CAN say so is a pin, and the second half of this case is the same store with cd naming the
    // coordinate.
    repository();
    String config = config();
    String pulled = image("qits-events", config, 411);
    tag("qits-events", SHA_A, pulled, daysAgo(300), daysAgo(1));
    tag("qits-events", SHA_B, image("qits-events", config, 412), daysAgo(300));
    tag("qits-events", SHA_C, image("qits-events", config, 413), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Stream.of("qits-events:" + SHA_A, "qits-events:" + SHA_B, "qits-events:" + SHA_C)
            .sorted()
            .toList(),
        identities(plan.dead()),
        "an image with no release has no belt either, so nothing here is named by anything");
    assertEquals(
        OwnArtifactsStrategy.deadUnaccessed(WINDOW), ruleFor(plan.dead(), "qits-events:" + SHA_A));

    GcStrategy.Plan pinned = strategy.plan(census.take(), pinning("qits-events", SHA_A));

    assertEquals(GcPins.BY_CD, ruleFor(pinned.kept(), "qits-events:" + SHA_A));
    assertTrue(pinned.blobsRetained().contains(pulled));
  }

  @Test
  void aManifestNoTagNamesDiesOnceNothingHasPulledItInsideTheWindow() throws Exception {
    // The 73 untagged manifests, in miniature: a tag re-push moves the tag row to the new manifest
    // and leaves the old row behind, reachable from no coordinate anyone uses. It is an identity of
    // its own here — and only here, because a TAGGED manifest's identity is its tag.
    // The live tag is a calver, so the belt holds it and the ONLY thing this case can condemn is the
    // orphan — which is what makes it a case about the identity model rather than about the window.
    // It was a sha tag until 2026-09-05, when a sha tag with no pin stopped surviving at all.
    repository();
    String config = config();
    String abandoned = image("qits-events", config, 501, daysAgo(300));
    String current = image("qits-events", config, 502);
    tag("qits-events", "2026.903.113443", current, daysAgo(2));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        List.of("qits-events" + OciImagesGcAdapter.DIGEST_PREFIX + abandoned),
        identities(plan.dead()),
        "only the orphan: the tagged manifest is not an identity of its own");
    assertEquals(OwnArtifactsStrategy.deadUnaccessed(WINDOW), plan.dead().get(0).rule());
    assertTrue(plan.blobsReleased().contains(abandoned));
    assertTrue(plan.blobsRetained().contains(current));
  }

  @Test
  void aChildOfATaggedIndexIsNotACandidateOfItsOwnHoweverColdItIs() throws Exception {
    // Multi-arch, and the rule that keeps a live coordinate whole: the index is what a tag names,
    // the child is reached through the index's closure, and the child's own row carries no access of
    // its own to speak with. Enumerating it would let the window condemn a manifest a live tag
    // reaches, which is a broken pull rather than a collection.
    repository();
    String config = config();
    byte[] childBytes = imageManifest(config, Map.of(layer(601), 601L));
    String child = store(childBytes);
    byte[] indexBytes = indexManifest(Map.of(child, (long) childBytes.length));
    String index = store(indexBytes);
    manifestRow("qits-multi", child, childBytes.length, OciMediaTypes.OCI_MANIFEST_V1, daysAgo(300));
    manifestRow("qits-multi", index, indexBytes.length, OciMediaTypes.OCI_INDEX_V1, daysAgo(300));
    tag("qits-multi", "2026.801.1", index, daysAgo(300));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of("qits-multi:2026.801.1"), identities(plan.kept()));
    assertTrue(plan.blobsRetained().contains(child), "the child rides on the index's closure");
  }

  @Test
  void anIdentityWhoseBlobIsStillInsideTheGraceWindowIsWithheldWholeRowsIntact() throws Exception {
    // The strand hazard, closed at the identity rather than at the unlink: deleting the tag row over
    // a young file would leave that file row-less — and row-less blobs are untouchable by
    // construction, so it would never be reclaimed at all. The tag waits out the window with it.
    //
    // The second tag is the image's release, which the belt keeps: this case is about ONE condemned
    // identity meeting a young blob, and a second dead row would only make the assertions below
    // about arithmetic. It was a sha when the belt spared the newest build for free.
    repository();
    String config = config();
    String doomed = image("qits-projects", config, 701);
    tag("qits-projects", SHA_A, doomed, daysAgo(300));
    tag("qits-projects", "2026.801.85448", image("qits-projects", config, 702), daysAgo(100));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied =
        strategy.apply(plan, Graces.blobsOnly(blobId -> blobId.equals(doomed)));

    assertEquals(List.of("qits-projects:" + SHA_A), identities(plan.dead()));
    assertEquals(List.of(), applied.deleted());
    assertEquals(List.of("qits-projects:" + SHA_A), identities(applied.withheldByGraceWindow()));
    assertEquals(List.of(), applied.errors(), "withheld is not an error — it is the window working");
    ociTags.getEntityManager().clear();
    assertTrue(ociTags.findOne("qits", "qits-projects", SHA_A).isPresent(), "the row stays");
  }

  @Test
  void anIncompletePinAggregateIsRefusedRatherThanReadAsNothingIsDeployed() throws Exception {
    // Belt and braces on the run-level abort: the planner never asks a readsPins() strategy to plan
    // against a broken aggregate, and if something ever did, an empty pin map would read as "nothing
    // is deployed" and condemn every coordinate cd holds. Refusing is the only safe answer.
    repository();
    tag("qits-projects", SHA_A, image("qits-projects", config(), 711), daysAgo(300));

    LiveBlobCensus.Census taken = census.take();
    GcPins broken =
        new GcPins(
            Map.of(),
            "",
            Set.of(),
            Set.of(),
            List.of("qits-platform-deployments deployment pins: unreachable at http://qits-platform-deployments:8080/platform-deployments/api"));

    IllegalStateException aborted =
        assertThrows(IllegalStateException.class, () -> strategy.plan(taken, broken));
    assertTrue(aborted.getMessage().contains("qits-platform-deployments"));
    assertTrue(strategy.readsPins(), "and it says so, which is what makes the planner skip it");
  }

  @Test
  void aRepositoryWithNoOciContentPlansNothingRatherThanFailing() throws Exception {
    // The honest answer for rules that exist and match no row: no dead identities, and the type's
    // live set handed back unchanged so the sweep keeps whatever the census found.
    repository();

    LiveBlobCensus.Census taken = census.take();
    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of(), plan.dead());
    assertEquals(List.of(), plan.kept());
    assertEquals(Set.of(), plan.blobsReleased());
    assertEquals(taken.live(OciImagesProfile.KEY).keySet(), plan.blobsRetained());
  }

  @Test
  void againstTheSubstratesOwnFixtureBothUnnamedTagsGoAndTheirManifestsWaitForTheNextRun()
      throws Exception {
    // The substrate's store, read under the new doctrine: two manifests under one image, tagged v1
    // and v2, both written moments ago. Neither tag is a calver release, so no belt covers them, and
    // nothing pins them — under the old window they were kept for being fresh, and at P0D that is
    // not a keep. Both go.
    //
    // What does NOT go is a single byte, and the reason is the identity model rather than the
    // window: a manifest under a condemned tag is untagged today and a candidate on the next run, so
    // its rows still hold their blobs while this run reports them released. The dry-run figure runs
    // one run ahead of the store here, which is the documented behaviour and is asserted rather than
    // rediscovered.
    seed();
    LiveBlobCensus.Census taken = census.take();

    GcStrategy.Plan plan = strategy.plan(taken, GcPins.none());

    assertEquals(List.of("alpha:v1", "alpha:v2"), identities(plan.dead()));
    assertTrue(
        plan.dead().stream()
            .allMatch(dead -> OwnArtifactsStrategy.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertEquals(List.of(), plan.kept());
    assertEquals(
        taken.live(OciImagesProfile.KEY).keySet(),
        plan.blobsReleased(),
        "every blob the two tags reached — which is the type's whole live set here");

    GcStrategy.Applied applied = strategy.apply(plan, Graces.nothingYoung());
    assertEquals(2, applied.deleted().size());
    ociManifests.getEntityManager().clear();
    assertEquals(2, ociManifests.count(), "the manifests are next run's candidates, not this one's");
  }

  @Test
  void aDeadTagIsDeletedByTheFirstRunThatFindsIt() throws Exception {
    // qits-1732 for this type: a tag nothing keeps, its manifest and bytes past the identity grace,
    // goes on the run that finds it dead, through the registry's own collectTag.
    seed();

    deletedOnTheRunThatFindsIt(
        strategy,
        "alpha:v1",
        () -> {
          ociTags.getEntityManager().clear();
          return ociTags.findOne("qits", "alpha", "v1").isPresent();
        });
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** The aggregate a run would have read, with one image's shas pinned. */
  private static GcPins pinning(String image, String... shas) {
    return new GcPins(Map.of(image, Set.of(shas)), "", Set.of(), Set.of(), List.of());
  }

  /**
   * The aggregate a run would have read, with these FULL image coordinates named by a Dockerfile on
   * some repository's main.
   */
  private static GcPins referencingImage(String... coordinates) {
    return new GcPins(
        Map.of(), "", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(coordinates), List.of());
  }

  /**
   * The same, for the coordinates qits-workspaces would pull TODAY — with everything else empty, so
   * a keep can only be the launch pin.
   */
  private static GcPins launchingFromWorkspaces(String... coordinates) {
    return new GcPins(
        Map.of(), "", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(coordinates),
        Set.of(), List.of());
  }

  /** The same, for the coordinates qits-projects would pull today. */
  private static GcPins launchingFromProjects(String... coordinates) {
    return new GcPins(
        Map.of(), "", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
        Set.of(coordinates), List.of());
  }

  private void repository() {
    repositoryService.ensure("qits", OciImagesProfile.KEY);
  }

  /** One config blob shared by every manifest in a case, the way a rebuilt image shares its base. */
  private String config() throws IOException {
    return store(filled(CONFIG, (byte) 1));
  }

  /** A distinct content blob of the given size, returned by digest. */
  private String layer(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  private String image(String imageName, String config, int layerSize) throws IOException {
    return image(imageName, config, layerSize, Instant.now());
  }

  /** A real image manifest, stored as bytes and given its {@code oci_manifest} row. */
  private String image(String imageName, String config, int layerSize, Instant createdAt)
      throws IOException {
    byte[] document = imageManifest(config, Map.of(layer(layerSize), (long) layerSize));
    String digest = store(document);
    manifestRow(imageName, digest, document.length, OciMediaTypes.OCI_MANIFEST_V1, createdAt);
    return digest;
  }

  private void manifestRow(
      String imageName, String digest, long size, String mediaType, Instant createdAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciManifest row = new OciManifest();
              row.repository = "qits";
              row.imageName = imageName;
              row.digest = digest;
              row.mediaType = mediaType;
              row.size = size;
              row.createdAt = createdAt;
              ociManifests.persist(row);
            });
  }

  private void tag(String imageName, String name, String digest, Instant updatedAt) {
    tag(imageName, name, digest, updatedAt, null);
  }

  /** A tag row with both of V9's timestamps under the case's control. */
  private void tag(
      String imageName, String name, String digest, Instant updatedAt, Instant accessedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OciTag row = new OciTag();
              row.repository = "qits";
              row.imageName = imageName;
              row.tag = name;
              row.manifestDigest = digest;
              row.updatedAt = updatedAt;
              row.accessedAt = accessedAt;
              ociTags.persist(row);
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
