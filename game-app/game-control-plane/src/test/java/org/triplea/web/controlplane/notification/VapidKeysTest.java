package org.triplea.web.controlplane.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.security.interfaces.ECPublicKey;
import org.junit.jupiter.api.Test;

class VapidKeysTest {

  @Test
  void generatedKeysRoundTripAndPublicKeyIsA65BytePoint() {
    final String[] generated = VapidKeys.generate();
    final VapidKeys keys = VapidKeys.fromConfig(generated[0], generated[1]);

    assertThat(keys.publicKeyBase64(), is(generated[0]));
    assertThat(EcUtil.B64URL_DEC.decode(keys.publicKeyBase64()).length, is(65));
  }

  @Test
  void authorizationHeaderIsAVerifiableEs256Jwt() {
    final String[] generated = VapidKeys.generate();
    final VapidKeys keys = VapidKeys.fromConfig(generated[0], generated[1]);

    final String header =
        keys.authorizationHeader("https://push.example.com", "mailto:admin@example.com");

    assertThat(header, startsWith("vapid t="));
    final String token = header.substring("vapid t=".length(), header.indexOf(",k="));
    final ECPublicKey publicKey = EcUtil.publicKeyFromPoint(EcUtil.B64URL_DEC.decode(generated[0]));
    final DecodedJWT decoded =
        JWT.require(Algorithm.ECDSA256(publicKey, null)).build().verify(token);

    assertThat(decoded.getAudience().get(0), is("https://push.example.com"));
    assertThat(decoded.getSubject(), is("mailto:admin@example.com"));
    assertThat(header.endsWith(",k=" + generated[0]), is(true));
  }
}
