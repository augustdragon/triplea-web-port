package org.triplea.web.server.game;

import games.strategy.engine.data.GameData;
import games.strategy.engine.delegate.IDelegateBridge;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Factory for a read-only {@link IDelegateBridge} that exposes only {@link
 * IDelegateBridge#getData()} (and an intentionally empty {@link
 * IDelegateBridge#getResourceLoader()}), throwing {@link UnsupportedOperationException} on
 * everything else.
 *
 * <p>Used by {@link HostVictoryDetector} to reuse the engine's own condition evaluator ({@code
 * TriggerAttachment.collectTestsForAllTriggers}) headless: for ownership/VP victory conditions that
 * evaluator only reads {@code bridge.getData()}. If a condition ever needs more (e.g. a dice {@code
 * chance} condition, which victory triggers don't use), the thrown exception is caught by the
 * detector and the trigger is treated as unevaluable — fail-closed, never a spurious win.
 *
 * <p>Implemented as a dynamic proxy on purpose: {@code
 * IDelegateBridge.sendMessage(WebSocketMessage)} references a type from an unrelated lobby module,
 * and a directly-implemented class would force the game container to depend on it just to throw.
 * The proxy dispatches by method name and never names that type.
 *
 * <p>{@code getResourceLoader()} returns empty deliberately — we are NOT taking the engine's
 * notification-message victory path (the one a headless host can't satisfy); we detect and end the
 * game ourselves. See docs/web-port/VICTORY-FIX-PLAN.md.
 */
final class GameDataDelegateBridge {
  private GameDataDelegateBridge() {}

  static IDelegateBridge readOnly(final GameData data) {
    return (IDelegateBridge)
        Proxy.newProxyInstance(
            IDelegateBridge.class.getClassLoader(),
            new Class<?>[] {IDelegateBridge.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getData" -> data;
                  case "getResourceLoader" -> Optional.empty();
                  // Object methods, so the proxy is safe to log/store without throwing.
                  case "toString" -> "GameDataDelegateBridge(readOnly)";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default ->
                      throw new UnsupportedOperationException(
                          "read-only condition-evaluation bridge: " + method.getName());
                });
  }
}
