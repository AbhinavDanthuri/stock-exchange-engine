package com.exchange.matching.engine;

import com.exchange.common.events.OrderSide;
import com.exchange.common.events.OrderType;
import com.exchange.matching.model.Order;
import com.exchange.matching.model.Trade;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Price-time priority limit order book for a single symbol.
 *
 * Data structures:
 *  - bids: TreeMap sorted DESC by price  -> best bid is firstKey()
 *  - asks: TreeMap sorted ASC  by price  -> best ask is firstKey()
 *  - each price level is a FIFO Deque (time priority within a level)
 *  - ordersById: O(1) lookup for cancellation
 *
 * Complexity: O(log P) to locate a price level (P = number of price levels),
 * O(1) amortized per fill within a level.
 *
 * THREADING CONTRACT: this class is intentionally NOT thread-safe. Exactly one
 * engine thread ever touches a given OrderBook instance (single-writer
 * principle, same approach LMAX Disruptor popularized). Concurrency across
 * symbols is achieved by sharding symbols over engine threads, not by locking
 * inside the book. That keeps the hot path free of contention and makes the
 * matching logic trivially deterministic and testable.
 */
public class OrderBook {

    private final String symbol;
    private final NavigableMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();
    private final Map<String, Order> ordersById = new HashMap<>();

    /** Last traded price; used as a reference and exposed in snapshots. */
    private BigDecimal lastTradePrice;

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    /** Entry point: match an incoming (taker) order against the book. */
    public MatchResult process(Order taker) {
        return switch (taker.type()) {
            case LIMIT -> processLimit(taker);
            case MARKET -> processMarket(taker);
        };
    }

    private MatchResult processLimit(Order taker) {
        List<Trade> trades = matchAgainstBook(taker, taker.price());
        if (taker.isFilled()) {
            return new MatchResult(taker, trades, MatchResult.TakerOutcome.FILLED, null);
        }
        // Whatever remains rests on the book at its limit price.
        rest(taker);
        var outcome = trades.isEmpty()
                ? MatchResult.TakerOutcome.RESTING
                : MatchResult.TakerOutcome.PARTIALLY_FILLED_RESTING;
        return new MatchResult(taker, trades, outcome, null);
    }

    private MatchResult processMarket(Order taker) {
        // A market order crosses at any price -- pass null limit.
        List<Trade> trades = matchAgainstBook(taker, null);
        if (taker.isFilled()) {
            return new MatchResult(taker, trades, MatchResult.TakerOutcome.FILLED, null);
        }
        // Market orders never rest: unfilled remainder is cancelled
        // (standard exchange semantics; avoids "market order at infinity" risk).
        var outcome = trades.isEmpty()
                ? MatchResult.TakerOutcome.REJECTED
                : MatchResult.TakerOutcome.CANCELLED_UNFILLED;
        String reason = trades.isEmpty() ? "NO_LIQUIDITY" : "PARTIAL_FILL_REMAINDER_CANCELLED";
        return new MatchResult(taker, trades, outcome, reason);
    }

    /**
     * Core loop: walk the opposite side of the book from best price outward,
     * filling FIFO within each level, until the taker is filled, the book is
     * exhausted, or prices no longer cross the limit.
     */
    private List<Trade> matchAgainstBook(Order taker, BigDecimal limit) {
        NavigableMap<BigDecimal, Deque<Order>> opposite =
                taker.side() == OrderSide.BUY ? asks : bids;
        List<Trade> trades = new ArrayList<>();

        while (!taker.isFilled() && !opposite.isEmpty()) {
            Map.Entry<BigDecimal, Deque<Order>> bestLevel = opposite.firstEntry();
            BigDecimal bookPrice = bestLevel.getKey();

            if (!crosses(taker.side(), limit, bookPrice)) break;

            Deque<Order> queue = bestLevel.getValue();
            Iterator<Order> it = queue.iterator();
            while (!taker.isFilled() && it.hasNext()) {
                Order maker = it.next();
                long qty = Math.min(taker.remainingQuantity(), maker.remainingQuantity());

                taker.fill(qty);
                maker.fill(qty);
                lastTradePrice = bookPrice; // trades execute at the MAKER's price

                Order buy  = taker.side() == OrderSide.BUY ? taker : maker;
                Order sell = taker.side() == OrderSide.BUY ? maker : taker;
                trades.add(Trade.of(symbol, buy, sell, bookPrice, qty));

                if (maker.isFilled()) {
                    it.remove();
                    ordersById.remove(maker.orderId());
                }
            }
            if (queue.isEmpty()) {
                opposite.remove(bookPrice); // drop empty price level
            }
        }
        return trades;
    }

    private boolean crosses(OrderSide takerSide, BigDecimal limit, BigDecimal bookPrice) {
        if (limit == null) return true; // market order
        return takerSide == OrderSide.BUY
                ? limit.compareTo(bookPrice) >= 0   // willing to pay >= best ask
                : limit.compareTo(bookPrice) <= 0;  // willing to sell <= best bid
    }

    private void rest(Order order) {
        NavigableMap<BigDecimal, Deque<Order>> side =
                order.side() == OrderSide.BUY ? bids : asks;
        side.computeIfAbsent(order.price(), p -> new ArrayDeque<>()).addLast(order);
        ordersById.put(order.orderId(), order);
    }

    /** O(1) lookup + O(level size) removal. Returns the cancelled order or null. */
    public Order cancel(String orderId) {
        Order order = ordersById.remove(orderId);
        if (order == null) return null; // already filled / never rested / unknown
        NavigableMap<BigDecimal, Deque<Order>> side =
                order.side() == OrderSide.BUY ? bids : asks;
        Deque<Order> queue = side.get(order.price());
        if (queue != null) {
            queue.removeIf(o -> o.orderId().equals(orderId));
            if (queue.isEmpty()) side.remove(order.price());
        }
        return order;
    }

    // ---------- snapshot / introspection ----------

    public record Level(BigDecimal price, long quantity, int orderCount) {}
    public record Snapshot(String symbol, BigDecimal lastTradePrice, List<Level> bids, List<Level> asks) {}

    public Snapshot snapshot(int depth) {
        return new Snapshot(symbol, lastTradePrice, top(bids, depth), top(asks, depth));
    }

    private List<Level> top(NavigableMap<BigDecimal, Deque<Order>> side, int depth) {
        List<Level> levels = new ArrayList<>(depth);
        for (Map.Entry<BigDecimal, Deque<Order>> e : side.entrySet()) {
            if (levels.size() == depth) break;
            long qty = e.getValue().stream().mapToLong(Order::remainingQuantity).sum();
            levels.add(new Level(e.getKey(), qty, e.getValue().size()));
        }
        return levels;
    }

    public BigDecimal bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public BigDecimal bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }
    public BigDecimal lastTradePrice() { return lastTradePrice; }
    public int restingOrderCount() { return ordersById.size(); }
    public String symbol() { return symbol; }
}
