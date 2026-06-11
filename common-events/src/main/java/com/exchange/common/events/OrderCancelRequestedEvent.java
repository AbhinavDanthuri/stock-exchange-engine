package com.exchange.common.events;

import java.time.Instant;

/** Published to "orders.cancel-requested" (key = symbol). */
public record OrderCancelRequestedEvent(String orderId, String symbol, Long userId, Instant requestedAt) {}
