package org.triplea.web.controlplane.auth;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private static final String SECRET = "abcdefghijklmnopqrstuvwxyz0123456789";

  private final JwtService jwt = new JwtService(SECRET, 60);

  @Test
  void mintedTokenRoundTripsToTheSameIdentity() {
    final Identity identity = new Identity("google", "sub-123", "Alice", "alice@example.com");

    final Optional<Identity> verified = jwt.verify(jwt.mint(identity));

    assertThat(verified.isPresent(), is(true));
    assertThat(verified.get(), is(identity));
  }

  @Test
  void rejectsGarbageToken() {
    assertThat(jwt.verify("not-a-jwt").isEmpty(), is(true));
  }

  @Test
  void rejectsTokenSignedWithADifferentSecret() {
    final JwtService other = new JwtService("ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ", 60);
    final String foreign = other.mint(new Identity("google", "s", "N", null));

    assertThat(jwt.verify(foreign).isEmpty(), is(true));
  }

  @Test
  void rejectsTamperedToken() {
    final String token = jwt.mint(new Identity("google", "sub", "N", null));
    // Tamper the first signature character: the last one only carries base64 padding bits,
    // which lenient decoders discard, so flipping it does not change the signature bytes.
    final int sigStart = token.lastIndexOf('.') + 1;
    final String tampered =
        token.substring(0, sigStart)
            + (token.charAt(sigStart) == 'A' ? 'B' : 'A')
            + token.substring(sigStart + 1);

    assertThat(jwt.verify(tampered).isEmpty(), is(true));
  }
}
