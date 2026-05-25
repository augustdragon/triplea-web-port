package org.triplea.web.server.game;

/**
 * One buyable production rule offered to the browser during the purchase phase: the engine rule
 * name (the key the browser echoes back in its choice), its PU cost, and the primary unit it
 * produces with quantity. Keyed by {@code name} because {@code ProductionRule} objects are not
 * JSON-serializable; the server reconstructs the rule from this name.
 */
public record PurchaseOption(String name, int cost, String produces, int quantity) {}
