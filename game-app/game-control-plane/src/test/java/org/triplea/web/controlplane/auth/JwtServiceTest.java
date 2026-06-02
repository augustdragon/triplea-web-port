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
    final Identity identity = new Identity("google", "sub-123", "Alice");

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
    final String foreign = other.mint(new Identity("google", "s", "N"));

    assertThat(jwt.verify(foreign).isEmpty(), is(true));
  }

  @Test
  void rejectsTamperedToken() {
    final String token = jwt.mint(new Identity("google", "sub", "N"));
    final String tampered =
        token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

    assertThat(jwt.verify(tampered).isEmpty(), is(true));
  }
}
