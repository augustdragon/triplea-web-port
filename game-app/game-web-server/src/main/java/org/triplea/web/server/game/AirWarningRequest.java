package org.triplea.web.server.game;

import java.util.List;

/**
 * The payload of a {@code kind:"airWarning"} decision request, sent when the player tries to end a
 * move phase that removes stranded aircraft while they still have air units that can't reach
 * friendly territory. {@code territories} are the locations holding such air (which will be lost if
 * the phase ends now). The browser replies {@code {endAnyway:true}} to end the phase and accept the
 * loss, or anything else (e.g. {@code {endAnyway:false}}) to keep moving and try to land them.
 */
public record AirWarningRequest(String player, List<String> territories) {}
