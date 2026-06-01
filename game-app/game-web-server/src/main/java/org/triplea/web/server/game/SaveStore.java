package org.triplea.web.server.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Durable storage for game saves, keyed by an opaque slot id. Backed by the local filesystem for
 * now; the {@code byte[]}-in / {@code byte[]}-out interface is the seam that later swaps to a
 * Docker volume or object store (R2/S3) without touching callers.
 *
 * <p>A save is TripleA's standard GZIP-compressed serialized {@code GameData} (the {@code .tsvg}
 * format) — produced by {@code GameData.toBytes()} and consumed by {@code
 * GameDataManager.loadGame()} — and includes delegates + history, so it resumes mid-game.
 *
 * <p>Single slot per game for now (this architecture hosts one game at a time). When the control
 * plane lands, the slot becomes the game id and the directory a per-game key in the store.
 */
@Slf4j
final class SaveStore {
  private final Path dir;

  SaveStore(final Path dir) {
    this.dir = dir;
    try {
      Files.createDirectories(dir);
    } catch (final IOException e) {
      throw new UncheckedIOException("Could not create save directory " + dir, e);
    }
  }

  private Path file(final String slot) {
    return dir.resolve(slot + ".tsvg");
  }

  /**
   * Write a save atomically (temp file + atomic move) so a crash mid-write can't corrupt the slot.
   * Logs and swallows I/O errors — a failed autosave must not crash the game loop.
   */
  void write(final String slot, final byte[] bytes) {
    final Path target = file(slot);
    final Path tmp = dir.resolve(slot + ".tsvg.tmp");
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      log.warn("Autosave to slot '{}' failed", slot, e);
    }
  }

  Optional<byte[]> read(final String slot) {
    final Path f = file(slot);
    if (!Files.exists(f)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readAllBytes(f));
    } catch (final IOException e) {
      log.warn("Reading save slot '{}' failed", slot, e);
      return Optional.empty();
    }
  }

  boolean exists(final String slot) {
    return Files.exists(file(slot));
  }
}
