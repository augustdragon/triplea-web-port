package org.triplea.web.controlplane.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;

/**
 * Pins the Web Push payload encryption to the worked example in RFC 8291 Appendix A. If the ECDH /
 * HKDF derivation or the aes128gcm record framing drifts, this fails — and a real browser would
 * silently fail to decrypt, which is far harder to diagnose. Every constant below is copied verbatim
 * from the RFC.
 */
class WebPushCryptoTest {

  private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
  private static final String UA_PUBLIC =
      "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
  private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
  private static final String AS_PUBLIC =
      "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
  private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
  private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
  private static final String EXPECTED_BODY =
      "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmY"
          + "WAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQ"
          + "exSgSxsj_Qulcy4a-fN";

  @Test
  void matchesRfc8291AppendixAVector() {
    final KeyPair sender =
        new KeyPair(
            EcUtil.publicKeyFromPoint(EcUtil.B64URL_DEC.decode(AS_PUBLIC)),
            EcUtil.privateKeyFromScalar(EcUtil.B64URL_DEC.decode(AS_PRIVATE)));

    final byte[] body =
        WebPushCrypto.encrypt(
            PLAINTEXT.getBytes(StandardCharsets.UTF_8),
            EcUtil.B64URL_DEC.decode(UA_PUBLIC),
            EcUtil.B64URL_DEC.decode(AUTH_SECRET),
            EcUtil.B64URL_DEC.decode(SALT),
            sender);

    assertThat(EcUtil.B64URL.encodeToString(body), is(EXPECTED_BODY));
  }
}
