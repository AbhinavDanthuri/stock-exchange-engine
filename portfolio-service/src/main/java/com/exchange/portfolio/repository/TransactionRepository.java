package com.exchange.portfolio.repository;

import com.exchange.portfolio.entity.TransactionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
    Page<TransactionRecord> findByUserIdOrderByExecutedAtDesc(Long userId, Pageable pageable);
    boolean existsByTradeIdAndUserId(String tradeId, Long userId);
}