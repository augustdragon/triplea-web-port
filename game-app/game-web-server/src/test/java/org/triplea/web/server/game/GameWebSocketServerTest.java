package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the battle-log reconnect cache in {@link GameWebSocketServer}: events are cached (so a
 * reconnecting client's log isn't empty), bounded, and cleared on a new/resumed game. The server is
 * constructed but never started, so {@code broadcast()} is a no-op over zero connections.
 */
class GameWebSocketServerTest {
  @Test
  void battleLogCachesInOrder() {
    final GameWebSocketServer s = new GameWebSocketServer(0);
    s.publishBattleEvent("e1");
    s.publishBattleEvent("e2");
    assertEquals(List.of("e1", "e2"), s.snapshotBattleLog());
  }

  @Test
  void battleLogIsBoundedDroppingOldest() {
    final GameWebSocketServer s = new GameWebSocketServer(0);
    for (int i = 0; i < 520; i++) {
      s.publishBattleEvent("e" + i);
    }
    final List<String> log = s.snapshotBattleLog();
    assertEquals(500, log.size(), "capped at the max");
    assertEquals("e20", log.get(0), "the oldest 20 were dropped");
    assertEquals("e519", log.get(log.size() - 1), "newest retained");
  }

  @Test
  void resetClearsBattleLog() {
    final GameWebSocketServer s = new GameWebSocketServer(0);
    s.publishBattleEvent("e1");
    s.resetForNewGame();
    assertTrue(s.snapshotBattleLog().isEmpty());
  }
}
