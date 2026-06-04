package org.triplea.web.controlplane.orchestrator;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * Spawns one game as a plain child JVM (no Docker) — the Docker-free single-VM deploy. Each child
 * is the installed {@code game-web-server} launcher, bound to a free localhost port, capped with
 * {@code -Xmx} (an uncapped JVM would default its max heap to ~25% of host RAM), reaching the
 * control plane back on localhost and sharing the host save dir directly (no mounts). The reap
 * handle is the child's PID, so reaping survives a control-plane restart via {@link ProcessHandle}.
 *
 * <p>The internal {@code wsEndpoint} ({@code ws://localhost:<port>}) is the upstream the control
 * plane's per-game WS proxy connects to; it is never handed to the browser (which dials a
 * same-origin {@code /game/{id}/ws} path instead).
 */
@Slf4j
public final class ProcessGameLauncher implements GameLauncher {

  private final String gameBin;
  private final String gameHeap;
  private final String gameToken;
  private final String callbackUrl;

  public ProcessGameLauncher(final ControlPlaneConfig config) {
    this.gameBin = config.gameBin();
    this.gameHeap = config.gameHeap();
    this.gameToken = config.gameToken();
    // A child on the same host reaches the control plane on localhost (no host-gateway needed).
    this.callbackUrl = "http://localhost:" + config.httpPort();
  }

  @Override
  public LaunchedGame launch(final LaunchSpec spec) {
    final int port = freePort();
    final List<String> cmd =
        new ArrayList<>(
            List.of(
                gameBin,
                spec.mapXml(),
                "--port=" + port,
                "--game-id=" + spec.gameId(),
                "--control-plane-url=" + callbackUrl,
                "--game-token=" + (gameToken == null ? "" : gameToken),
                "--turn-limit-seconds=" + spec.turnLimitSeconds()));
    if (spec.saveRef() != null) {
      cmd.add("--save-ref=" + spec.saveRef());
    }
    try {
      final ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
      pb.environment().put("GAME_WEB_SERVER_OPTS", gameHeap); // installDist bin honors this
      final Process process = pb.start();
      final String handle = Long.toString(process.pid());
      final String wsEndpoint = "ws://localhost:" + port;
      log.info("Spawned game {} as pid {} on {}", spec.gameId(), handle, wsEndpoint);
      return new LaunchedGame(handle, wsEndpoint);
    } catch (final IOException e) {
      throw new IllegalStateException("Could not spawn game process: " + e.getMessage(), e);
    }
  }

  @Override
  public void reap(final String handle) {
    try {
      ProcessHandle.of(Long.parseLong(handle))
          .ifPresent(
              h -> {
                h.destroyForcibly();
                log.info("Reaped game process pid {}", handle);
              });
    } catch (final NumberFormatException e) {
      log.warn("Cannot reap game process — bad pid handle '{}'", handle);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new IllegalStateException("No free port available", e);
    }
  }
}
