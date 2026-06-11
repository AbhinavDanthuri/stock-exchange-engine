package com.exchange.order.service;

import com.exchange.common.events.*;
import com.exchange.order.dto.OrderDtos.*;
import com.exchange.order.entity.AuditLog;
import com.exchange.order.entity.OrderEntity;
import com.exchange.order.repository.AuditLogRepository;
import com.exchange.order.repository.OrderRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Write path:
 *   1. validate request (+ idempotency key check in Redis)
 *   2. persist order row in MySQL with status=NEW  (system of record)
 *   3. publish OrderPlacedEvent to Kafka, keyed by symbol
 *   4. matching engine asynchronously emits status updates + trades
 *
 * This is the "outbox-lite" pattern; docs/architecture.md discusses upgrading
 * to a true transactional outbox with Debezium for exactly-once handoff.
 */
@Service
public class OrderService {

    public static final String TOPIC_ORDERS = "orders.placed";
    public static final String TOPIC_CANCEL = "orders.cancel-requested";

    private final OrderRepository orders;
    private final AuditLogRepository audit;
    private final KafkaTemplate<String, Object> kafka;
    private final StringRedisTemplate redis;

    public OrderService(OrderRepository orders, AuditLogRepository audit,
                        KafkaTemplate<String, Object> kafka, StringRedisTemplate redis) {
        this.orders = orders; this.audit = audit; this.kafka = kafka; this.redis = redis;
    }

    @Transactional
    public OrderResponse placeOrder(Long userId, PlaceOrderRequest req, String idempotencyKey) {
        if (req.type() == OrderType.LIMIT && req.price() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LIMIT order requires price");
        if (req.type() == OrderType.MARKET && req.price() != null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MARKET order must not carry price");

        // Idempotency: clients retry on timeout; same key must not double-submit.
        if (idempotencyKey != null) {
            String redisKey = "idem:order:" + userId + ":" + idempotencyKey;
            Boolean first = redis.opsForValue().setIfAbsent(redisKey, "1", Duration.ofMinutes(10));
            if (Boolean.FALSE.equals(first))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "duplicate request (idempotency key reuse)");
        }

        String orderId = UUID.randomUUID().toString();
        String symbol = req.symbol().toUpperCase();
        OrderEntity entity = new OrderEntity(orderId, userId, symbol, req.side(),
                req.type(), req.price(), req.quantity());
        orders.save(entity);
        audit.save(new AuditLog("ORDER", orderId, "PLACED", userId,
                "{\"symbol\":\"%s\",\"side\":\"%s\",\"qty\":%d}".formatted(symbol, req.side(), req.quantity())));

        kafka.send(TOPIC_ORDERS, symbol, new OrderPlacedEvent(orderId, userId, symbol,
                req.side(), req.type(), req.price(), req.quantity(), Instant.now()));

        return toResponse(entity);
    }

    @Transactional
    public void requestCancel(Long userId, String orderId) {
        OrderEntity order = orders.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!order.getUserId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        if (order.getStatus() == OrderStatus.FILLED || order.getStatus() == OrderStatus.CANCELLED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "order already terminal: " + order.getStatus());

        audit.save(new AuditLog("ORDER", orderId, "CANCEL_REQUESTED", userId, null));
        kafka.send(TOPIC_CANCEL, order.getSymbol(),
                new OrderCancelRequestedEvent(orderId, order.getSymbol(), userId, Instant.now()));
        // NOTE: cancellation is asynchronous; the engine decides (the order may
        // already be filled). Final status arrives via orders.status.
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> myOrders(Long userId, int page, int size) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(this::toResponse).getContent();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, String orderId) {
        OrderEntity o = orders.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found"));
        if (!o.getUserId().equals(userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your order");
        return toResponse(o);
    }

    private OrderResponse toResponse(OrderEntity o) {
        return new OrderResponse(o.getId(), o.getSymbol(), o.getSide(), o.getType(),
                o.getPrice(), o.getQuantity(), o.getFilledQuantity(),
                o.getStatus(), o.getRejectReason(), o.getCreatedAt());
    }
}
