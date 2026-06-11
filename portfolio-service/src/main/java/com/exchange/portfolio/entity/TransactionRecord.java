package com.exchange.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable transaction-history row, one per (user, trade) leg. */
@Entity
@Table(name = "transactions",
       uniqueConstraints = @UniqueConstraint(name = "uq_txn_trade_user", columnNames = {"tradeId", "userId"}),
       indexes = @Index(name = "idx_txn_user_time", columnList = "userId, executedAt"))
public class TransactionRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36) private String tradeId;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 10) private String symbol;
    @Column(nullable = false, length = 4)  private String side; // BUY / SELL
    @Column(nullable = false, precision = 18, scale = 4) private BigDecimal price;
    @Column(nullable = false) private long quantity;
    @Column(nullable = false, precision = 18, scale = 4) private BigDecimal grossAmount;
    @Column(nullable = false) private Instant executedAt;

    protected TransactionRecord() {}

    public TransactionRecord(String tradeId, Long userId, String symbol, String side,
                             BigDecimal price, long quantity, Instant executedAt) {
        this.tradeId = tradeId; this.userId = userId; this.symbol = symbol; this.side = side;
        this.price = price; this.quantity = quantity;
        this.grossAmount = price.multiply(BigDecimal.valueOf(quantity));
        this.executedAt = executedAt;
    }

    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public BigDecimal getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public Instant getExecutedAt() { return executedAt; }
    public String getTradeId() { return tradeId; }
}
