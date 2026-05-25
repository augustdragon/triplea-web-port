package org.triplea.web.server.map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The bits of a map's {@code map.properties} the web client needs to render: image dimensions and
 * each player's display color. Colors are 6-digit hex (no leading {@code #}), as stored by the
 * engine (e.g. {@code color.Japanese=e6b937}).
 */
public record MapProperties(int mapWidth, int mapHeight, Map<String, String> playerColors) {
  private static final String COLOR_PREFIX = "color.";

  /** Reads {@code map.properties} from the map folder; returns empty defaults if it is absent. */
  public static MapProperties readFrom(final Path mapFolder) throws IOException {
    final Path file = mapFolder.resolve("map.properties");
    if (!Files.exists(file)) {
      return new MapProperties(0, 0, Map.of());
    }

    final Properties props = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      props.load(in);
    }

    final Map<String, String> playerColors = new LinkedHashMap<>();
    for (final String key : props.stringPropertyNames()) {
      if (key.startsWith(COLOR_PREFIX)) {
        playerColors.put(key.substring(COLOR_PREFIX.length()), props.getProperty(key).trim());
      }
    }
    return new MapProperties(
        parseInt(props.getProperty("map.width")),
        parseInt(props.getProperty("map.height")),
        playerColors);
  }

  private static int parseInt(final String value) {
    try {
      return value == null ? 0 : Integer.parseInt(value.trim());
    } catch (final NumberFormatException e) {
      return 0;
    }
  }
}
