package com.exchange.matching.kafka;

import com.exchange.common.events.OrderCancelRequestedEvent;
import com.exchange.common.events.OrderPlacedEvent;
import com.exchange.matching.engine.MatchingEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka partitions are keyed by symbol, so within one partition all events for
 * a symbol arrive in order. With concurrency=3 listener threads + shard
 * routing inside the engine, per-symbol ordering is preserved end-to-end.
 */
@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);
    private final MatchingEngineService engine;

    public OrderEventsConsumer(MatchingEngineService engine) {
        this.engine = engine;
    }

    @KafkaListener(topics = "orders.placed", groupId = "matching-engine", concurrency = "3")
    public void onOrderPlaced(OrderPlacedEvent evt) {
        log.debug("Received order {} {} {} x{} @{}", evt.orderId(), evt.side(), evt.symbol(), evt.quantity(), evt.price());
        engine.onOrderPlaced(evt);
    }

    @KafkaListener(topics = "orders.cancel-requested", groupId = "matching-engine", concurrency = "3")
    public void onCancelRequested(OrderCancelRequestedEvent evt) {
        engine.onCancelRequested(evt.orderId(), evt.symbol());
    }
}
