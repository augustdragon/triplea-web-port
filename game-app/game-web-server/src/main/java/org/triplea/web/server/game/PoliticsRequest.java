package org.triplea.web.server.game;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"politics"} decision request: the player may take a political action
 * (declare war, sign a treaty) as the first phase of its turn. {@code actions} are the actions
 * currently legal for this player (the engine's {@code getValidActions} — already filtered by
 * conditions like "not yet at war"). {@code error} carries back a delegate rejection of a prior
 * attempt. The browser replies {@code {action:"<name>"}} to attempt one (the engine applies the
 * relationship changes and the server re-prompts with the now-smaller action list), or {@code
 * {done:true}} to end the phase. An empty action list ends the phase server-side without prompting.
 */
public record PoliticsRequest(
    String player, List<PoliticalActionOption> actions, @Nullable String error) {}
