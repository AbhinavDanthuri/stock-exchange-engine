package com.exchange.portfolio.service;

import com.exchange.common.events.TradeExecutedEvent;
import com.exchange.portfolio.entity.Holding;
import com.exchange.portfolio.entity.TransactionRecord;
import com.exchange.portfolio.entity.WalletAccount;
import com.exchange.portfolio.repository.HoldingRepository;
import com.exchange.portfolio.repository.WalletRepository;
import com.exchange.portfolio.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Settles each trade into both parties' wallets and holdings.
 *
 * Consistency model: SAGA-style eventual consistency. The trade already
 * happened (the engine is authoritative); settlement must therefore never
 * "fail permanently" -- it retries on optimistic-lock conflicts and falls back
 * to a dead-letter topic for manual ops intervention.
 *
 * Idempotency: the (tradeId, userId) unique constraint + existence check make
 * replayed Kafka deliveries harmless.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final HoldingRepository holdings;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;

    public SettlementService(HoldingRepository holdings, WalletRepository wallets,
                             TransactionRepository transactions) {
        this.holdings = holdings; this.wallets = wallets; this.transactions = transactions;
    }

    @Retryable(retryFor = OptimisticLockingFailureException.class,
               maxAttempts = 5, backoff = @Backoff(delay = 100, multiplier = 2))
    @Transactional
    public void settle(TradeExecutedEvent trade) {
        if (transactions.existsByTradeIdAndUserId(trade.tradeId(), trade.buyerId())) {
            log.debug("trade {} already settled, skipping", trade.tradeId());
            return;
        }
        BigDecimal gross = trade.price().multiply(BigDecimal.valueOf(trade.quantity()));

        // Buyer: -cash, +shares
        wallet(trade.buyerId()).debit(gross);
        holding(trade.buyerId(), trade.symbol()).add(trade.quantity());
        transactions.save(new TransactionRecord(trade.tradeId(), trade.buyerId(), trade.symbol(),
                "BUY", trade.price(), trade.quantity(), trade.executedAt()));

        // Seller: +cash, -shares
        wallet(trade.sellerId()).credit(gross);
        holding(trade.sellerId(), trade.symbol()).remove(trade.quantity());
        transactions.save(new TransactionRecord(trade.tradeId(), trade.sellerId(), trade.symbol(),
                "SELL", trade.price(), trade.quantity(), trade.executedAt()));

        log.info("settled trade {} {} x{} @ {}", trade.tradeId(), trade.symbol(), trade.quantity(), trade.price());
    }

    private WalletAccount wallet(Long userId) {
        return wallets.findByUserId(userId)
                .orElseGet(() -> wallets.save(new WalletAccount(userId, new BigDecimal("100000.0000")))); // demo opening balance
    }

    private Holding holding(Long userId, String symbol) {
        return holdings.findByUserIdAndSymbol(userId, symbol)
                .orElseGet(() -> holdings.save(new Holding(userId, symbol)));
    }
}
