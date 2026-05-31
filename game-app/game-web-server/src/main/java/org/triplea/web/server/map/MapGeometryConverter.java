package org.triplea.web.server.map;

import com.google.gson.Gson;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.awt.Point;
import java.awt.Polygon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.triplea.util.PointFileReaderWriter;

/**
 * Converts a TripleA map folder's geometry files into a {@link MapGeometry} model (and JSON) for
 * the web client. Reuses the engine's {@link PointFileReaderWriter} so parsing matches the engine
 * exactly, rather than re-implementing the {@code polygons.txt}/{@code centers.txt} formats.
 */
public final class MapGeometryConverter {
  private static final Gson GSON = new Gson();

  private MapGeometryConverter() {}

  /**
   * Reads geometry and {@code map.properties} from the given map folder. The result has no game
   * data (connections or owners).
   *
   * @throws IOException if {@code polygons.txt} is missing or malformed.
   */
  public static MapGeometry fromMapFolder(final Path mapFolder) throws IOException {
    final MapProperties properties = MapProperties.readFrom(mapFolder);
    return new MapGeometry(
        properties.mapWidth(),
        properties.mapHeight(),
        properties.playerColors(),
        readTerritories(mapFolder, null),
        null,
        null);
  }

  /**
   * Reads geometry and {@code map.properties} from {@code mapFolder} and merges the territory
   * adjacency graph and initial ownership parsed from {@code gameXml}, producing the complete map
   * data the web client needs for the static render.
   *
   * @throws IOException if {@code polygons.txt} is missing or malformed.
   * @throws IllegalStateException if {@code gameXml} cannot be parsed.
   */
  public static MapGeometry fromMapFolderAndGame(final Path mapFolder, final Path gameXml)
      throws IOException {
    final MapProperties properties = MapProperties.readFrom(mapFolder);
    final var gameData = GameDataLoader.load(gameXml);
    return new MapGeometry(
        properties.mapWidth(),
        properties.mapHeight(),
        properties.playerColors(),
        readTerritories(mapFolder, gameData),
        MapConnections.from(gameData),
        GameStateReader.ownersByTerritory(gameData));
  }

  private static List<TerritoryGeometry> readTerritories(
      final Path mapFolder, @Nullable final GameData gameData) throws IOException {
    final Map<String, List<Polygon>> polygonsByTerritory =
        PointFileReaderWriter.readOneToManyPolygons(mapFolder.resolve("polygons.txt"));

    final Path centersFile = mapFolder.resolve("centers.txt");
    final Map<String, Point> centersByTerritory =
        Files.exists(centersFile) ? PointFileReaderWriter.readOneToOne(centersFile) : Map.of();

    // Polygons the game data doesn't recognize as territories — non-territory extras to drop (below).
    final Set<String> realNames = realTerritoryNames(polygonsByTerritory.keySet(), gameData);
    final Set<String> extraNames =
        gameData == null
            ? Set.of()
            : nonTerritoryPolygonNames(polygonsByTerritory.keySet(), realNames);

    final List<TerritoryGeometry> territories = new ArrayList<>();
    for (final var entry : polygonsByTerritory.entrySet()) {
      final String name = entry.getKey();
      // Drop polygons the game data doesn't know as territories — non-territory extras in
      // polygons.txt: a mislabeled duplicate (WW2 Pacific's "Suiyuyan", an identical-shape copy of
      // the Chinese-owned "Suiyuan", which would mask the real territory beneath it) and the
      // decorative corner boxes ("Box1".."Box3"). Both render as stray ownerless neutrals otherwise.
      // The geometry-only path (no game data) can't judge, so extraNames is empty and all are kept.
      if (extraNames.contains(name)) {
        continue;
      }
      final List<List<XyPoint>> polygons = new ArrayList<>();
      for (final Polygon polygon : entry.getValue()) {
        polygons.add(toPoints(polygon));
      }
      final Point center = centersByTerritory.get(name);
      // Semantic attributes come from the game data when available; geometry-only polygons default.
      final Territory territory =
          gameData == null ? null : gameData.getMap().getTerritoryOrNull(name);
      final boolean water = territory != null && territory.isWater();
      final int production = territory == null ? 0 : TerritoryAttachment.getProduction(territory);
      final String capitalOf =
          territory == null
              ? null
              : TerritoryAttachment.get(territory)
                  .flatMap(TerritoryAttachment::getCapital)
                  .orElse(null);
      territories.add(
          new TerritoryGeometry(
              name,
              polygons,
              center == null ? null : new XyPoint(center.x, center.y),
              water,
              production,
              capitalOf));
    }
    return territories;
  }

  /** Which of {@code polygonNames} the game data recognizes as territories (empty if no game). */
  private static Set<String> realTerritoryNames(
      final Set<String> polygonNames, @Nullable final GameData gameData) {
    if (gameData == null) {
      return Set.of();
    }
    final Set<String> names = new HashSet<>();
    for (final String name : polygonNames) {
      if (gameData.getMap().getTerritoryOrNull(name) != null) {
        names.add(name);
      }
    }
    return names;
  }

  /**
   * Polygon names the game data doesn't recognize as territories — non-territory extras in
   * polygons.txt (mislabeled duplicates like "Suiyuyan", and decorative corner boxes "Box1".."Box3")
   * that shouldn't render as map regions. Pure function of its inputs, so it's unit-tested without a
   * full game-data fixture.
   */
  static Set<String> nonTerritoryPolygonNames(
      final Set<String> polygonNames, final Set<String> realNames) {
    final Set<String> extras = new HashSet<>();
    for (final String name : polygonNames) {
      if (!realNames.contains(name)) {
        extras.add(name);
      }
    }
    return extras;
  }

  /** Serializes a {@link MapGeometry} to JSON for delivery to the web client. */
  public static String toJson(final MapGeometry geometry) {
    return GSON.toJson(geometry);
  }

  private static List<XyPoint> toPoints(final Polygon polygon) {
    final List<XyPoint> points = new ArrayList<>(polygon.npoints);
    for (int i = 0; i < polygon.npoints; i++) {
      points.add(new XyPoint(polygon.xpoints[i], polygon.ypoints[i]));
    }
    return points;
  }
}
