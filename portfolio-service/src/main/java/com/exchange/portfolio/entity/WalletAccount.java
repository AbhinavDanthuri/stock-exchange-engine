package com.exchange.portfolio.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "wallet_accounts",
       uniqueConstraints = @UniqueConstraint(name = "uq_wallet_user", columnNames = "userId"))
public class WalletAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private Long userId;

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal cashBalance = BigDecimal.ZERO;

    @Version
    private long version;

    protected WalletAccount() {}
    public WalletAccount(Long userId) { this.userId = userId; }
    public WalletAccount(Long userId, BigDecimal opening) { this.userId = userId; this.cashBalance = opening; }

    public void credit(BigDecimal amount) { cashBalance = cashBalance.add(amount); }
    public void debit(BigDecimal amount)  { cashBalance = cashBalance.subtract(amount); }
    public Long getUserId() { return userId; }
    public BigDecimal getCashBalance() { return cashBalance; }
}
