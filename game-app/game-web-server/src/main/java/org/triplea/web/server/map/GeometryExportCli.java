package org.triplea.web.server.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line entry point that converts a map folder's geometry files into a {@code geometry.json}
 * for the web client.
 *
 * <p>Usage: {@code GeometryExportCli <mapFolder> <outputJsonFile> [gameXmlFile]} where {@code
 * mapFolder} contains {@code polygons.txt} (and optionally {@code centers.txt}). When a {@code
 * gameXmlFile} is given, the territory adjacency graph parsed from it is included too.
 */
public final class GeometryExportCli {
  private GeometryExportCli() {}

  public static void main(final String[] args) throws IOException {
    if (args.length < 2 || args.length > 3) {
      System.err.println("Usage: GeometryExportCli <mapFolder> <outputJsonFile> [gameXmlFile]");
      System.exit(2);
      return;
    }
    final Path mapFolder = Path.of(args[0]);
    final Path output = Path.of(args[1]);

    final MapGeometry geometry =
        (args.length == 3)
            ? MapGeometryConverter.fromMapFolderAndGame(mapFolder, Path.of(args[2]))
            : MapGeometryConverter.fromMapFolder(mapFolder);
    Files.writeString(output, MapGeometryConverter.toJson(geometry));

    System.out.printf(
        "Exported %d territories (%s) from %s to %s%n",
        geometry.territories().size(),
        geometry.connections() == null
            ? "no connections"
            : geometry.connections().size() + " with connections",
        mapFolder,
        output.toAbsolutePath());
  }
}
