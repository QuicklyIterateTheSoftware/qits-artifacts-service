package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.artifacts.control.DocsProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcIdentity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The docs adapter's facts, now that two kinds of version share one site: calver rows from the
 * release train are <b>releases</b> and spend belt slots; bare-sha rows from the per-commit
 * userflow pipelines are working artifacts that live on the access window alone. The lexical-hex
 * trap — "oldest" meaning "smallest sha string" — is the specific bug this suite pins shut.
 */
@QuarkusTest
class DocsGcAdapterTest extends GcFixture {

  /** The configured window for this type — zero since 2026-09-05, so nothing is kept by age. */
  private static final Duration WINDOW = Duration.ZERO;

  private static final String DOCS_REPO = "docs";
  private static final String SITE = "@userflows/qits-githost";

  @Inject DocsGcStrategy strategy;
  @Inject DocsGcAdapter adapter;

  @Test
  void aCalverRowIsAReleaseAndAShaRowIsNot() throws Exception {
    repository();
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(10), null, Map.of(), blob(11));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(10), null, Map.of(), blob(12));

    List<GcCandidate> candidates = adapter.enumerate();
    assertTrue(released(candidates, SITE + "@2026.801.30"), "a calver version is a release");
    assertFalse(released(candidates, SITE + "@" + "a".repeat(40)), "a sha version is not");
  }

  @Test
  void theBeltKeepsCalverReleasesAndAShaBundleHasNoKeepLeftAtAll() throws Exception {
    // Three calver releases: the newest two hold their belt slots forever, whatever their age, and
    // the third goes. Two sha bundles from the per-commit pipelines: no slot for either, and since
    // 2026-09-05 no window for either — the one published yesterday goes with the one published a
    // hundred days ago.
    //
    // That is the intended collection rather than a loss: a userflows bundle for a commit is read by
    // whoever reviews that run, on the day of the run, and the site's releases are what anybody
    // reads afterwards. What buys a bundle time to be read at all is the six-hour grace on its
    // bytes, which withholds the version whole.
    repository();
    String doomedRelease = blob(21);
    String doomedSha = blob(22);
    backdate(doomedRelease, Duration.ofDays(30));
    backdate(doomedSha, Duration.ofDays(30));
    docsSite(DOCS_REPO, SITE, "2026.601.10", daysAgo(400), null, Map.of(), doomedRelease);
    docsSite(DOCS_REPO, SITE, "2026.701.20", daysAgo(300), null, Map.of(), blob(23));
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(200), null, Map.of(), blob(24));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(100), null, Map.of(), doomedSha);
    docsSite(DOCS_REPO, SITE, "b".repeat(40), daysAgo(1), null, Map.of(), blob(25));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(
        Set.of(
            SITE + "@2026.601.10",
            SITE + "@" + "a".repeat(40),
            SITE + "@" + "b".repeat(40)),
        Set.copyOf(identities(plan.dead())));
    assertTrue(
        plan.dead().stream()
            .allMatch(dead -> OwnArtifactsStrategy.deadUnaccessed(WINDOW).equals(dead.rule())));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.701.20"));
    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.801.30"));
    assertEquals(2, plan.kept().size(), "the belt is the whole keep-set of this type");
    assertFalse(
        identities(plan.kept()).contains(SITE + "@" + "b".repeat(40)),
        "a sha bundle holds no belt slot, and youth is not a slot either");
  }

  @Test
  void versionAgeIsNumericForCalverAndAccessTimeForShasNeverLexical() throws Exception {
    // 2026.101.x sorts lexically BELOW 2026.9.x; numerically month 9 is older than month 101's
    // (i.e. the lexical trap inverts the calver order). And two shas order by when they were last
    // wanted, not by hex — 'z…' published first must rank older than 'a…' published later.
    repository();
    docsSite(DOCS_REPO, SITE, "2026.9.10", daysAgo(40), null, Map.of(), blob(31));
    docsSite(DOCS_REPO, SITE, "2026.101.10", daysAgo(30), null, Map.of(), blob(32));
    docsSite(DOCS_REPO, SITE, "z".repeat(40), daysAgo(20), null, Map.of(), blob(33));
    docsSite(DOCS_REPO, SITE, "a".repeat(40), daysAgo(10), null, Map.of(), blob(34));

    Comparator<GcCandidate> byAge = adapter.byAge();
    List<String> oldestFirst =
        adapter.enumerate().stream().sorted(byAge).map(GcCandidate::identity).toList();

    assertEquals(
        List.of(
            SITE + "@" + "z".repeat(40), // oldest access, and shas rank below every calver
            SITE + "@" + "a".repeat(40),
            SITE + "@2026.9.10", // numerically older than 101, lexically "newer"
            SITE + "@2026.101.10"),
        oldestFirst);
  }

  @Test
  void twoSitesDoNotSpendEachOthersBeltSlots() throws Exception {
    // The existing per-site group doctrine, pinned for docs now that it matters more: a site with
    // one old release keeps it as a release although the OTHER site published three since.
    repository();
    String otherSite = "@userflows/qits-artifacts";
    docsSite(DOCS_REPO, SITE, "2026.601.10", daysAgo(400), null, Map.of(), blob(41));
    docsSite(DOCS_REPO, otherSite, "2026.701.20", daysAgo(300), null, Map.of(), blob(42));
    docsSite(DOCS_REPO, otherSite, "2026.801.30", daysAgo(200), null, Map.of(), blob(43));
    docsSite(DOCS_REPO, otherSite, "2026.815.40", daysAgo(150), null, Map.of(), blob(44));

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());

    assertEquals(OwnArtifactsStrategy.KEPT_RELEASE, ruleFor(plan.kept(), SITE + "@2026.601.10"));
    assertEquals(
        List.of(otherSite + "@2026.701.20"),
        identities(plan.dead()),
        "only the other site's third-newest release dies");
  }

  @Test
  void changelogSitesAreNeverOfferedAsCandidates() throws Exception {
    // @changelog/x (qits-893) is kept forever for now (owner, 2026-10-04): enumerate must skip
    // every one of its rows outright rather than offer it to the belt. @apidocs/x is an ordinary
    // docs site sitting right beside it and enumerates normally, so all five of its released
    // versions must still appear.
    repository();
    String changelogSite = DocsGcAdapter.CHANGELOG_SCOPE + "x";
    String apidocsSite = "@apidocs/x";
    for (int i = 0; i < 5; i++) {
      String version = "2026.101." + (10 + i);
      docsSite(DOCS_REPO, changelogSite, version, daysAgo(10), null, Map.of(), blob(60 + i));
      docsSite(DOCS_REPO, apidocsSite, version, daysAgo(10), null, Map.of(), blob(70 + i));
    }

    List<GcCandidate> candidates = adapter.enumerate();

    assertEquals(5, candidates.size(), "the changelog site's five rows must not be enumerated");
    assertTrue(
        candidates.stream()
            .allMatch(candidate -> candidate.identity().startsWith(apidocsSite + DocsGcAdapter.AT)),
        "every surviving candidate is @apidocs/x");
    assertTrue(
        candidates.stream().noneMatch(candidate -> candidate.identity().startsWith(changelogSite)),
        "none of @changelog/x's rows are offered");
  }

  @Test
  void aCollectedShaVersionTakesItsMetadataWithIt() throws Exception {
    // The V2 cascade, driven through the real funnel: collecting the version removes its
    // docs_site_metadata rows with its files — no orphaned branch facts about a bundle that no
    // longer lists itself.
    repository();
    String doomed = blob(51);
    backdate(doomed, Duration.ofDays(30));
    docsSite(
        DOCS_REPO,
        SITE,
        "a".repeat(40),
        daysAgo(200),
        null,
        Map.of("git.branch.name", "feature/x", "git.commit.hash", "a".repeat(40)),
        doomed);
    docsSite(DOCS_REPO, SITE, "2026.801.30", daysAgo(10), null, Map.of(), blob(52));
    assertEquals(2, metadataRows());

    GcStrategy.Plan plan = strategy.plan(census.take(), GcPins.none());
    GcStrategy.Applied applied = strategy.apply(plan, blobId -> false);

    assertEquals(List.of(SITE + "@" + "a".repeat(40)), identities(applied.deleted()));
    assertEquals(List.of(), applied.errors());
    assertEquals(0, metadataRows(), "fk_docs_site_metadata_site cascaded with the version");
  }

  // --- fixture ---------------------------------------------------------------------------------

  private void repository() {
    repositoryService.ensure(DOCS_REPO, DocsProfile.KEY);
  }

  private String blob(int size) throws IOException {
    return store(filled(size, (byte) (size % 251)));
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static List<String> identities(List<GcIdentity> identities) {
    return identities.stream().map(GcIdentity::identity).toList();
  }

  private static String ruleFor(List<GcIdentity> kept, String identity) {
    return kept.stream()
        .filter(entry -> entry.identity().equals(identity))
        .map(GcIdentity::rule)
        .findFirst()
        .orElseThrow(() -> new AssertionError("not kept at all: " + identity));
  }

  private static boolean released(List<GcCandidate> candidates, String identity) {
    return candidates.stream()
        .filter(candidate -> candidate.identity().equals(identity))
        .findFirst()
        .orElseThrow(() -> new AssertionError("not enumerated: " + identity))
        .released();
  }

  private int metadataRows() throws Exception {
    try (Connection connection = blobs.getConnection();
        Statement statement = connection.createStatement();
        ResultSet counted =
            statement.executeQuery("select count(*) from docs_site_metadata")) {
      counted.next();
      return counted.getInt(1);
    }
  }
}
