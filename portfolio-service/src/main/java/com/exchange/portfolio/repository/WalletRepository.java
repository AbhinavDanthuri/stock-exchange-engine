package com.exchange.portfolio.repository;

import com.exchange.portfolio.entity.WalletAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<WalletAccount, Long> {
    Optional<WalletAccount> findByUserId(Long userId);
}