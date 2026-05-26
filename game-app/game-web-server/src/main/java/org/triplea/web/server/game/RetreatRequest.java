package org.triplea.web.server.game;

import java.util.List;

/**
 * The payload of a {@code kind:"retreat"} decision request: the attacker (or a submerging sub
 * owner) may pull out of a battle between rounds. {@code options} are the territory names that can
 * be retreated to; when {@code submerge} is true the only option is the battle site itself (subs go
 * invisible rather than move). {@code attackers}/{@code defenders} are the forces still standing
 * (per-type summaries) so the player can weigh the odds before deciding. The browser replies {@code
 * {retreatTo:"<name>"}} to pull out (or submerge) or {@code {remain:true}} to keep fighting.
 */
public record RetreatRequest(
    String player,
    String battleTerritory,
    boolean submerge,
    List<String> options,
    String message,
    String attackers,
    String defenders) {}
