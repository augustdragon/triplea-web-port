package org.triplea.web.server.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point that converts a map folder's geometry files into a {@code geometry.json}
 * for the web client.
 *
 * <p>Usage: {@code GeometryExportCli <mapFolder> <outputJsonFile>} where {@code mapFolder} contains
 * {@code polygons.txt} (and optionally {@code centers.txt}).
 */
public final class GeometryExportCli {
  private GeometryExportCli() {}

  public static void main(final String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Usage: GeometryExportCli <mapFolder> <outputJsonFile>");
      System.exit(2);
      return;
    }
    final Path mapFolder = Path.of(args[0]);
    final Path output = Path.of(args[1]);

    final MapGeometry geometry = MapGeometryConverter.fromMapFolder(mapFolder);
    Files.writeString(output, MapGeometryConverter.toJson(geometry));

    System.out.printf(
        "Exported %d territories from %s to %s%n",
        geometry.territories().size(), mapFolder, output.toAbsolutePath());
  }
}
