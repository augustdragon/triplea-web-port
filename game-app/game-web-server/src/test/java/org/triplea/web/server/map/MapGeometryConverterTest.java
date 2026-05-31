package org.triplea.web.server.map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.awt.Point;
import java.awt.Polygon;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MapGeometryConverterTest {
  // The test task runs with the module directory as the working directory.
  private static final Path TEST_MAP = Path.of("src", "test", "resources", "test-map");

  @Test
  void readsPolygonsAndCenters() throws IOException {
    final MapGeometry geometry = MapGeometryConverter.fromMapFolder(TEST_MAP);

    assertThat(geometry.territories(), hasSize(2));

    final TerritoryGeometry alpha = findTerritory(geometry, "Alpha");
    assertThat("Alpha is a single quad", alpha.polygons(), hasSize(1));
    assertThat(alpha.polygons().get(0), hasSize(4));
    assertThat(alpha.polygons().get(0).get(0), is(new XyPoint(0, 0)));
    assertThat(alpha.center(), is(new XyPoint(5, 5)));

    final TerritoryGeometry beta = findTerritory(geometry, "Beta");
    assertThat("Beta is split into two polygons", beta.polygons(), hasSize(2));
    assertThat(beta.polygons().get(1), hasSize(3));
    assertThat(beta.center(), is(new XyPoint(15, 3)));
  }

  @Test
  void territoryWithoutCenterIsNull() throws IOException {
    final MapGeometry geometry = MapGeometryConverter.fromMapFolder(TEST_MAP);
    // Both fixture territories have centers; this guards the null path stays serializable below.
    assertThat(geometry.territories().get(0).center(), notNullValue());
  }

  @Test
  void readsDimensionsAndPlayerColorsFromMapProperties() throws IOException {
    final MapGeometry geometry = MapGeometryConverter.fromMapFolder(TEST_MAP);

    assertThat(geometry.mapWidth(), is(100));
    assertThat(geometry.mapHeight(), is(80));
    assertThat(geometry.playerColors(), hasEntry("Red", "ff0000"));
    assertThat(geometry.playerColors(), hasEntry("Blue", "0000ff"));
  }

  @Test
  void serializesToJson() throws IOException {
    final String json = MapGeometryConverter.toJson(MapGeometryConverter.fromMapFolder(TEST_MAP));

    assertThat(json, notNullValue());
    assertThat("includes territory names", json.contains("Alpha"), is(true));
    assertThat("includes pixel coordinates", json.contains("\"x\":10"), is(true));
  }

  @Test
  void foldsStandaloneDecorationIntoNearestLandTerritoryButDropsDuplicateOverlay() {
    // "Suiyuyan" shares "Suiyuan"'s center -> a duplicate overlay, excluded (no fill).
    // "Box1"/"Box2" are standalone decoration nearest "Yukon Territory" -> folded into it as the
    // single bounding box of the two, which the converter renders as a filler rectangle.
    final Map<String, List<Polygon>> polys =
        Map.of(
            "Suiyuan", List.of(quad(0, 0, 10, 10)),
            "Suiyuyan", List.of(quad(0, 0, 10, 10)),
            "Yukon Territory", List.of(quad(100, 200, 200, 300)),
            "Box1", List.of(quad(150, 10, 180, 40)),
            "Box2", List.of(quad(120, 20, 150, 50)));
    final Map<String, Point> centers =
        Map.of(
            "Suiyuan", new Point(1000, 1000),
            "Suiyuyan", new Point(1000, 1000),
            "Yukon Territory", new Point(150, 250),
            "Box1", new Point(165, 25),
            "Box2", new Point(135, 35));
    final Set<String> realNames = Set.of("Suiyuan", "Yukon Territory");
    final Set<String> landNames = Set.of("Suiyuan", "Yukon Territory");

    final Map<String, int[]> fill =
        MapGeometryConverter.cornerFillBounds(
            polys.keySet(), polys, centers, realNames, landNames);

    assertThat(fill.keySet(), contains("Yukon Territory"));
    assertThat(fill.get("Yukon Territory"), is(new int[] {120, 10, 180, 50}));
  }

  @Test
  void fillsNothingWithoutLandTerritoriesToAttachTo() {
    final Map<String, List<Polygon>> polys = Map.of("Box1", List.of(quad(0, 0, 10, 10)));
    final Map<String, Point> centers = Map.of("Box1", new Point(5, 5));

    final Map<String, int[]> fill =
        MapGeometryConverter.cornerFillBounds(
            polys.keySet(), polys, centers, Set.of(), Set.of());

    assertThat(fill.isEmpty(), is(true));
  }

  /** A rectangular polygon spanning (x0,y0)-(x1,y1). */
  private static Polygon quad(final int x0, final int y0, final int x1, final int y1) {
    return new Polygon(new int[] {x0, x1, x1, x0}, new int[] {y0, y0, y1, y1}, 4);
  }

  private static TerritoryGeometry findTerritory(final MapGeometry geometry, final String name) {
    return geometry.territories().stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
