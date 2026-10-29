package eu.wohlben.qits.artifacts.gc;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * Grace windows a case builds by hand, for the adapter suites that call {@code apply} directly.
 *
 * <p>{@link GcStrategy.GraceWindow} asks two questions since qits-1732 — is this blob young, is this
 * row young — so it stopped being a lambda. These answer the row question "no" on purpose: a case
 * handing one in is about which <em>blob</em> is young, and its rows carry stamps chosen for the
 * rule under test rather than for the window. The row half is exercised through the executor, which
 * builds the real window from the run's clock.
 */
final class Graces {

  private Graces() {}

  /** Nothing is young: every blob and every row is past the window. */
  static GcStrategy.GraceWindow nothingYoung() {
    return blobsOnly(blobId -> false);
  }

  /** Exactly the blobs the predicate names are young; no row is. */
  static GcStrategy.GraceWindow blobsOnly(Predicate<String> young) {
    return new GcStrategy.GraceWindow() {
      @Override
      public boolean withinGrace(String blobId) {
        return young.test(blobId);
      }

      @Override
      public boolean withinGrace(Instant stampedAt) {
        return false;
      }
    };
  }
}
