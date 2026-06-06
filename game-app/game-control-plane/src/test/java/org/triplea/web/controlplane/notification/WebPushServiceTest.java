package org.triplea.web.controlplane.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole send path against a stand-in push service (a local {@link HttpServer}): the
 * VAPID {@code Authorization} header, the {@code aes128gcm} content headers, the encrypted body, and
 * the status-to-{@link WebPushService.Result} mapping. The payload crypto itself is pinned
 * separately by {@link WebPushCryptoTest}; here we confirm a real, well-formed HTTPS request is
 * produced and that a "gone" status drives pruning.
 */
class WebPushServiceTest {

  private HttpServer server;
  private final AtomicReference<RequestCapture> captured = new AtomicReference<>();
  private WebPushService service;
  private PushSubscription okSubscription;
  private PushSubscription goneSubscription;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/push/ok", exchange -> respond(exchange, 201));
    server.createContext("/push/gone", exchange -> respond(exchange, 410));
    server.start();

    final String[] vapid = VapidKeys.generate();
    service = new WebPushService(VapidKeys.fromConfig(vapid[0], vapid[1]), "mailto:test@example.com");

    // A stand-in browser subscription: a real EC public point (so encryption succeeds) + random auth.
    final KeyPair ua = EcUtil.generateKeyPair();
    final String p256dh = EcUtil.B64URL.encodeToString(EcUtil.encodePoint((ECPublicKey) ua.getPublic()));
    final String auth = EcUtil.B64URL.encodeToString(EcUtil.randomBytes(16));
    final String base = "http://127.0.0.1:" + server.getAddress().getPort();
    okSubscription = new PushSubscription(base + "/push/ok", p256dh, auth);
    goneSubscription = new PushSubscription(base + "/push/gone", p256dh, auth);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void sendsAWellFormedEncryptedRequestAndReturnsOk() {
    final WebPushService.Result result = service.send(okSubscription, "{\"title\":\"Your turn\"}");

    assertThat(result, is(WebPushService.Result.OK));
    final RequestCapture request = captured.get();
    assertThat(request, notNullValue());
    assertThat(request.method, is("POST"));
    assertThat(request.contentEncoding, is("aes128gcm"));
    assertThat(request.authorization, startsWith("vapid t="));
    assertThat(request.ttl, notNullValue());
    // aes128gcm body = 16-byte salt + 4 + 1 + 65-byte key header + ciphertext+tag → comfortably > 86.
    assertThat(request.bodyLength, greaterThan(86));
  }

  @Test
  void mapsGoneStatusToPrunable() {
    assertThat(service.send(goneSubscription, "{}"), is(WebPushService.Result.GONE));
  }

  private void respond(final HttpExchange exchange, final int status) throws IOException {
    final byte[] body = exchange.getRequestBody().readAllBytes();
    captured.set(
        new RequestCapture(
            exchange.getRequestMethod(),
            exchange.getRequestHeaders().getFirst("Content-Encoding"),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("TTL"),
            body.length));
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }

  private record RequestCapture(
      String method,
      String contentEncoding,
      String authorization,
      String ttl,
      int bodyLength) {}
}
