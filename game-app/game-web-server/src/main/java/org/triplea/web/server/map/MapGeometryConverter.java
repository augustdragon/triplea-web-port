package org.triplea.web.server.map;

import com.google.gson.Gson;
import java.awt.Point;
import java.awt.Polygon;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
   * Reads {@code polygons.txt} (required) and {@code centers.txt} (optional) from the given map
   * folder.
   *
   * @throws IOException if {@code polygons.txt} is missing or malformed.
   */
  public static MapGeometry fromMapFolder(final Path mapFolder) throws IOException {
    final Map<String, List<Polygon>> polygonsByTerritory =
        PointFileReaderWriter.readOneToManyPolygons(mapFolder.resolve("polygons.txt"));

    final Path centersFile = mapFolder.resolve("centers.txt");
    final Map<String, Point> centersByTerritory =
        Files.exists(centersFile) ? PointFileReaderWriter.readOneToOne(centersFile) : Map.of();

    final List<TerritoryGeometry> territories = new ArrayList<>();
    for (final var entry : polygonsByTerritory.entrySet()) {
      final String name = entry.getKey();
      final List<List<XyPoint>> polygons = new ArrayList<>();
      for (final Polygon polygon : entry.getValue()) {
        polygons.add(toPoints(polygon));
      }
      final Point center = centersByTerritory.get(name);
      territories.add(
          new TerritoryGeometry(
              name, polygons, center == null ? null : new XyPoint(center.x, center.y)));
    }
    return new MapGeometry(territories);
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
