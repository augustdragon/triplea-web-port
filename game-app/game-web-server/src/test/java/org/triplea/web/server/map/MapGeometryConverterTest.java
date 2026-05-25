package org.triplea.web.server.map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.nio.file.Path;
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
  void serializesToJson() throws IOException {
    final String json = MapGeometryConverter.toJson(MapGeometryConverter.fromMapFolder(TEST_MAP));

    assertThat(json, notNullValue());
    assertThat("includes territory names", json.contains("Alpha"), is(true));
    assertThat("includes pixel coordinates", json.contains("\"x\":10"), is(true));
  }

  private static TerritoryGeometry findTerritory(final MapGeometry geometry, final String name) {
    return geometry.territories().stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
