package com.exchange.portfolio.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "holdings",
       uniqueConstraints = @UniqueConstraint(name = "uq_holding_user_symbol", columnNames = {"userId", "symbol"}))
public class Holding {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 10) private String symbol;
    @Column(nullable = false) private long quantity;

    /** Optimistic lock: two trade settlements for the same user+symbol may race. */
    @Version
    private long version;

    protected Holding() {}

    public Holding(Long userId, String symbol) {
        this.userId = userId; this.symbol = symbol; this.quantity = 0;
    }

    public void add(long qty)    { this.quantity += qty; }
    public void remove(long qty) { this.quantity -= qty; } // may go negative pre-funding-check; see docs
    public Long getUserId() { return userId; }
    public String getSymbol() { return symbol; }
    public long getQuantity() { return quantity; }
}
