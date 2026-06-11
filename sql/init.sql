-- Database-per-service: each microservice owns its schema exclusively.
-- No cross-database joins or foreign keys -- services share data via Kafka
-- events and APIs only. (FKs shown inside a schema where ownership allows.)

CREATE DATABASE IF NOT EXISTS exchange_users;
CREATE DATABASE IF NOT EXISTS exchange_orders;
CREATE DATABASE IF NOT EXISTS exchange_portfolio;
GRANT ALL PRIVILEGES ON exchange_users.*     TO 'exchange'@'%';
GRANT ALL PRIVILEGES ON exchange_orders.*    TO 'exchange'@'%';
GRANT ALL PRIVILEGES ON exchange_portfolio.* TO 'exchange'@'%';
FLUSH PRIVILEGES;

-- ============ exchange_users (user-service) ============
USE exchange_users;

CREATE TABLE IF NOT EXISTS users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(50)  NOT NULL UNIQUE,
  email         VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role          VARCHAR(20)  NOT NULL DEFAULT 'TRADER',
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_users_email (email)
) ENGINE=InnoDB;

-- Stock listing reference data (owned by user-service in this build for
-- simplicity; would be its own reference-data-service at larger scale)
CREATE TABLE IF NOT EXISTS stocks (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  symbol       VARCHAR(10)  NOT NULL UNIQUE,
  company_name VARCHAR(120) NOT NULL,
  tick_size    DECIMAL(10,4) NOT NULL DEFAULT 0.01,
  lot_size     INT          NOT NULL DEFAULT 1,
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  listed_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;

INSERT IGNORE INTO stocks (symbol, company_name) VALUES
  ('AAPL', 'Apple Inc.'), ('GOOG', 'Alphabet Inc.'),
  ('TSLA', 'Tesla Inc.'), ('MSFT', 'Microsoft Corp.'),
  ('AMZN', 'Amazon.com Inc.'), ('INFY', 'Infosys Ltd.');

-- ============ exchange_orders (order-service) ============
USE exchange_orders;

CREATE TABLE IF NOT EXISTS orders (
  id              VARCHAR(36)  PRIMARY KEY,          -- UUID
  user_id         BIGINT       NOT NULL,             -- logical ref (no FK across services)
  symbol          VARCHAR(10)  NOT NULL,
  side            VARCHAR(4)   NOT NULL,             -- BUY | SELL
  type            VARCHAR(6)   NOT NULL,             -- MARKET | LIMIT
  price           DECIMAL(18,4) NULL,                -- NULL for MARKET
  quantity        BIGINT       NOT NULL,
  filled_quantity BIGINT       NOT NULL DEFAULT 0,
  status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
  reject_reason   VARCHAR(64)  NULL,
  version         BIGINT       NOT NULL DEFAULT 0,   -- optimistic lock
  created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at      TIMESTAMP(6) NULL,
  INDEX idx_orders_user (user_id, created_at),
  INDEX idx_orders_symbol_status (symbol, status),
  CONSTRAINT chk_qty CHECK (quantity > 0),
  CONSTRAINT chk_filled CHECK (filled_quantity BETWEEN 0 AND quantity)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_type   VARCHAR(30)  NOT NULL,
  entity_id     VARCHAR(36)  NOT NULL,
  action        VARCHAR(40)  NOT NULL,
  actor_user_id BIGINT       NULL,
  detail        TEXT         NULL,
  occurred_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  INDEX idx_audit_entity (entity_type, entity_id)
) ENGINE=InnoDB;

-- ============ exchange_portfolio (portfolio-service) ============
USE exchange_portfolio;

CREATE TABLE IF NOT EXISTS wallet_accounts (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id      BIGINT        NOT NULL UNIQUE,
  cash_balance DECIMAL(18,4) NOT NULL DEFAULT 0,
  version      BIGINT        NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS holdings (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id  BIGINT      NOT NULL,
  symbol   VARCHAR(10) NOT NULL,
  quantity BIGINT      NOT NULL DEFAULT 0,
  version  BIGINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uq_holding_user_symbol (user_id, symbol)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transactions (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  trade_id     VARCHAR(36)   NOT NULL,
  user_id      BIGINT        NOT NULL,
  symbol       VARCHAR(10)   NOT NULL,
  side         VARCHAR(4)    NOT NULL,
  price        DECIMAL(18,4) NOT NULL,
  quantity     BIGINT        NOT NULL,
  gross_amount DECIMAL(18,4) NOT NULL,
  executed_at  TIMESTAMP(6)  NOT NULL,
  UNIQUE KEY uq_txn_trade_user (trade_id, user_id),   -- settlement idempotency
  INDEX idx_txn_user_time (user_id, executed_at)
) ENGINE=InnoDB;
