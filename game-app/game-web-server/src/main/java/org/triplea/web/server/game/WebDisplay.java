package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Forwards the engine's battle-progress events to the browser as a live battle log. Subclasses the
 * no-op {@link HeadlessDisplay} and overrides only the events worth showing — battle start, dice
 * rolled, casualties, retreat, battle end — emitting each as a {@code {type:"battle", kind:...}}
 * envelope via the supplied sink (the WebSocket broadcast). This is the <i>display</i> channel
 * (what happened); decisions still flow through {@link WebDecisionBridge}.
 *
 * <p>The battle's {@code isHeadless()} flag is false for a real {@code ServerGame} (only the odds
 * calculator sets it true), so these callbacks fire normally here.
 */
public final class WebDisplay extends HeadlessDisplay {
  private final Gson gson = new Gson();
  private final Consumer<String> sink;

  public WebDisplay(final Consumer<String> sink) {
    this.sink = sink;
  }

  private void emit(final JsonObject event) {
    event.addProperty("type", "battle");
    sink.accept(gson.toJson(event));
  }

  @Override
  public void showBattle(
      final UUID battleId,
      final Territory location,
      final String battleTitle,
      final Collection<Unit> attackingUnits,
      final Collection<Unit> defendingUnits,
      final Collection<Unit> killedUnits,
      final Collection<Unit> attackingWaitingToDie,
      final Collection<Unit> defendingWaitingToDie,
      final Map<Unit, Collection<Unit>> dependentUnits,
      final GamePlayer attacker,
      final GamePlayer defender,
      final boolean isAmphibious,
      final BattleType battleType,
      final Collection<Unit> amphibiousLandAttackers) {
    final JsonObject event = new JsonObject();
    event.addProperty("kind", "start");
    event.addProperty("battleId", battleId.toString());
    event.addProperty("location", location.getName());
    event.addProperty("attacker", attacker.getName());
    event.addProperty("defender", defender.getName());
    event.addProperty("attackers", WebPlayer.summarizeUnits(attackingUnits));
    event.addProperty("defenders", WebPlayer.summarizeUnits(defendingUnits));
    event.addProperty("amphibious", isAmphibious);
    emit(event);
  }

  @Override
  public void notifyDice(final DiceRoll dice, final String stepName) {
    final JsonObject event = new JsonObject();
    event.addProperty("kind", "dice");
    event.addProperty("step", stepName);
    event.addProperty("hits", dice.getHits());
    emit(event);
  }

  @Override
  public void casualtyNotification(
      final UUID battleId,
      final String step,
      final DiceRoll dice,
      final GamePlayer player,
      final Collection<Unit> killed,
      final Collection<Unit> damaged,
      final Map<Unit, Collection<Unit>> dependents) {
    final JsonObject event = new JsonObject();
    event.addProperty("kind", "casualties");
    event.addProperty("battleId", battleId.toString());
    event.addProperty("player", player.getName());
    event.addProperty("killed", WebPlayer.summarizeUnits(killed));
    event.addProperty("damaged", WebPlayer.summarizeUnits(damaged));
    emit(event);
  }

  @Override
  public void notifyRetreat(
      final String shortMessage,
      final String message,
      final String step,
      final GamePlayer retreatingPlayer) {
    final JsonObject event = new JsonObject();
    event.addProperty("kind", "retreat");
    event.addProperty("player", retreatingPlayer.getName());
    event.addProperty("message", message);
    emit(event);
  }

  @Override
  public void battleEnd(final UUID battleId, final String message) {
    final JsonObject event = new JsonObject();
    event.addProperty("kind", "end");
    event.addProperty("battleId", battleId.toString());
    event.addProperty("message", message);
    emit(event);
  }
}
