package com.exchange.matching.engine;

import com.exchange.common.events.OrderSide;
import com.exchange.common.events.OrderType;
import com.exchange.matching.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    private OrderBook book;
    private final AtomicLong seq = new AtomicLong();

    @BeforeEach
    void setUp() {
        book = new OrderBook("AAPL");
    }

    private Order limit(OrderSide side, String price, long qty) {
        return new Order("ord-" + seq.incrementAndGet(), 1L, "AAPL", side,
                OrderType.LIMIT, new BigDecimal(price), qty, seq.get());
    }

    private Order market(OrderSide side, long qty) {
        return new Order("ord-" + seq.incrementAndGet(), 1L, "AAPL", side,
                OrderType.MARKET, null, qty, seq.get());
    }

    @Test
    void limitOrdersThatDontCrossRestOnBook() {
        var r1 = book.process(limit(OrderSide.BUY, "100.00", 10));
        var r2 = book.process(limit(OrderSide.SELL, "101.00", 10));

        assertEquals(MatchResult.TakerOutcome.RESTING, r1.outcome());
        assertEquals(MatchResult.TakerOutcome.RESTING, r2.outcome());
        assertEquals(new BigDecimal("100.00"), book.bestBid());
        assertEquals(new BigDecimal("101.00"), book.bestAsk());
        assertTrue(r1.trades().isEmpty());
    }

    @Test
    void crossingLimitOrderExecutesAtMakerPrice() {
        book.process(limit(OrderSide.SELL, "101.00", 10));         // maker
        var result = book.process(limit(OrderSide.BUY, "103.00", 10)); // taker willing to pay more

        assertEquals(MatchResult.TakerOutcome.FILLED, result.outcome());
        assertEquals(1, result.trades().size());
        // Price improvement: trade executes at maker's 101, not taker's 103
        assertEquals(new BigDecimal("101.00"), result.trades().get(0).price());
        assertNull(book.bestAsk());
    }

    @Test
    void partialFillRestsRemainderOnBook() {
        book.process(limit(OrderSide.SELL, "101.00", 4));
        var result = book.process(limit(OrderSide.BUY, "101.00", 10));

        assertEquals(MatchResult.TakerOutcome.PARTIALLY_FILLED_RESTING, result.outcome());
        assertEquals(4, result.trades().get(0).quantity());
        assertEquals(6, result.taker().remainingQuantity());
        assertEquals(new BigDecimal("101.00"), book.bestBid()); // remainder now best bid
    }

    @Test
    void timePriorityWithinPriceLevelIsFifo() {
        Order first = limit(OrderSide.SELL, "101.00", 5);
        Order second = limit(OrderSide.SELL, "101.00", 5);
        book.process(first);
        book.process(second);

        var result = book.process(limit(OrderSide.BUY, "101.00", 5));
        assertEquals(first.orderId(), result.trades().get(0).sellOrderId());
    }

    @Test
    void pricePriorityAcrossLevels_bestPriceFillsFirst() {
        book.process(limit(OrderSide.SELL, "102.00", 5));
        book.process(limit(OrderSide.SELL, "101.00", 5)); // better ask, arrived later

        var result = book.process(market(OrderSide.BUY, 5));
        assertEquals(new BigDecimal("101.00"), result.trades().get(0).price());
    }

    @Test
    void marketOrderSweepsMultipleLevels() {
        book.process(limit(OrderSide.SELL, "101.00", 5));
        book.process(limit(OrderSide.SELL, "102.00", 5));

        var result = book.process(market(OrderSide.BUY, 8));
        assertEquals(MatchResult.TakerOutcome.FILLED, result.outcome());
        assertEquals(2, result.trades().size());
        assertEquals(5, result.trades().get(0).quantity());
        assertEquals(3, result.trades().get(1).quantity());
        assertEquals(new BigDecimal("102.00"), book.bestAsk()); // 2 left at 102
    }

    @Test
    void marketOrderWithNoLiquidityIsRejected() {
        var result = book.process(market(OrderSide.BUY, 10));
        assertEquals(MatchResult.TakerOutcome.REJECTED, result.outcome());
        assertEquals("NO_LIQUIDITY", result.reason());
    }

    @Test
    void marketOrderRemainderIsCancelledNotRested() {
        book.process(limit(OrderSide.SELL, "101.00", 3));
        var result = book.process(market(OrderSide.BUY, 10));

        assertEquals(MatchResult.TakerOutcome.CANCELLED_UNFILLED, result.outcome());
        assertEquals(3, result.taker().filledQuantity());
        assertEquals(0, book.restingOrderCount()); // nothing rested
    }

    @Test
    void cancelRemovesRestingOrder() {
        Order o = limit(OrderSide.BUY, "100.00", 10);
        book.process(o);
        assertNotNull(book.cancel(o.orderId()));
        assertNull(book.bestBid());
        assertNull(book.cancel(o.orderId())); // idempotent: second cancel is a no-op
    }

    @Test
    void snapshotAggregatesQuantityPerLevel() {
        book.process(limit(OrderSide.BUY, "100.00", 10));
        book.process(limit(OrderSide.BUY, "100.00", 5));
        book.process(limit(OrderSide.BUY, "99.00", 7));

        var snap = book.snapshot(10);
        assertEquals(2, snap.bids().size());
        assertEquals(15, snap.bids().get(0).quantity());
        assertEquals(2, snap.bids().get(0).orderCount());
    }
}
