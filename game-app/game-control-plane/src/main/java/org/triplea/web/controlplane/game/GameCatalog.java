package org.triplea.web.controlplane.game;

import games.strategy.engine.data.GameData;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.triplea.web.controlplane.config.ControlPlaneConfig;

/**
 * The set of games the lobby can create tables for, each with its playable powers enumerated from
 * the engine at startup (parsed once, cached). v1 holds the single map configured via {@code
 * CONTROL_PLANE_GAME_XML}; a maps directory / multiple entries can replace this later. A bad or
 * missing path is logged and yields an empty catalog rather than failing startup — the rest of the
 * control plane (auth, health) still runs.
 */
@Slf4j
public final class GameCatalog {

  /**
   * One hostable game: a stable id, its display name, the map XML path, and its playable powers.
   */
  public record AvailableGame(String id, String name, String mapXml, List<String> playablePowers) {}

  private final Map<String, AvailableGame> byId;

  private GameCatalog(final Map<String, AvailableGame> byId) {
    this.byId = byId;
  }

  /** Build the catalog by parsing the configured game XML (if any) for its playable powers. */
  public static GameCatalog fromConfig(final ControlPlaneConfig config) {
    final Map<String, AvailableGame> byId = new LinkedHashMap<>();
    final String configured = config.gameXml();
    if (configured == null || configured.isBlank()) {
      log.warn("CONTROL_PLANE_GAME_XML not set — the lobby has no games to offer");
      return new GameCatalog(byId);
    }
    try {
      final Path xml = Path.of(configured);
      final GameData data = GameXmlReader.parse(xml);
      final List<String> powers = GameXmlReader.playablePowers(data);
      final String name = data.getGameName();
      final String id = slug(name);
      byId.put(id, new AvailableGame(id, name, xml.toString(), powers));
      log.info("Available game '{}' ({}): {} playable powers {}", id, name, powers.size(), powers);
    } catch (final RuntimeException e) {
      log.error("Could not load CONTROL_PLANE_GAME_XML={} — catalog is empty", configured, e);
    }
    return new GameCatalog(byId);
  }

  public Optional<AvailableGame> get(final String id) {
    return Optional.ofNullable(byId.get(id));
  }

  public Collection<AvailableGame> all() {
    return byId.values();
  }

  private static String slug(final String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }
}
