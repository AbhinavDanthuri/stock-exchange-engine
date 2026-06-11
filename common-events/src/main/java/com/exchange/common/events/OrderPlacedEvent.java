package com.exchange.common.events;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by order-service to topic "orders.placed" (key = symbol, so all
 * orders for one instrument land on the same partition and are processed in order).
 */
public record OrderPlacedEvent(
        String orderId,
        Long userId,
        String symbol,
        OrderSide side,
        OrderType type,
        BigDecimal price,        // null for MARKET orders
        long quantity,
        Instant placedAt
) {}
