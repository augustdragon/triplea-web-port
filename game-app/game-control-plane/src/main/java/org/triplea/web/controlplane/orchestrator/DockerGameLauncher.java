package org.triplea.web.controlplane.orchestrator;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * Spawns one Docker container per game via the {@code docker} CLI. Each container publishes its
 * WebSocket port to the host, reaches the control plane back through the host gateway (to report
 * turn events), mounts the map folder read-only at its own path (so the game XML path is valid
 * inside the container), and shares a save volume so autosaves survive a reap. The container id is
 * the reap handle.
 */
@Slf4j
public final class DockerGameLauncher implements GameLauncher {

  private final String image;
  private final String saveVolume;
  private final String callbackUrl;
  private final String wsHost;
  private final String gameToken;

  public DockerGameLauncher(final ControlPlaneConfig config) {
    this.image = config.gameImage();
    this.saveVolume = config.saveVolume();
    this.callbackUrl = config.gameCallbackUrl();
    this.wsHost = config.gameWsHost();
    this.gameToken = config.gameToken();
  }

  @Override
  public LaunchedGame launch(final LaunchSpec spec) {
    final int port = freePort();
    final Path mapDir = mapDirOf(Path.of(spec.mapXml()));
    final List<String> cmd =
        new ArrayList<>(
            List.of(
                "docker",
                "run",
                "-d",
                "--name",
                "game-" + spec.gameId(),
                "--add-host",
                "host.docker.internal:host-gateway",
                "-p",
                port + ":" + port,
                "-v",
                mapDir + ":" + mapDir + ":ro",
                "-v",
                saveVolume + ":/root/.triplea-web/saves",
                image,
                spec.mapXml(),
                "--port=" + port,
                "--game-id=" + spec.gameId(),
                "--control-plane-url=" + callbackUrl,
                "--game-token=" + (gameToken == null ? "" : gameToken),
                "--turn-limit-seconds=" + spec.turnLimitSeconds()));
    if (spec.saveRef() != null) {
      cmd.add("--save-ref=" + spec.saveRef());
    }
    final String containerId = runDocker(cmd).trim();
    final String wsEndpoint = "ws://" + wsHost + ":" + port;
    log.info(
        "Spawned game {} as container {} on {}", spec.gameId(), shortId(containerId), wsEndpoint);
    return new LaunchedGame(containerId, wsEndpoint);
  }

  @Override
  public void reap(final String handle) {
    try {
      runDocker(List.of("docker", "rm", "-f", handle));
      log.info("Reaped container {}", shortId(handle));
    } catch (final RuntimeException e) {
      log.warn("Reaping container {} failed: {}", shortId(handle), e.getMessage());
    }
  }

  /** Map XMLs live at {@code <map>/games/<game>.xml}; mount the {@code <map>} folder. */
  private static Path mapDirOf(final Path xml) {
    final Path parent = xml.getParent();
    return parent != null && parent.getParent() != null ? parent.getParent() : parent;
  }

  private static String runDocker(final List<String> cmd) {
    try {
      final Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      final String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      final int code = process.waitFor();
      if (code != 0) {
        throw new IllegalStateException("docker exited " + code + ": " + output.trim());
      }
      return output;
    } catch (final IOException e) {
      throw new IllegalStateException("Could not run docker: " + e.getMessage(), e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted running docker", e);
    }
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (final IOException e) {
      throw new IllegalStateException("No free port available", e);
    }
  }

  private static String shortId(final String id) {
    return id == null ? "?" : id.substring(0, Math.min(12, id.length()));
  }
}
