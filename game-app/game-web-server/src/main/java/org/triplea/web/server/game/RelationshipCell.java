package org.triplea.web.server.game;

/**
 * One player-to-player relationship for the relationship grid. {@code type} is the engine
 * relationship type name (e.g. "War", "Neutrality", "Custodianship"); {@code category} collapses it
 * to "war" / "allied" / "neutral" (the engine archetype, via the relationship tracker) so the
 * client can color the cell without knowing every map's custom type names.
 */
public record RelationshipCell(String type, String category) {}
