package com.exchange.portfolio.repository;

import com.exchange.portfolio.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepository extends JpaRepository<Holding, Long> {
    Optional<Holding> findByUserIdAndSymbol(Long userId, String symbol);
    List<Holding> findByUserId(Long userId);
}