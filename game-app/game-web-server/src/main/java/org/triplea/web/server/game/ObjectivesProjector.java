package org.triplea.web.server.game;

import com.google.common.base.Splitter;
import games.strategy.engine.data.GameData;
import games.strategy.triplea.attachments.AbstractConditionsAttachment;
import games.strategy.triplea.attachments.AbstractPlayerRulesAttachment;
import games.strategy.triplea.attachments.ICondition;
import games.strategy.triplea.ui.ObjectiveDummyDelegateBridge;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.triplea.util.FileNameUtils;

/**
 * Computes the national-objectives list from a map's {@code objectives.properties} plus the live
 * {@link GameData}, mirroring the base game's {@code ObjectivePanel}. The properties file defines,
 * per game, ordered sections (the {@code TABLEGROUP} entries) and one HTML description per
 * objective keyed {@code <gameName>.<player>;<attachmentName>}; each objective resolves to an
 * {@link ICondition} (a rules/trigger attachment) via {@link
 * AbstractPlayerRulesAttachment#getCondition}, whose satisfied/unsatisfied state is evaluated with
 * {@link AbstractConditionsAttachment}.
 *
 * <p>Evaluation is read-only: it uses {@link ObjectiveDummyDelegateBridge}, exactly as
 * ObjectivePanel does, which discards any {@code addChange} and returns 0 for randomness, so
 * testing a condition can't mutate the game. Called on the game-loop thread between steps, so no
 * read lock is needed (unlike the Swing panel, which runs on the EDT).
 */
public final class ObjectivesProjector {
  private static final String GROUP = "TABLEGROUP";

  private ObjectivesProjector() {}

  public static List<ObjectiveItem> project(final GameData data, final Properties props) {
    if (props == null || props.isEmpty()) {
      return List.of();
    }
    final String prefix =
        FileNameUtils.replaceIllegalCharacters(data.getGameName(), '_').replaceAll(" ", "_") + ".";

    // Pass 1: sections (TABLEGROUP entries), ordered by their sorter number. Each maps to the
    // attachment names it contains, in declared order.
    final Set<String> sorters = new TreeSet<>(); // "<sorter>;<section>"
    final Map<String, List<String>> sectionMembers = new LinkedHashMap<>();
    for (final Map.Entry<Object, Object> entry : props.entrySet()) {
      final String fileKey = (String) entry.getKey();
      if (!fileKey.startsWith(prefix)) {
        continue;
      }
      final List<String> key = Splitter.on(';').splitToList(fileKey.substring(prefix.length()));
      if (key.size() != 2 || !key.get(0).startsWith(GROUP)) {
        continue;
      }
      final List<String> sorter = Splitter.on('.').splitToList(key.get(0));
      if (sorter.size() != 2) {
        continue;
      }
      sorters.add(sorter.get(1) + ";" + key.get(1));
      sectionMembers.put(key.get(1), List.of(((String) entry.getValue()).split(";")));
    }
    final List<String> orderedSections = new ArrayList<>();
    for (final String s : sorters) {
      orderedSections.add(s.substring(s.indexOf(';') + 1));
    }

    // Pass 2: objectives — resolve each to its condition and assign it to a section.
    final Map<String, Map<String, ICondition>> sectionConditions = new LinkedHashMap<>();
    final Map<String, String> textByAttachment = new LinkedHashMap<>();
    for (final Map.Entry<Object, Object> entry : props.entrySet()) {
      final String fileKey = (String) entry.getKey();
      if (!fileKey.startsWith(prefix)) {
        continue;
      }
      final List<String> key = Splitter.on(';').splitToList(fileKey.substring(prefix.length()));
      if (key.size() != 2 || key.get(0).startsWith(GROUP)) {
        continue;
      }
      final ICondition condition =
          AbstractPlayerRulesAttachment.getCondition(key.get(0), key.get(1), data);
      if (condition == null) {
        continue;
      }
      final String attachmentName = key.get(1);
      for (final String section : orderedSections) {
        if (sectionMembers.get(section).contains(attachmentName)) {
          sectionConditions
              .computeIfAbsent(section, s -> new LinkedHashMap<>())
              .put(attachmentName, condition);
          textByAttachment.put(attachmentName, (String) entry.getValue());
          break;
        }
      }
    }

    // Evaluate every referenced condition once, read-only via the dummy bridge.
    final Set<ICondition> all = new HashSet<>();
    sectionConditions.values().forEach(m -> all.addAll(m.values()));
    final Map<ICondition, Boolean> tested =
        all.isEmpty()
            ? Map.of()
            : AbstractConditionsAttachment.testAllConditionsRecursive(
                AbstractConditionsAttachment.getAllConditionsRecursive(all, null),
                null,
                new ObjectiveDummyDelegateBridge(data));

    // Emit in section order, then each section's declared attachment order (de-duplicated).
    final List<ObjectiveItem> items = new ArrayList<>();
    for (final String section : orderedSections) {
      final Map<String, ICondition> conds = sectionConditions.get(section);
      if (conds == null) {
        continue;
      }
      final Set<String> emitted = new HashSet<>();
      for (final String attachmentName : sectionMembers.get(section)) {
        final ICondition condition = conds.get(attachmentName);
        if (condition == null || !emitted.add(attachmentName)) {
          continue;
        }
        items.add(
            new ObjectiveItem(
                section,
                textByAttachment.get(attachmentName),
                Boolean.TRUE.equals(tested.get(condition))));
      }
    }
    return items;
  }
}
