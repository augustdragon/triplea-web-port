package org.triplea.web.server.game;

import javax.annotation.Nullable;

/** Reporter for standalone dev (no control plane): every call is a no-op. */
final class NoOpGameReporter implements GameReporter {

  @Override
  public void gameStarted() {}

  @Override
  public void turnCommitted(
      final int round, final @Nullable String power, final String phase, final String bytesRef) {}

  @Override
  public void gameFinished(final String reason, final @Nullable String winner) {}

  @Override
  public void seatResigned(final String power) {}

  @Override
  public void turnDeadline(final @Nullable String power, final @Nullable Long deadlineEpoch) {}
}
