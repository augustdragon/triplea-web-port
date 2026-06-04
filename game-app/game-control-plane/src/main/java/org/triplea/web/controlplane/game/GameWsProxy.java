package org.triplea.web.controlplane.game;

import io.javalin.websocket.WsContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;

/**
 * Pipes one browser WebSocket (the control plane's {@code /game/{id}/ws} endpoint) to a game's
 * internal WebSocket ({@code ws://localhost:<port>}), so every game shares the single public origin
 * and stays behind TLS — the per-game container/process ports never face the internet. The control
 * plane authorizes the session before constructing this; the browser's first message is its
 * connect-ticket, which is forwarded to the game and verified there (end-to-end ticket auth
 * unchanged — this is a dumb pipe with a session gate in front).
 *
 * <p>Two subtleties handled: (1) the upstream connects asynchronously, so browser messages that
 * arrive before it is ready (notably the first auth ticket) are buffered and flushed on open; (2)
 * the JDK WebSocket permits only one outstanding send, so browser→upstream sends are serialized.
 */
@Slf4j
public final class GameWsProxy {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final WsContext browser;
  private final String upstreamUri;
  private final Object lock = new Object();
  private final List<String> pending = new ArrayList<>();
  private final StringBuilder inbound = new StringBuilder();
  private volatile WebSocket upstream;
  private volatile boolean closed;

  public GameWsProxy(final WsContext browser, final String upstreamUri) {
    this.browser = browser;
    this.upstreamUri = upstreamUri;
  }

  /**
   * WS close code (4502) when the game upstream can't be reached — distinct from the game's own
   * close codes (e.g. 4401 for a bad ticket), so the client/harness can tell "not ready yet" apart
   * from "rejected".
   */
  private static final int UPSTREAM_UNREACHABLE = 4502;

  /** Open the upstream connection to the game; buffered browser messages flush once it's ready. */
  public void connectUpstream() {
    HTTP.newWebSocketBuilder()
        .buildAsync(URI.create(upstreamUri), new UpstreamListener())
        .whenComplete(
            (ws, err) -> {
              if (err != null) {
                log.warn("Game WS proxy could not reach {}: {}", upstreamUri, err.getMessage());
                close(UPSTREAM_UNREACHABLE, "game upstream unreachable");
              }
            });
  }

  /** Browser → game. Buffer until upstream is up, then send serially (one outstanding send). */
  public void toUpstream(final String text) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      if (upstream == null) {
        pending.add(text);
        return;
      }
      send(text);
    }
  }

  /** Must hold {@code lock}. Send one frame and wait for it (JDK allows one outstanding send). */
  private void send(final String text) {
    try {
      upstream.sendText(text, true).toCompletableFuture().join();
    } catch (final RuntimeException e) {
      close();
    }
  }

  /**
   * Close both legs idempotently, relaying the game's close code to the browser (so it can tell a
   * bad-ticket reject from a transient not-ready).
   */
  public void close() {
    close(WebSocket.NORMAL_CLOSURE, "");
  }

  public void close(final int code, final String reason) {
    final WebSocket up;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      up = upstream;
      pending.clear();
    }
    if (up != null) {
      up.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(e -> null);
    }
    if (browser.session.isOpen()) {
      browser.closeSession(code, reason == null ? "" : reason);
    }
  }

  /** Receives game → browser frames and the upstream lifecycle. */
  private final class UpstreamListener implements WebSocket.Listener {
    @Override
    public void onOpen(final WebSocket webSocket) {
      synchronized (lock) {
        upstream = webSocket;
        for (final String m : pending) {
          send(m);
        }
        pending.clear();
      }
      webSocket.request(Long.MAX_VALUE); // deliver all game frames as they arrive
    }

    @Override
    public CompletionStage<?> onText(
        final WebSocket webSocket, final CharSequence data, final boolean last) {
      inbound.append(data);
      if (last) {
        final String message = inbound.toString();
        inbound.setLength(0);
        if (browser.session.isOpen()) {
          browser.send(message);
        }
      }
      return null;
    }

    @Override
    public CompletionStage<?> onClose(
        final WebSocket webSocket, final int statusCode, final String reason) {
      close(
          statusCode, reason); // relay the game's close code (e.g. 4401 bad ticket) to the browser
      return null;
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable error) {
      log.debug("Game WS proxy upstream error: {}", error.getMessage());
      close();
    }
  }
}
