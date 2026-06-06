package org.triplea.web.controlplane.notification;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * P-256 elliptic-curve, ECDH, and HKDF helpers for Web Push — JDK crypto only (no BouncyCastle).
 *
 * <p>Key wire formats follow the Web Push convention the browser's {@code PushManager} uses: a
 * public key is the uncompressed EC point ({@code 0x04 || X(32) || Y(32)} = 65 bytes), and a private
 * key is the raw 32-byte scalar — both base64url without padding.
 */
final class EcUtil {
  private EcUtil() {}

  static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

  private static final String CURVE = "secp256r1"; // == NIST P-256 == prime256v1
  private static final int COORD_LEN = 32;
  private static final int POINT_LEN = 65;
  private static final SecureRandom RANDOM = new SecureRandom();

  static ECParameterSpec p256Params() {
    try {
      final AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
      params.init(new ECGenParameterSpec(CURVE));
      return params.getParameterSpec(ECParameterSpec.class);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("P-256 curve parameters unavailable", e);
    }
  }

  static KeyPair generateKeyPair() {
    try {
      final KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
      gen.initialize(new ECGenParameterSpec(CURVE), RANDOM);
      return gen.generateKeyPair();
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("Cannot generate P-256 key pair", e);
    }
  }

  static byte[] randomBytes(final int length) {
    final byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /** Build an EC public key from a 65-byte uncompressed point ({@code 0x04 || X || Y}). */
  static ECPublicKey publicKeyFromPoint(final byte[] point) {
    if (point.length != POINT_LEN || point[0] != 0x04) {
      throw new IllegalArgumentException("Expected a 65-byte uncompressed P-256 point");
    }
    final BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + COORD_LEN));
    final BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 1 + COORD_LEN, POINT_LEN));
    try {
      return (ECPublicKey)
          KeyFactory.getInstance("EC")
              .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
    } catch (final GeneralSecurityException e) {
      throw new IllegalArgumentException("Invalid P-256 public point", e);
    }
  }

  /** Build an EC private key from a raw scalar (big-endian, up to 32 bytes). */
  static ECPrivateKey privateKeyFromScalar(final byte[] scalar) {
    try {
      return (ECPrivateKey)
          KeyFactory.getInstance("EC")
              .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), p256Params()));
    } catch (final GeneralSecurityException e) {
      throw new IllegalArgumentException("Invalid P-256 private scalar", e);
    }
  }

  /** Encode a public key as the 65-byte uncompressed point. */
  static byte[] encodePoint(final ECPublicKey key) {
    final byte[] x = toFixedLength(key.getW().getAffineX(), COORD_LEN);
    final byte[] y = toFixedLength(key.getW().getAffineY(), COORD_LEN);
    final byte[] out = new byte[POINT_LEN];
    out[0] = 0x04;
    System.arraycopy(x, 0, out, 1, COORD_LEN);
    System.arraycopy(y, 0, out, 1 + COORD_LEN, COORD_LEN);
    return out;
  }

  /** Encode a private key as the raw 32-byte scalar. */
  static byte[] encodeScalar(final ECPrivateKey key) {
    return toFixedLength(key.getS(), COORD_LEN);
  }

  /** The ECDH shared secret (the 32-byte X coordinate of the agreed point) — RFC 8291's {@code Z}. */
  static byte[] ecdh(final ECPrivateKey privateKey, final ECPublicKey publicKey) {
    try {
      final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
      agreement.init(privateKey);
      agreement.doPhase(publicKey, true);
      return agreement.generateSecret();
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("ECDH failed", e);
    }
  }

  /**
   * HKDF (RFC 5869) over HMAC-SHA-256: extract a pseudorandom key from {@code salt}+{@code ikm},
   * then expand to {@code length} bytes under {@code info}.
   */
  static byte[] hkdf(final byte[] salt, final byte[] ikm, final byte[] info, final int length) {
    final byte[] prk = hmacSha256(salt, ikm);
    final ByteArrayOutputStream okm = new ByteArrayOutputStream();
    byte[] block = new byte[0];
    for (int counter = 1; okm.size() < length; counter++) {
      final ByteArrayOutputStream input = new ByteArrayOutputStream();
      input.writeBytes(block);
      input.writeBytes(info);
      input.write(counter);
      block = hmacSha256(prk, input.toByteArray());
      okm.writeBytes(block);
    }
    return Arrays.copyOf(okm.toByteArray(), length);
  }

  static byte[] hmacSha256(final byte[] key, final byte[] data) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 failed", e);
    }
  }

  static byte[] concat(final byte[]... parts) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] toFixedLength(final BigInteger value, final int length) {
    final byte[] raw = value.toByteArray();
    if (raw.length == length) {
      return raw;
    }
    final byte[] out = new byte[length];
    if (raw.length > length) {
      // Drop a leading sign byte (BigInteger may prepend 0x00 for a high bit).
      System.arraycopy(raw, raw.length - length, out, 0, length);
    } else {
      System.arraycopy(raw, 0, out, length - raw.length, raw.length);
    }
    return out;
  }
}
