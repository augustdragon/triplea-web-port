package games.strategy.triplea.util;

import static games.strategy.triplea.Constants.UNIT_ATTACHMENT_NAME;
import static games.strategy.triplea.delegate.battle.steps.MockGameData.givenGameData;
import static org.hamcrest.MatcherAssert.assertThat;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.UnitAttachment;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnitSeparatorTest {
  private final GameData gameData = givenGameData().build();
  private final GamePlayer player1 = new GamePlayer("player1", gameData);

  private UnitType givenUnitType(final String name) {
    final UnitType unitType = new UnitType(name, gameData);
    final UnitAttachment unitAttachment = new UnitAttachment(name, unitType, gameData);
    unitType.addAttachment(UNIT_ATTACHMENT_NAME, unitAttachment);
    return unitType;
  }

  @Test
  void testCategorizeWithAirUnitsWithDifferentMovement_simplePositiveCase() {

    final UnitType dragon = givenUnitType("Dragon");
    dragon.getUnitAttachment().setHitPoints(2);
    dragon.getUnitAttachment().setMovement(4);
    dragon.getUnitAttachment().setIsAir(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(dragon.createTemp(2, player1));

    units.get(0).setAlreadyMoved(BigDecimal.ONE);
    units.get(1).setAlreadyMoved(BigDecimal.valueOf(2));

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    assertThat(
        "Air units with different movement points left should be in different categories",
        categories.size() == 2);
  }

  @Test
  void testCategorizeWithAirUnitsWithDifferentMovement_insensitiveToAirUnitsOneHitPoint() {
    final UnitType drake = givenUnitType("Drake");
    drake.getUnitAttachment().setHitPoints(1);
    drake.getUnitAttachment().setMovement(4);
    drake.getUnitAttachment().setIsAir(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(drake.createTemp(2, player1));

    units.get(0).setAlreadyMoved(BigDecimal.ONE);
    units.get(1).setAlreadyMoved(BigDecimal.valueOf(2));

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    assertThat(
        "Air units with type with one hit point should be in the same category",
        categories.size() == 1);
  }

  @Test
  void
      testCategorizeWithAirUnitsWithDifferentMovement_regardingAirUnitsInsensitiveToMovementFlag() {
    final UnitType dragon = givenUnitType("Dragon");
    dragon.getUnitAttachment().setHitPoints(2);
    dragon.getUnitAttachment().setMovement(4);
    dragon.getUnitAttachment().setIsAir(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(dragon.createTemp(2, player1));

    units.get(0).setAlreadyMoved(BigDecimal.ONE);
    units.get(1).setAlreadyMoved(BigDecimal.valueOf(2));

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    final UnitSeparator.SeparatorCategories separatorCategoriesIncludingMovement =
        UnitSeparator.SeparatorCategories.builder()
            .movement(true)
            .movementForAirUnitsOnly(true)
            .build();

    final Set<UnitCategory> categoriesWithMovementFlag =
        UnitSeparator.categorize(units, separatorCategoriesIncludingMovement);

    assertThat(
        "Categorization of air units should be the same regardless of the pure movement flag",
        categories.equals(categoriesWithMovementFlag));
  }

  @Test
  void testCategorizeWithAirUnitsWithDifferentMovement_sensitiveToAirUnitsWithOneHitPointLeft() {
    final UnitType dragon = givenUnitType("Dragon");
    dragon.getUnitAttachment().setHitPoints(2);
    dragon.getUnitAttachment().setMovement(4);
    dragon.getUnitAttachment().setIsAir(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(dragon.createTemp(2, player1));

    units.get(0).setAlreadyMoved(BigDecimal.ONE);
    units.get(1).setAlreadyMoved(BigDecimal.valueOf(2));

    units.get(0).setHits(1);
    units.get(1).setHits(1);

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    assertThat(
        "Categorization of air units should take into account hit points by type "
            + "but not hit points the unit has already taken",
        categories.size() == 2);
  }

  @Test
  void testCategorizeWithAirUnitsWithDifferentMovement_unitsWithDifferentHitsInDifferentCategory() {
    final UnitType dragon = givenUnitType("Dragon");
    dragon.getUnitAttachment().setHitPoints(2);
    dragon.getUnitAttachment().setMovement(4);
    dragon.getUnitAttachment().setIsAir(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(dragon.createTemp(2, player1));

    units.get(0).setHits(1);

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    assertThat(
        "units with different hits should be in different categories", categories.size() == 2);
  }

  @Test
  void testCategorizeWithAirUnitsWithDifferentMovement_insensitiveToNonAirUnits() {
    final UnitType tank = givenUnitType("Tank");
    tank.getUnitAttachment().setHitPoints(2);
    tank.getUnitAttachment().setMovement(4);

    final UnitType battleship = givenUnitType("Battleship");
    battleship.getUnitAttachment().setHitPoints(2);
    battleship.getUnitAttachment().setMovement(4);
    battleship.getUnitAttachment().setIsSea(true);

    final List<Unit> units = new ArrayList<>();
    units.addAll(tank.createTemp(2, player1));
    units.addAll(battleship.createTemp(2, player1));

    units.get(0).setAlreadyMoved(BigDecimal.ONE);
    units.get(1).setAlreadyMoved(BigDecimal.valueOf(2));
    units.get(2).setAlreadyMoved(BigDecimal.ONE);
    units.get(3).setAlreadyMoved(BigDecimal.valueOf(2));

    final UnitSeparator.SeparatorCategories separatorCategories =
        UnitSeparator.SeparatorCategories.builder().movementForAirUnitsOnly(true).build();

    final Set<UnitCategory> categories = UnitSeparator.categorize(units, separatorCategories);

    assertThat(
        "Non-Air units with different movement points left should be in the same category",
        categories.size() == 2);
  }
}
