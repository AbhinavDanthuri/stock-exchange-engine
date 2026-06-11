package com.exchange.order.kafka;

import com.exchange.common.events.OrderStatusChangedEvent;
import com.exchange.order.entity.AuditLog;
import com.exchange.order.repository.AuditLogRepository;
import com.exchange.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies engine status decisions back to MySQL. @RetryableTopic gives
 * automatic retries with backoff and a dead-letter topic (orders.status-dlt)
 * for poison messages -- fault tolerance without hand-rolled plumbing.
 */
@Component
public class OrderStatusConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusConsumer.class);
    private final OrderRepository orders;
    private final AuditLogRepository audit;

    public OrderStatusConsumer(OrderRepository orders, AuditLogRepository audit) {
        this.orders = orders; this.audit = audit;
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 500, multiplier = 2.0))
    @KafkaListener(topics = "orders.status", groupId = "order-service")
    @Transactional
    public void onStatusChanged(OrderStatusChangedEvent evt) {
        orders.findById(evt.orderId()).ifPresentOrElse(order -> {
            // Idempotent: applying the same status twice converges to same state.
            order.applyStatus(evt.status(), evt.filledQuantity(), evt.reason());
            orders.save(order);
            audit.save(new AuditLog("ORDER", evt.orderId(), "STATUS_" + evt.status(), null, evt.reason()));
        }, () -> log.warn("status for unknown order {}", evt.orderId()));
    }
}
