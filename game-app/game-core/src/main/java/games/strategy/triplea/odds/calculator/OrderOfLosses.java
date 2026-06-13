package games.strategy.triplea.odds.calculator;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.util.Tuple;

/**
 * Parses a textual "Order of Losses" (OOL) specification into a concrete unit casualty order. This
 * logic used to live in the desktop {@code OrderOfLossesInputPanel}; it is pure data manipulation
 * and is used headlessly by the battle calculator that the Pro AI relies on.
 */
@UtilityClass
public class OrderOfLosses {
  static final char OOL_SEPARATOR = ';';
  static final char OOL_AMOUNT_DESCRIPTOR = '^';
  static final String OOL_ALL = "*";

  @VisibleForTesting
  static Iterable<String> splitOrderOfLoss(final String orderOfLoss) {
    return Splitter.on(OOL_SEPARATOR).split(orderOfLoss.trim());
  }

  @VisibleForTesting
  static String[] splitOrderOfLossSection(final String orderOfLossSection) {
    return Iterables.toArray(
        Splitter.on(OOL_AMOUNT_DESCRIPTOR).split(orderOfLossSection), String.class);
  }

  static List<Unit> getUnitListByOrderOfLoss(
      final String ool, final Collection<Unit> units, final GameState data) {
    if (ool == null || ool.isBlank()) {
      return null;
    }
    final List<Tuple<Integer, UnitType>> map = new ArrayList<>();
    for (final String section : splitOrderOfLoss(ool)) {
      if (section.length() == 0) {
        continue;
      }
      final String[] amountThenType = splitOrderOfLossSection(section);
      final int amount =
          amountThenType[0].equals(OOL_ALL)
              ? Integer.MAX_VALUE
              : Integer.parseInt(amountThenType[0]);
      final UnitType type = data.getUnitTypeList().getUnitTypeOrThrow(amountThenType[1]);
      map.add(Tuple.of(amount, type));
    }
    Collections.reverse(map);
    final Set<Unit> unitsLeft = new HashSet<>(units);
    final List<Unit> order = new ArrayList<>();
    for (final Tuple<Integer, UnitType> section : map) {
      final List<Unit> unitsOfType =
          CollectionUtils.getNMatches(
              unitsLeft, section.getFirst(), Matches.unitIsOfType(section.getSecond()));
      order.addAll(unitsOfType);
      unitsLeft.removeAll(unitsOfType);
    }
    Collections.reverse(order);
    return order;
  }
}
