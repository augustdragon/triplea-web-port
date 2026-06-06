package org.triplea.web.controlplane.notification;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts a Web Push payload using the {@code aes128gcm} content encoding (RFC 8188) with the key
 * derivation specified for Web Push (RFC 8291). JDK crypto only.
 *
 * <p>Conformance is pinned by {@code WebPushCryptoTest}, which reproduces the worked example in RFC
 * 8291 Appendix A byte-for-byte. The single-record body produced here is what gets POSTed to the
 * browser's push endpoint; the browser's service worker decrypts it transparently.
 */
final class WebPushCrypto {
  private WebPushCrypto() {}

  // key_info = "WebPush: info" || 0x00 || ua_public || as_public  (RFC 8291 §3.4)
  private static final byte[] KEY_INFO_PREFIX = ascii("WebPush: info\0");
  // RFC 8188 §2.2 / §2.3 derivation labels (each NUL-terminated).
  private static final byte[] CEK_INFO = ascii("Content-Encoding: aes128gcm\0");
  private static final byte[] NONCE_INFO = ascii("Content-Encoding: nonce\0");

  private static final int CEK_LEN = 16; // AES-128
  private static final int NONCE_LEN = 12; // GCM IV
  private static final int IKM_LEN = 32;
  private static final int SALT_LEN = 16;
  private static final int GCM_TAG_BITS = 128;
  private static final int RECORD_SIZE = 4096; // RFC 8291 Appendix A
  private static final byte LAST_RECORD_DELIMITER = 0x02; // RFC 8188 §2 (padding delimiter)

  /** Encrypt with a fresh random salt and ephemeral sender key pair (the production path). */
  static byte[] encrypt(
      final byte[] plaintext, final byte[] uaPublicKey, final byte[] authSecret) {
    return encrypt(
        plaintext, uaPublicKey, authSecret, EcUtil.randomBytes(SALT_LEN), EcUtil.generateKeyPair());
  }

  /**
   * Encrypt deterministically with a caller-supplied salt and sender key pair — used by the
   * conformance test to reproduce the RFC vector; production calls the random overload above.
   *
   * @param plaintext the message bytes (the service worker receives these decrypted)
   * @param uaPublicKey the subscription's {@code p256dh} key (65-byte uncompressed point)
   * @param authSecret the subscription's 16-byte {@code auth} secret
   * @param salt the 16-byte content-encoding salt
   * @param senderKeyPair the ephemeral application-server (sender) EC key pair
   */
  static byte[] encrypt(
      final byte[] plaintext,
      final byte[] uaPublicKey,
      final byte[] authSecret,
      final byte[] salt,
      final KeyPair senderKeyPair) {
    final ECPrivateKey senderPrivate = (ECPrivateKey) senderKeyPair.getPrivate();
    final ECPublicKey senderPublic = (ECPublicKey) senderKeyPair.getPublic();
    final byte[] senderPublicBytes = EcUtil.encodePoint(senderPublic);
    final ECPublicKey uaPublic = EcUtil.publicKeyFromPoint(uaPublicKey);

    final byte[] ecdhSecret = EcUtil.ecdh(senderPrivate, uaPublic);

    // RFC 8291 §3.4: IKM is derived from the ECDH secret keyed by the auth secret, bound to both
    // public keys so a key can't be replayed against a different subscription.
    final byte[] keyInfo = EcUtil.concat(KEY_INFO_PREFIX, uaPublicKey, senderPublicBytes);
    final byte[] ikm = EcUtil.hkdf(authSecret, ecdhSecret, keyInfo, IKM_LEN);

    // RFC 8188 §2.2/§2.3: content-encryption key and nonce are derived from that IKM and the salt.
    final byte[] cek = EcUtil.hkdf(salt, ikm, CEK_INFO, CEK_LEN);
    final byte[] nonce = EcUtil.hkdf(salt, ikm, NONCE_INFO, NONCE_LEN);

    // Single record: plaintext || 0x02 (last-record padding delimiter), no extra padding.
    final byte[] record = new byte[plaintext.length + 1];
    System.arraycopy(plaintext, 0, record, 0, plaintext.length);
    record[plaintext.length] = LAST_RECORD_DELIMITER;

    final byte[] ciphertext = aesGcm(cek, nonce, record);

    // RFC 8188 §2.1 header: salt(16) || rs(uint32) || idlen(1) || keyid(idlen), keyid = sender key.
    final ByteBuffer header =
        ByteBuffer.allocate(SALT_LEN + 4 + 1 + senderPublicBytes.length)
            .put(salt)
            .putInt(RECORD_SIZE)
            .put((byte) senderPublicBytes.length)
            .put(senderPublicBytes);
    return EcUtil.concat(header.array(), ciphertext);
  }

  private static byte[] aesGcm(final byte[] key, final byte[] nonce, final byte[] plaintext) {
    try {
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      return cipher.doFinal(plaintext);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("AES-128-GCM encryption failed", e);
    }
  }

  private static byte[] ascii(final String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
