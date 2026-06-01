package org.triplea.web.controlplane.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

/**
 * Launches a game as a local child-process JVM — the development default chosen in the build plan's
 * spawn fork (Docker is swapped in at M5 behind {@link GameLauncher}). The full child-process
 * command is wired in M4a, once {@code :game-web-server} accepts the injected {@code --game-id} /
 * {@code --port} / {@code --save-ref} arguments. Until then a call fails loudly rather than
 * returning a fake endpoint, so exercising the seam before it is ready is impossible to miss.
 */
@Slf4j
public final class ProcessGameLauncher implements GameLauncher {

  @Override
  public String launch(final String gameId, @Nullable final String saveRef) {
    log.info("Launch requested for game {} (saveRef={})", gameId, saveRef);
    throw new UnsupportedOperationException(
        "ProcessGameLauncher.launch is wired in M4a (game-container parameterization)");
  }
}
