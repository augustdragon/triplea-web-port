package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards {@link SaveStore} — the filesystem save seam: write/read/exists + atomic overwrite. */
class SaveStoreTest {
  @Test
  void writeReadRoundTrips(@TempDir final Path dir) {
    final SaveStore store = new SaveStore(dir);
    final byte[] data = {1, 2, 3, 4, 5};
    assertFalse(store.exists("g1"));
    store.write("g1", data);
    assertTrue(store.exists("g1"));
    assertArrayEquals(data, store.read("g1").orElseThrow());
  }

  @Test
  void readMissingSlotIsEmpty(@TempDir final Path dir) {
    assertTrue(new SaveStore(dir).read("nope").isEmpty());
  }

  @Test
  void writeOverwrites(@TempDir final Path dir) {
    final SaveStore store = new SaveStore(dir);
    store.write("g", new byte[] {1});
    store.write("g", new byte[] {9, 9});
    assertArrayEquals(new byte[] {9, 9}, store.read("g").orElseThrow());
  }
}
