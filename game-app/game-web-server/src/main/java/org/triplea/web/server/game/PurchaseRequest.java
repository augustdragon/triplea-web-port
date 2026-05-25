package org.triplea.web.server.game;

import java.util.List;
import javax.annotation.Nullable;

/**
 * The payload of a {@code kind:"purchase"} decision request: which player is buying, how many PUs
 * they have to spend, whether this is a bid step, the buyable {@link PurchaseOption}s, and an
 * optional {@code error} carried back when a prior submission was rejected by the delegate (so the
 * browser can show why and let the player re-choose).
 */
public record PurchaseRequest(
    String player,
    int pusAvailable,
    boolean bid,
    List<PurchaseOption> options,
    @Nullable String error) {}
