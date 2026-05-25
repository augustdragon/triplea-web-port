package org.triplea.web.server.map;

import java.util.List;

/**
 * Plain geometry of a map, ready to serialize to JSON for the web client. All coordinates are
 * pixels in the map image's coordinate space (origin top-left). Territory connections are not
 * included here — those are semantic data read from the parsed game XML, not the geometry files.
 */
public record MapGeometry(List<TerritoryGeometry> territories) {}
