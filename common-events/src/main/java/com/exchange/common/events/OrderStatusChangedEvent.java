package com.exchange.common.events;

import java.time.Instant;

/** Published by matching-engine to "orders.status" so order-service can update MySQL. */
public record OrderStatusChangedEvent(
        String orderId,
        String symbol,
        OrderStatus status,
        long filledQuantity,
        long remainingQuantity,
        String reason,
        Instant occurredAt
) {}
