package org.triplea.web.controlplane.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.triplea.web.controlplane.auth.ConnectTicket.Payload;

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
    final Payload p = payload(soon());

    final Optional<Payload> verified = ConnectTicket.verify(ConnectTicket.sign(p, TOKEN), TOKEN);

    assertThat(verified.isPresent(), is(true));
    assertThat(verified.get(), is(p));
  }

  @Test
  void preservesANullSeat() {
    final Payload p = new Payload("game-1", null, 7L, "Host", true, "n", soon());

    final Optional<Payload> verified = ConnectTicket.verify(ConnectTicket.sign(p, TOKEN), TOKEN);

    assertThat(verified.isPresent(), is(true));
    assertThat(verified.get().seat(), is((String) null));
  }

  @Test
  void rejectsADifferentToken() {
    final String ticket = ConnectTicket.sign(payload(soon()), TOKEN);

    assertThat(ConnectTicket.verify(ticket, "some-other-token").isEmpty(), is(true));
  }

  @Test
  void rejectsTamperedPayload() {
    final String ticket = ConnectTicket.sign(payload(soon()), TOKEN);
    final int dot = ticket.indexOf('.');
    // Flip a character in the payload segment; the signature no longer matches.
    final String tampered =
        (ticket.charAt(0) == 'A' ? 'B' : 'A') + ticket.substring(1, dot) + ticket.substring(dot);

    assertThat(ConnectTicket.verify(tampered, TOKEN).isEmpty(), is(true));
  }

  @Test
  void rejectsExpiredTicket() {
    final String ticket = ConnectTicket.sign(payload(Instant.now().getEpochSecond() - 1), TOKEN);

    assertThat(ConnectTicket.verify(ticket, TOKEN).isEmpty(), is(true));
  }

  @Test
  void rejectsGarbage() {
    assertThat(ConnectTicket.verify("not-a-ticket", TOKEN).isEmpty(), is(true));
    assertThat(ConnectTicket.verify(null, TOKEN).isEmpty(), is(true));
    assertThat(ConnectTicket.verify("noseparator", TOKEN).isEmpty(), is(true));
  }
}
