package org.triplea.web.controlplane.orchestrator;

import org.jetbrains.annotations.Nullable;

/**
 * Seam for starting a game's JVM and telling browsers where to reach it. The control plane depends
 * only on this interface; the implementation varies by environment: {@link ProcessGameLauncher} (a
 * local child process, the development default) now, and a Docker-API launcher (one container per
 * game) in M5. Introduced early so the lobby and routing (M3+) can be built and verified against a
 * stable seam before Docker enters the picture.
 */
public interface GameLauncher {

  /**
   * Start a game for {@code gameId}, resuming from {@code saveRef} when non-null (lazy
   * rehydration), and return the WebSocket endpoint (e.g. {@code ws://host:port}) the browser
   * should connect to.
   */
  String launch(String gameId, @Nullable String saveRef);
}
