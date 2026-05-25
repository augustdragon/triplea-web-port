package org.triplea.web.server.map;

import java.util.List;
import javax.annotation.Nullable;

/**
 * One territory's screen geometry: its name, one or more polygons (each a ring of points), and an
 * optional center point used to anchor unit/label rendering. Most territories have a single
 * polygon; some (e.g. ones split by a map edge) have several.
 */
public record TerritoryGeometry(
    String name, List<List<XyPoint>> polygons, @Nullable XyPoint center) {}
