package com.exchange.matching.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Trade(
        String tradeId,
        String symbol,
        String buyOrderId,
        String sellOrderId,
        Long buyerId,
        Long sellerId,
        BigDecimal price,
        long quantity,
        Instant executedAt
) {
    public static Trade of(String symbol, Order buy, Order sell, BigDecimal price, long qty) {
        return new Trade(UUID.randomUUID().toString(), symbol,
                buy.orderId(), sell.orderId(), buy.userId(), sell.userId(),
                price, qty, Instant.now());
    }
}
