package org.triplea.web.server.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.triplea.web.server.game.ConnectTicket.Payload;

/**
 * Guards the container-side connect-ticket verifier. The wire format mirrors the control plane's
 * {@code ConnectTicket}; these tests round-trip with the container's own signer (the real signer in
 * production is the control plane — the cross-module contract is covered by the M6 e2e harness).
 */
class ConnectTicketTest {

  private static final String TOKEN = "shared-game-token-abc";

  private static Payload payload(final long exp) {
    return new Payload("game-1", "Japanese", 42L, "Alice", true, "nonce-1", exp);
  }

  private static long soon() {
    return Instant.now().getEpochSecond() + 60;
  }

  @Test
  void roundTripsToTheSamePayload() {
    final Optional<Payload> verified =
        ConnectTicket.verify(ConnectTicket.sign(payload(soon()), TOKEN), TOKEN);

    assertTrue(verified.isPresent());
    assertEquals(payload(soon()).seat(), verified.get().seat());
    assertEquals("Alice", verified.get().displayName());
    assertTrue(verified.get().isHost());
  }

  @Test
  void rejectsWrongToken() {
    final String ticket = ConnectTicket.sign(payload(soon()), TOKEN);
    assertTrue(ConnectTicket.verify(ticket, "other-token").isEmpty());
  }

  @Test
  void rejectsExpired() {
    final String ticket = ConnectTicket.sign(payload(Instant.now().getEpochSecond() - 1), TOKEN);
    assertTrue(ConnectTicket.verify(ticket, TOKEN).isEmpty());
  }

  @Test
  void rejectsTampered() {
    final String ticket = ConnectTicket.sign(payload(soon()), TOKEN);
    final int dot = ticket.indexOf('.');
    final String tampered =
        (ticket.charAt(0) == 'A' ? 'B' : 'A') + ticket.substring(1, dot) + ticket.substring(dot);
    assertTrue(ConnectTicket.verify(tampered, TOKEN).isEmpty());
  }

  @Test
  void rejectsGarbageAndBlankToken() {
    assertTrue(ConnectTicket.verify("not-a-ticket", TOKEN).isEmpty());
    assertTrue(ConnectTicket.verify(null, TOKEN).isEmpty());
    assertTrue(ConnectTicket.verify(ConnectTicket.sign(payload(soon()), TOKEN), "").isEmpty());
  }
}
