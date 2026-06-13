# Scalable Distributed Stock Exchange Engine

A production-style distributed stock exchange backend: six Spring Boot
microservices, an in-memory lock-free matching engine, Kafka event flows,
Redis caching/rate-limiting, real-time WebSocket market data, MySQL
persistence — all on Docker Compose..

```
Java 21 · Spring Boot 3.3 · Spring Cloud Gateway · Eureka · Kafka (KRaft)
Redis · MySQL 8 · WebSocket/STOMP · JWT · Docker · JUnit 5 · Mockito
```

## Quick start
```bash
docker compose up --build -d        # full stack
open http://localhost:8761          # Eureka
open http://localhost:8085          # live trade feed (WebSocket demo page)
```
Then follow `docs/07-deployment-guide.md` for a scripted buy/sell walkthrough.

## Services
| Service | Port | Role |
|---|---|---|
| api-gateway | 8080 | JWT edge auth, Redis rate limiting, circuit breakers, routing |
| discovery-server | 8761 | Eureka service registry |
| user-service | 8081 | registration, login, JWT issuance, stock listings |
| order-service | 8082 | order intake/cancel, MySQL system of record, audit log |
| matching-engine-service | 8084 | in-memory price-time-priority matching (the core) |
| portfolio-service | 8083 | trade settlement, wallets, holdings, transaction history |
| market-data-service | 8085 | Kafka→WebSocket fan-out, Redis ticker cache |

## Documentation
1. [Architecture & concurrency model](docs/01-architecture.md)
2. [REST API design](docs/02-api-design.md)
3. [Kafka event flow](docs/03-kafka-event-flow.md)
4. [Redis strategy](docs/04-redis-strategy.md)
5. [Database schema & ERD](docs/05-database-and-erd.md)
6. [Testing strategy](docs/06-testing-strategy.md)
7. [Deployment guide](docs/07-deployment-guide.md)
8. [Resume bullets & interview Q&A](docs/08-resume-and-interview.md)

## The matching engine in one paragraph
Each symbol has an order book (`TreeMap` price levels, FIFO deques, O(1)
cancel index). Books are never locked: symbols are sharded onto
single-threaded executors (`SymbolShardRouter`), so exactly one thread ever
touches a book — the LMAX single-writer principle. Kafka keys events by symbol,
preserving per-symbol order end-to-end. Trades publish to `trades.executed`;
settlement, persistence, and live market data all hang off that event stream.

## Build & test locally (without Docker)
```bash
mvn -q -pl matching-engine-service -am test    # order book + concurrency tests
mvn -q package -DskipTests                     # all jars
```

## Project layout
```
common-events/            shared Kafka event records (the service contracts)
discovery-server/         Eureka
api-gateway/              edge: JWT, rate limit, circuit breaker
user-service/             auth + reference data
order-service/            intake, persistence, audit, status consumer
matching-engine-service/  OrderBook, SymbolShardRouter, MatchingEngineService
portfolio-service/        SettlementService (saga, optimistic locking)
market-data-service/      WebSocket broadcast + Redis ticker
sql/init.sql              full DDL (3 schemas)
docs/                     all design docs + interview prep
docker-compose.yml        one-command stack
Dockerfile                shared multi-stage build (SERVICE build-arg)
```
