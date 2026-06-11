package com.exchange.matching.engine;

import com.exchange.matching.model.Order;
import com.exchange.matching.model.Trade;

import java.util.List;

/**
 * Everything that happened as a result of processing one inbound order:
 * zero or more trades, plus the final state of the taker order
 * (resting on book / fully filled / rejected).
 */
public record MatchResult(Order taker, List<Trade> trades, TakerOutcome outcome, String reason) {

    public enum TakerOutcome { RESTING, FILLED, PARTIALLY_FILLED_RESTING, REJECTED, CANCELLED_UNFILLED }

    public static MatchResult rejected(Order taker, String reason) {
        return new MatchResult(taker, List.of(), TakerOutcome.REJECTED, reason);
    }
}
