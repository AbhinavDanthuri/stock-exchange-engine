package com.exchange.matching.engine;

import com.exchange.common.events.OrderPlacedEvent;
import com.exchange.common.events.OrderStatus;
import com.exchange.common.events.OrderStatusChangedEvent;
import com.exchange.matching.kafka.EnginePublisher;
import com.exchange.matching.model.Order;
import com.exchange.matching.model.Trade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the order books. Receives commands (from Kafka), routes them to
 * the owning shard thread, then publishes resulting trades + status events.
 */
@Service
public class MatchingEngineService {

    private static final Logger log = LoggerFactory.getLogger(MatchingEngineService.class);

    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    /** Idempotency guard: Kafka is at-least-once, dedupe replayed order ids. */
    private final Set<String> seenOrderIds = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequenceGenerator = new AtomicLong();

    private final SymbolShardRouter router;
    private final EnginePublisher publisher;
    private final Timer matchLatency;
    private final Counter tradesCounter;

    public MatchingEngineService(SymbolShardRouter router, EnginePublisher publisher, MeterRegistry metrics) {
        this.router = router;
        this.publisher = publisher;
        this.matchLatency = Timer.builder("engine.match.latency").publishPercentiles(0.5, 0.99).register(metrics);
        this.tradesCounter = metrics.counter("engine.trades.executed");
    }

    public CompletableFuture<Void> onOrderPlaced(OrderPlacedEvent evt) {
        if (!seenOrderIds.add(evt.orderId())) {
            log.warn("Duplicate order {} ignored (at-least-once replay)", evt.orderId());
            return CompletableFuture.completedFuture(null);
        }
        Order order = new Order(evt.orderId(), evt.userId(), evt.symbol(), evt.side(),
                evt.type(), evt.price(), evt.quantity(), sequenceGenerator.incrementAndGet());

        return router.submit(evt.symbol(), () -> {
            long start = System.nanoTime();
            OrderBook book = books.computeIfAbsent(evt.symbol(), OrderBook::new);
            MatchResult result = book.process(order);
            matchLatency.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            return result;
        }).thenAccept(this::publishResult);
    }

    public CompletableFuture<Void> onCancelRequested(String orderId, String symbol) {
        return router.submit(symbol, () -> {
            OrderBook book = books.get(symbol);
            return book == null ? null : book.cancel(orderId);
        }).thenAccept(cancelled -> {
            if (cancelled != null) {
                publisher.publishStatus(new OrderStatusChangedEvent(
                        cancelled.orderId(), symbol, OrderStatus.CANCELLED,
                        cancelled.filledQuantity(), cancelled.remainingQuantity(),
                        "USER_CANCELLED", Instant.now()));
            } else {
                publisher.publishStatus(new OrderStatusChangedEvent(
                        orderId, symbol, OrderStatus.REJECTED, 0, 0,
                        "CANCEL_REJECTED_NOT_ON_BOOK", Instant.now()));
            }
        });
    }

    public CompletableFuture<OrderBook.Snapshot> snapshot(String symbol, int depth) {
        return router.submit(symbol, () -> {
            OrderBook book = books.get(symbol);
            return book == null ? new OrderBook.Snapshot(symbol, null, java.util.List.of(), java.util.List.of())
                                : book.snapshot(depth);
        });
    }

    private void publishResult(MatchResult result) {
        for (Trade t : result.trades()) {
            tradesCounter.increment();
            publisher.publishTrade(t);
        }
        Order taker = result.taker();
        OrderStatus status = switch (result.outcome()) {
            case FILLED -> OrderStatus.FILLED;
            case RESTING -> OrderStatus.ACCEPTED;
            case PARTIALLY_FILLED_RESTING -> OrderStatus.PARTIALLY_FILLED;
            case CANCELLED_UNFILLED -> OrderStatus.CANCELLED;
            case REJECTED -> OrderStatus.REJECTED;
        };
        publisher.publishStatus(new OrderStatusChangedEvent(
                taker.orderId(), taker.symbol(), status,
                taker.filledQuantity(), taker.remainingQuantity(),
                result.reason(), Instant.now()));
    }
}
