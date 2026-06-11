# Testing strategy

## Pyramid

1. **Unit tests (fast, no Spring context)** — the matching engine is pure
   logic, so it gets the densest coverage: `OrderBookTest` covers
   price-time priority, partial fills, maker-price execution, market-order
   sweep/rejection semantics, cancellation idempotency, snapshot aggregation.
2. **Concurrency tests** — `ConcurrencyStressTest`: 8 producer threads ×
   2,500 orders over 4 symbols through the real shard router; asserts trade
   validity invariants. Run repeatedly; failures here are race conditions.
3. **Slice tests** — `@DataJpaTest` for repositories (against H2 or
   Testcontainers-MySQL), `@WebMvcTest` + Mockito for controllers
   (validation, ownership 403s, idempotency 409s).
4. **Integration tests** — Testcontainers spins real Kafka + MySQL + Redis;
   `@SpringBootTest` posts an order and awaits (Awaitility) the status flip
   to FILLED and the portfolio delta. This proves the event chain, not just
   the pieces.
5. **End-to-end smoke** — docker compose up, then `scripts/smoke-test.sh`:
   register two users, place crossing orders, assert trade + portfolio.
6. **Load test** — k6 or Gatling against POST /api/orders; watch
   `engine.match.latency` p99 and `engine.trades.executed` in Prometheus.

## What to measure (and quote in interviews)
- Matching latency p50/p99 from Micrometer (`engine.match.latency`) — the
  in-memory match itself is microseconds; end-to-end (HTTP→WS) is dominated
  by Kafka hops (~tens of ms with default settings).
- Throughput ceiling per symbol = single shard thread speed; aggregate
  throughput scales with symbols × shards × instances.

## Mockito usage example
Controller test mocks `OrderService`, verifies that `Idempotency-Key`
propagates and that a FORBIDDEN from the service maps to HTTP 403 —
controllers are tested for the web contract, services for business rules,
never both at once.
