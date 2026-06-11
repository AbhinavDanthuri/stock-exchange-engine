package com.exchange.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by matching-engine to topic "trades.executed".
 * Consumed by portfolio-service (settlement), order-service (status update),
 * market-data-service (WebSocket broadcast + Redis ticker).
 */
public record TradeExecutedEvent(
        String tradeId,
        String symbol,
        String buyOrderId,
        String sellOrderId,
        Long buyerId,
        Long sellerId,
        BigDecimal price,
        long quantity,
        Instant executedAt
) {}
