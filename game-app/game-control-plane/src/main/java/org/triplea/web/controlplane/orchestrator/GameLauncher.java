package org.triplea.web.controlplane.orchestrator;

import org.jetbrains.annotations.Nullable;

/**
 * Seam for spawning and reaping a game's container. The control plane depends only on this
 * interface; {@link DockerGameLauncher} is the implementation. Introduced as a seam in M1 so the
 * lobby and routing could be built and verified before Docker; M5 makes it real.
 */
public interface GameLauncher {

  /**
   * What to launch: the game id, the map XML path, an optional save to resume from, and the host's
   * per-turn timer in seconds (0 = unlimited).
   */
  record LaunchSpec(String gameId, String mapXml, @Nullable String saveRef, int turnLimitSeconds) {}

  /** A launched game: a handle for reaping (the container id) and where browsers reach it. */
  record LaunchedGame(String handle, String wsEndpoint) {}

  /** Spawn a container for the game and return its handle + WebSocket endpoint. */
  LaunchedGame launch(LaunchSpec spec);

  /** Stop and remove a previously launched container. */
  void reap(String handle);
}
