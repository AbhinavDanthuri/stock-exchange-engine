package com.exchange.portfolio.controller;

import com.exchange.portfolio.entity.Holding;
import com.exchange.portfolio.entity.TransactionRecord;
import com.exchange.portfolio.repository.HoldingRepository;
import com.exchange.portfolio.repository.WalletRepository;
import com.exchange.portfolio.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final HoldingRepository holdings;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;

    public PortfolioController(HoldingRepository holdings, WalletRepository wallets,
                               TransactionRepository transactions) {
        this.holdings = holdings; this.wallets = wallets; this.transactions = transactions;
    }

    @GetMapping
    public Map<String, Object> portfolio(@RequestHeader("X-User-Id") Long userId) {
        BigDecimal cash = wallets.findByUserId(userId)
                .map(w -> w.getCashBalance()).orElse(BigDecimal.ZERO);
        List<Map<String, Object>> positions = holdings.findByUserId(userId).stream()
                .filter(h -> h.getQuantity() != 0)
                .map(h -> Map.<String, Object>of("symbol", h.getSymbol(), "quantity", h.getQuantity()))
                .toList();
        return Map.of("userId", userId, "cashBalance", cash, "positions", positions);
    }

    @GetMapping("/transactions")
    public List<TransactionRecord> transactions(@RequestHeader("X-User-Id") Long userId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return transactions.findByUserIdOrderByExecutedAtDesc(userId, PageRequest.of(page, size)).getContent();
    }
}
