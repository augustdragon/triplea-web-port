package org.triplea.web.server.map;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Map data for the web client's static render. Geometry (polygons, centers) and the player colors /
 * image dimensions come from the map folder's files; coordinates are pixels in the map image's
 * space (origin top-left). The optional {@code connections} adjacency graph and {@code
 * initialOwners} snapshot are semantic data from the parsed game XML, included only when a game is
 * supplied to the converter. {@code initialOwners} is a static first-render convenience; Phase 2
 * replaces it with live state pushed over WebSocket.
 */
public record MapGeometry(
    int mapWidth,
    int mapHeight,
    Map<String, String> playerColors,
    List<TerritoryGeometry> territories,
    @Nullable Map<String, List<String>> connections,
    @Nullable Map<String, String> initialOwners) {}
