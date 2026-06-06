package org.triplea.web.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import games.strategy.triplea.ui.display.HeadlessDisplay;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Forwards the engine's battle events to the browser as a compact historical record: one {@code
 * {type:"battle", kind:"result"}} envelope per battle, carrying the game round, the attacking
 * nation, the defender, the location, the outcome, and each side's total losses. The client groups
 * these by round → nation. (Live, in-battle force counts for a retreat decision come from {@link
 * WebPlayer#retreatQuery}'s panel, so the log itself stays a clean after-the-fact summary rather
 * than a per-dice-roll feed.)
 *
 * <p>Subclasses the no-op {@link HeadlessDisplay}; the battle's {@code isHeadless()} is false for a
 * real {@code ServerGame}, so {@code casualtyNotification} fires and we can total the losses.
 */
public final class WebDisplay extends HeadlessDisplay {
  private final Gson gson = new Gson();
  private final Consumer<String> sink;
  @Nullable private GameData gameData;
  private final Map<String, Battle> battles = new HashMap<>();

  public WebDisplay(final Consumer<String> sink) {
    this.sink = sink;
  }

  /** Wired after {@code startGame} so we can read the current game round at battle start. */
  public void setGameData(final GameData gameData) {
    this.gameData = gameData;
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
    final int round = gameData == null ? 0 : gameData.getSequence().getRound();
    battles.put(
        battleId.toString(),
        new Battle(round, attacker.getName(), defender.getName(), location.getName()));
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
    final Battle battle = battles.get(battleId.toString());
    if (battle == null) {
      return;
    }
    // Attribute losses to a side by the owning nation (the lead attacker vs everyone else).
    if (player.getName().equals(battle.attacker)) {
      battle.attackerKilled.addAll(killed);
    } else {
      battle.defenderKilled.addAll(killed);
    }
  }

  @Override
  public void battleEnd(final UUID battleId, final String message) {
    final Battle battle = battles.remove(battleId.toString());
    if (battle == null) {
      return;
    }
    final JsonObject event = new JsonObject();
    event.addProperty("type", "battle");
    event.addProperty("kind", "result");
    // Stable per-battle id so a client can dedup the cache the server replays on every (re)connect.
    event.addProperty("id", battleId.toString());
    event.addProperty("gameRound", battle.gameRound);
    event.addProperty("attacker", battle.attacker);
    event.addProperty("defender", battle.defender);
    event.addProperty("location", battle.location);
    event.addProperty("result", message);
    event.addProperty("attackerLosses", WebPlayer.summarizeUnits(battle.attackerKilled));
    event.addProperty("defenderLosses", WebPlayer.summarizeUnits(battle.defenderKilled));
    sink.accept(gson.toJson(event));
  }

  /** Accumulates one battle's facts until it ends. */
  private static final class Battle {
    private final int gameRound;
    private final String attacker;
    private final String defender;
    private final String location;
    private final List<Unit> attackerKilled = new ArrayList<>();
    private final List<Unit> defenderKilled = new ArrayList<>();

    private Battle(
        final int gameRound, final String attacker, final String defender, final String location) {
      this.gameRound = gameRound;
      this.attacker = attacker;
      this.defender = defender;
      this.location = location;
    }
  }
}
