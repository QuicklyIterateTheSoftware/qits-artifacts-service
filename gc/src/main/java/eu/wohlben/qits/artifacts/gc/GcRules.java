package eu.wohlben.qits.artifacts.gc;

import eu.wohlben.qits.blobstore.entity.RepositoryTypeProfile;
import eu.wohlben.qits.artifacts.gc.dto.GcTypeConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The configured policy, read back as sentences — the report's configuration echo.
 *
 * <p>The engine owns its own sentence ({@link OwnArtifactsStrategy#rule}), so the text a reviewer
 * reads is written beside the code that acts on it and cannot drift from it. This class only walks
 * the types and asks.
 *
 * <p>A type whose configuration is missing or unusable is reported as such rather than skipped: a
 * type absent from the echo would read as "no policy", which is the one thing the echo exists to
 * make impossible to mistake.
 */
final class GcRules {

  static final String EXCLUDED =
      "excluded: no engine is configured for this type, so nothing of it is ever deleted. A"
          + " decision, not a gap — the rule it will eventually get is named in its strategy class.";

  /** The wire spelling of {@link GcPolicy#EXCLUDED}, as the echo and the summary both write it. */
  static final String EXCLUDED_STRATEGY = "excluded";

  /**
   * What an excluded type's own report line leads with, ahead of whatever its strategy says.
   *
   * <p>The configuration echo already carries {@link #EXCLUDED}, and that is not enough: a reviewer
   * reads a type's own entry to find out what happened to it, and {@code dead: []} beside a claimed
   * strategy reads as "the rule ran and found nothing". Those are different facts, and only one of
   * them is a decision. Stated on the line where the absence is, which is the honesty-about-absence
   * rule applied to the settlement's two excluded types.
   */
  static final String EXCLUDED_NOTE =
      "excluded by configuration: no engine is configured for this type, so nothing of it is ever"
          + " deleted — a decision, not a gap. ";

  private GcRules() {}

  /**
   * A type's report note, with the excluded line in front of it when it is one of the excluded
   * types. Used by the plan and by the sweep receipt, so both say the same thing about absence.
   */
  static String note(GcTypeConfig config, String type, String strategyNote) {
    GcPolicy policy;
    try {
      policy = config.of(type).strategy();
    } catch (RuntimeException unconfigured) {
      // Unconfigured is a louder fault than excluded and the echo already reports it in full; the
      // type's own note is left as its strategy wrote it rather than overwritten with a guess.
      return strategyNote;
    }
    if (policy != GcPolicy.EXCLUDED) {
      return strategyNote;
    }
    return strategyNote == null ? EXCLUDED_NOTE.trim() : EXCLUDED_NOTE + strategyNote;
  }

  /** One line per registered repository type, in the registry's own (sorted) order. */
  static List<GcTypeConfiguration> echo(GcTypeConfig config, Collection<String> types) {
    List<GcTypeConfiguration> lines = new ArrayList<>();
    for (String type : types) {
      lines.add(line(config, type));
    }
    return List.copyOf(lines);
  }

  /** One type's line of that echo. */
  static GcTypeConfiguration line(GcTypeConfig config, String type) {
    String wireName = RepositoryTypeProfile.wireNameOf(type);
    GcPolicy policy;
    try {
      policy = config.of(type).strategy();
    } catch (RuntimeException unconfigured) {
      return new GcTypeConfiguration(wireName, null, null, unconfigured.getMessage());
    }
    if (policy == GcPolicy.EXCLUDED) {
      return new GcTypeConfiguration(wireName, EXCLUDED_STRATEGY, null, EXCLUDED);
    }
    Duration window;
    try {
      window = config.requireWindow(type);
    } catch (RuntimeException unconfigured) {
      return new GcTypeConfiguration(wireName, name(policy), null, unconfigured.getMessage());
    }
    return new GcTypeConfiguration(
        wireName, name(policy), GcPlanner.iso(window), OwnArtifactsStrategy.rule(window));
  }

  /** The wire spelling of a policy: what a deployment writes in its configuration. */
  private static String name(GcPolicy policy) {
    return policy.name().toLowerCase(Locale.ROOT);
  }
}
