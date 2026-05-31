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
import java.util.HashMap;
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

    // Polygons in polygons.txt that the game data doesn't recognize as territories are handled two
    // ways (below): a mislabeled duplicate that shares a real territory's center ("Suiyuyan" over the
    // Chinese-owned "Suiyuan") is dropped, while standalone decoration (the corner "Box1".."Box3")
    // is folded into the nearest land territory as one filler rectangle so it reads as part of that
    // territory rather than leaving a hole. The geometry-only path (no game data) keeps everything.
    final Set<String> realNames = realTerritoryNames(polygonsByTerritory.keySet(), gameData);
    final Set<String> landNames = landTerritoryNames(realNames, gameData);
    final Map<String, int[]> cornerFill =
        gameData == null
            ? Map.of()
            : cornerFillBounds(
                polygonsByTerritory.keySet(),
                polygonsByTerritory,
                centersByTerritory,
                realNames,
                landNames);

    final List<TerritoryGeometry> territories = new ArrayList<>();
    for (final var entry : polygonsByTerritory.entrySet()) {
      final String name = entry.getKey();
      // Skip non-territory polygons as standalone regions: their shapes are either dropped or merged
      // into a neighbor as corner fill (see above). Keep everything when there's no game data.
      if (gameData != null && !realNames.contains(name)) {
        continue;
      }
      final List<List<XyPoint>> polygons = new ArrayList<>();
      for (final Polygon polygon : entry.getValue()) {
        polygons.add(toPoints(polygon));
      }
      // Fold any decoration nearest this territory into it as a single filler rectangle.
      final int[] fill = cornerFill.get(name);
      if (fill != null) {
        polygons.add(rectangle(fill));
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

  /** Real, non-water territory names — the candidate targets for folding in stray decoration. */
  private static Set<String> landTerritoryNames(
      final Set<String> realNames, @Nullable final GameData gameData) {
    if (gameData == null) {
      return Set.of();
    }
    final Set<String> land = new HashSet<>();
    for (final String name : realNames) {
      final Territory territory = gameData.getMap().getTerritoryOrNull(name);
      if (territory != null && !territory.isWater()) {
        land.add(name);
      }
    }
    return land;
  }

  /**
   * For each land territory that some stray decoration polygon is nearest to, the bounding box
   * {@code [minX, minY, maxX, maxY]} of all such polygons — a single filler rectangle to fold into
   * that territory so the decoration reads as part of it instead of leaving a hole. A polygon that
   * merely duplicates a real territory (sharing its center, e.g. "Suiyuyan" over "Suiyuan") is
   * excluded: it's dropped, not filled. Pure function of its inputs, so it's unit-tested without a
   * full game-data fixture.
   */
  static Map<String, int[]> cornerFillBounds(
      final Set<String> polygonNames,
      final Map<String, List<Polygon>> polygonsByName,
      final Map<String, Point> centersByName,
      final Set<String> realNames,
      final Set<String> landNames) {
    final Set<Point> realCenters = new HashSet<>();
    for (final String name : realNames) {
      final Point center = centersByName.get(name);
      if (center != null) {
        realCenters.add(center);
      }
    }
    final Map<String, Point> landCenters = new HashMap<>();
    for (final String name : landNames) {
      final Point center = centersByName.get(name);
      if (center != null) {
        landCenters.put(name, center);
      }
    }

    final Map<String, int[]> bounds = new HashMap<>();
    for (final String name : polygonNames) {
      if (realNames.contains(name)) {
        continue; // a real territory, kept as itself
      }
      final Point center = centersByName.get(name);
      if (center == null || realCenters.contains(center)) {
        continue; // a duplicate overlay (or unplaceable) — dropped, not filled
      }
      final String target = nearestByCenter(center, landCenters);
      if (target == null) {
        continue; // nowhere to attach — dropped
      }
      final int[] box =
          bounds.computeIfAbsent(
              target,
              k ->
                  new int[] {
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
                  });
      for (final Polygon polygon : polygonsByName.getOrDefault(name, List.of())) {
        for (int i = 0; i < polygon.npoints; i++) {
          box[0] = Math.min(box[0], polygon.xpoints[i]);
          box[1] = Math.min(box[1], polygon.ypoints[i]);
          box[2] = Math.max(box[2], polygon.xpoints[i]);
          box[3] = Math.max(box[3], polygon.ypoints[i]);
        }
      }
    }
    return bounds;
  }

  /** Name of the entry in {@code centers} whose point is nearest {@code from}, or null if none. */
  private static String nearestByCenter(final Point from, final Map<String, Point> centers) {
    String best = null;
    long bestDistanceSq = Long.MAX_VALUE;
    for (final var entry : centers.entrySet()) {
      final long dx = (long) from.x - entry.getValue().x;
      final long dy = (long) from.y - entry.getValue().y;
      final long distanceSq = dx * dx + dy * dy;
      if (distanceSq < bestDistanceSq) {
        bestDistanceSq = distanceSq;
        best = entry.getKey();
      }
    }
    return best;
  }

  /** An axis-aligned rectangle ring from bounds {@code [minX, minY, maxX, maxY]}. */
  private static List<XyPoint> rectangle(final int[] bounds) {
    return List.of(
        new XyPoint(bounds[0], bounds[1]),
        new XyPoint(bounds[2], bounds[1]),
        new XyPoint(bounds[2], bounds[3]),
        new XyPoint(bounds[0], bounds[3]));
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
