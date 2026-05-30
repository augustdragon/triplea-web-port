package org.triplea.web.server.game;

/**
 * One resource's standing for a player in the Resources info tab (the base game's EconomyPanel
 * cell): the current {@code amount} on hand and the {@code income} estimated for the next
 * end-of-turn, rendered client-side as {@code amount (+income)}.
 *
 * @param name resource name (e.g. "PUs", "techTokens", "SuicideAttackTokens").
 * @param amount quantity currently held.
 * @param income estimated change next end-of-turn.
 */
public record ResourceCell(String name, int amount, int income) {}
