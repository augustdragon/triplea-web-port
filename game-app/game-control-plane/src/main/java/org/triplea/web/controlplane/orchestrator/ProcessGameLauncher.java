package org.triplea.web.controlplane.orchestrator;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

/**
 * Launches a game as a local child-process JVM — the development default chosen in the build plan's
 * spawn fork (Docker is swapped in at M5 behind {@link GameLauncher}). The actual child-process
 * command is wired in M4a, once {@code :game-web-server} accepts the injected {@code --game-id} /
 * {@code --port} / {@code --save-ref} arguments. Until then it records the launch intent and
 * returns a placeholder endpoint, so the lobby launch flow works end-to-end while the real spawn
 * lands.
 */
@Slf4j
public final class ProcessGameLauncher implements GameLauncher {

  @Override
  public String launch(final String gameId, @Nullable final String saveRef) {
    // TODO(M4a): spawn the parameterized :game-web-server child process and return its real WS URL.
    final String endpoint = "ws://pending/" + gameId;
    log.info(
        "Launch requested for game {} (saveRef={}); real child-process spawn lands in M4a —"
            + " placeholder endpoint {}",
        gameId,
        saveRef,
        endpoint);
    return endpoint;
  }
}
