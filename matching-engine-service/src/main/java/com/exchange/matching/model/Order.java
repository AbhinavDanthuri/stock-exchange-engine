package com.exchange.matching.model;

import com.exchange.common.events.OrderSide;
import com.exchange.common.events.OrderType;

import java.math.BigDecimal;

/**
 * In-memory order representation inside the engine.
 *
 * Mutability note: {@code remainingQuantity} is mutated ONLY by the single
 * engine thread that owns the symbol's order book (see SymbolShardRouter),
 * so no synchronization is needed -- this is the "single writer principle".
 */
public final class Order {

    private final String orderId;
    private final Long userId;
    private final String symbol;
    private final OrderSide side;
    private final OrderType type;
    private final BigDecimal price;       // null for MARKET
    private final long originalQuantity;
    private final long sequence;          // engine-assigned, enforces time priority

    private long remainingQuantity;

    public Order(String orderId, Long userId, String symbol, OrderSide side,
                 OrderType type, BigDecimal price, long quantity, long sequence) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (type == OrderType.LIMIT && (price == null || price.signum() <= 0))
            throw new IllegalArgumentException("limit order requires positive price");
        this.orderId = orderId;
        this.userId = userId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.sequence = sequence;
    }

    public void fill(long qty) {
        if (qty <= 0 || qty > remainingQuantity)
            throw new IllegalStateException("invalid fill qty " + qty + " for order " + orderId);
        remainingQuantity -= qty;
    }

    public boolean isFilled()           { return remainingQuantity == 0; }
    public String orderId()             { return orderId; }
    public Long userId()                { return userId; }
    public String symbol()              { return symbol; }
    public OrderSide side()             { return side; }
    public OrderType type()             { return type; }
    public BigDecimal price()           { return price; }
    public long originalQuantity()      { return originalQuantity; }
    public long remainingQuantity()     { return remainingQuantity; }
    public long filledQuantity()        { return originalQuantity - remainingQuantity; }
    public long sequence()              { return sequence; }
}
