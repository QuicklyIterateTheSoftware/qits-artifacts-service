package eu.wohlben.qits.stories.support;

import eu.wohlben.qits.userflows.Flow;
import java.util.Map;

/** A signed-in operator's session, played for a browser story. */
public final class StoryBrowser {

  /** The signed-in operator every explorer story browses as. */
  public static final String OPERATOR = "story-operator";

  private StoryBrowser() {}

  /**
   * Present a signed-in operator's bearer on the browser context, <b>before the first
   * navigate</b>.
   *
   * <p>Every JSON read the SPA makes is {@code @RolesAllowed} on the platform roles, and the
   * packaged process runs {@code LaunchMode.NORMAL}, where no {@code %test} synthetic identity
   * answers for a caller that names none. Forward-auth's {@code X-Qits-User} / {@code X-Qits-Roles}
   * pair, which qits-gateway used to inject for a signed-in operator, leaves qits-auth-core with
   * qits-1338 (epic qits-543, qits-1358), so the browser presents what a person's session does now:
   * a {@code qits:admin} token minted by the run's mock idp ({@link StoryIdentities}). Without it
   * every fetch is an anonymous 401 and the page renders its error states, which is a screenshot of
   * the harness rather than of the product.
   *
   * <p>{@code Flow.page()} records nothing, and that is right here: this is harness plumbing, not a
   * step in anybody's story.
   */
  public static void asOperator(Flow flow) {
    flow.page().setExtraHTTPHeaders(Map.of("Authorization", StoryIdentities.operator(OPERATOR)));
  }
}
