package org.triplea.web.server.map;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.gameparser.GameParser;
import java.nio.file.Path;

/**
 * Loads a map's game XML into the engine's {@link GameData} using the engine's own parser. This is
 * the seam where the web port drives the unchanged engine: parsing yields the full rules graph
 * (territories, connections, players, units, attachments) that the rest of the port reads from.
 */
public final class GameDataLoader {
  private GameDataLoader() {}

  /**
   * Parses the game XML at {@code gameXml} into a {@link GameData}.
   *
   * @throws IllegalStateException if the file cannot be parsed (the engine logs the cause).
   */
  public static GameData load(final Path gameXml) {
    return GameParser.parse(gameXml, false)
        .orElseThrow(() -> new IllegalStateException("Failed to parse game XML: " + gameXml));
  }
}
