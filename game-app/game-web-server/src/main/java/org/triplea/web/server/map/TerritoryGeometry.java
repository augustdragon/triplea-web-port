package org.triplea.web.server.map;

import java.util.List;
import javax.annotation.Nullable;

/**
 * One territory's screen geometry plus the few semantic attributes the web client needs to render
 * and play it: its name, one or more polygons (each a ring of points), an optional center point
 * (anchors unit/label rendering), whether it is a sea zone ({@code water}), its production value,
 * and {@code capitalOf} — the player whose capital it is, or {@code null}. Most territories have a
 * single polygon; some (e.g. ones split by a map edge) have several. The semantic fields are
 * populated only when the converter is given the game XML; geometry-only territories default to
 * non-water / 0 production / no capital.
 */
public record TerritoryGeometry(
    String name,
    List<List<XyPoint>> polygons,
    @Nullable XyPoint center,
    boolean water,
    int production,
    @Nullable String capitalOf) {}
