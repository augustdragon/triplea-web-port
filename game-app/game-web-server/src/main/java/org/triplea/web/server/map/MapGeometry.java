package org.triplea.web.server.map;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Map data for the web client. Geometry (polygons, centers) comes from the map folder's text files;
 * coordinates are pixels in the map image's space (origin top-left). The optional {@code
 * connections} adjacency graph is semantic data from the parsed game XML, included only when a game
 * is supplied to the converter.
 */
public record MapGeometry(
    List<TerritoryGeometry> territories, @Nullable Map<String, List<String>> connections) {}
