# Architecture

## High-level diagram

```
                                   ┌─────────────────────┐
                                   │   Clients (web/CLI)  │
                                   │  REST + WebSocket    │
                                   └──────────┬───────────┘
                                              │ :8080
                              ┌───────────────▼────────────────┐
                              │          API GATEWAY           │
                              │  Spring Cloud Gateway          │
                              │  • JWT verification (edge)     │
                              │  • Redis token-bucket rate     │
                              │    limiting (per user / IP)    │
                              │  • Resilience4j circuit breaker│
                              │  • lb:// routing via Eureka    │
                              └───┬─────────┬─────────┬────┬───┘
            X-User-Id header ▲    │         │         │    │
                                  │         │         │    │
        ┌──────────────┐   ┌──────▼───┐ ┌───▼──────┐ ┌▼────▼─────────┐
        │ DISCOVERY    │◄──┤  USER    │ │  ORDER   │ │ PORTFOLIO /   │
        │ Eureka :8761 │   │ SERVICE  │ │ SERVICE  │ │ MARKET-DATA   │
        └──────────────┘   │  :8081   │ │  :8082   │ │ :8083 / :8085 │
                           └────┬─────┘ └────┬─────┘ └───┬─────▲─────┘
                                │            │           │     │ WebSocket /ws
                           MySQL│       MySQL│ +Redis    │MySQL│ +Redis
                       (users,  │    (orders,│ (idem-    │(holdings, wallets,
                        stocks) │     audit) │  potency) │ transactions)
                                             │           │
                                   publish   │           │ consume
                                orders.placed│           │trades.executed
                              orders.cancel- │           │
                                   requested ▼           │
                       ╔═════════════════════════════════╧════════════╗
                       ║                  KAFKA                       ║
                       ║  orders.placed ──────► (key = symbol)        ║
                       ║  orders.cancel-requested ─►                  ║
                       ║  ◄────── trades.executed                     ║
                       ║  ◄────── orders.status                       ║
                       ╚═════════════╦═══════════════════▲════════════╝
                                     │ consume           │ publish
                          ┌──────────▼───────────────────┴──────────┐
                          │       MATCHING ENGINE SERVICE :8084     │
                          │  • in-memory order books (per symbol)   │
                          │  • TreeMap price levels + FIFO queues   │
                          │  • sharded single-writer concurrency    │
                          │  • price-time priority matching         │
                          │  • NO database on the hot path          │
                          └─────────────────────────────────────────┘
```

## Why this shape

**The matching engine is deliberately stateless-looking but stateful in memory.**
Real exchanges (NSE, NASDAQ) never put a database on the matching hot path —
a single MySQL round trip (~1 ms) would cap throughput at ~1k orders/sec.
Instead: the order book lives in memory, every state change is *derived from
the Kafka event log*, and MySQL (in order-service) remains the queryable
system of record updated asynchronously. On restart the engine can rebuild
books by replaying `orders.placed` / `orders.status` from Kafka (event
sourcing — see Recovery below).

**Per-symbol ordering end-to-end.** Kafka messages are keyed by symbol →
all AAPL orders land in one partition → consumed in order → routed to the one
shard thread that owns AAPL's book. No locks, no races, deterministic matching.

**Database-per-service.** Three separate MySQL schemas (`exchange_users`,
`exchange_orders`, `exchange_portfolio`). Services never touch each other's
tables; they integrate only through Kafka events and REST. This is what makes
each service independently deployable and scalable.

## Concurrency model (the interview centerpiece)

Two layers:

1. **Across symbols — sharded single-writer.** `SymbolShardRouter` maps
   `symbol → hash % N` single-threaded executors. Each `OrderBook` is touched
   by exactly one thread, ever. This is the LMAX "single writer principle":
   instead of making the data structure thread-safe (locks → contention →
   unpredictable latency), make the *access pattern* single-threaded and get
   parallelism by partitioning the keyspace.
2. **Within the pipeline — lock-free handoffs.** Kafka listener threads
   (concurrency=3) hand orders to shard executors via their queues;
   results flow out through `CompletableFuture` callbacks to the Kafka
   producer, which has its own I/O thread. No shared mutable state crosses
   threads except through queues.

Thread-safety inventory:
| Structure | Type | Why safe |
|---|---|---|
| `books` registry | `ConcurrentHashMap` | concurrent `computeIfAbsent` from listener threads |
| `seenOrderIds` | `ConcurrentHashMap.newKeySet()` | lock-free dedupe of Kafka replays |
| `sequenceGenerator` | `AtomicLong` | CAS, no lock |
| `OrderBook` internals | plain `TreeMap`/`ArrayDeque`/`HashMap` | single-writer per shard — intentionally unsynchronized |

## Scaling each tier

| Tier | How it scales |
|---|---|
| Gateway | stateless → N replicas behind a load balancer; rate-limit state in Redis is shared |
| user/order/portfolio | stateless → N replicas; MySQL read replicas for query load |
| Matching engine | partition-parallel: 6 Kafka partitions → up to 6 engine instances; each instance owns a disjoint symbol set. Scale further by adding partitions. |
| Market data | each replica consumes the full trade stream (unique group id) and serves its own WebSocket clients |
| Kafka / Redis / MySQL | standard clustering (out of scope for the demo compose file) |

## Recovery & fault tolerance

- **Engine crash:** books are in memory. Recovery options implemented/documented:
  (a) replay `orders.placed` minus terminal `orders.status` from Kafka
  (compacted topic or from offset 0), or (b) periodic snapshot of books to Redis
  + replay tail. The demo uses Kafka replay via `auto-offset-reset: earliest`.
- **Consumer failures:** `@RetryableTopic` → exponential backoff retries →
  dead-letter topic (`*-dlt`) for poison messages.
- **Gateway:** circuit breaker on order routes with fallback; Redis rate
  limiter degrades open if Redis is briefly unavailable.
- **Settlement races:** optimistic locking (`@Version`) + `@Retryable`;
  idempotency via `(trade_id, user_id)` unique constraint.

## Known trade-offs (be ready to defend these)

- **Funds are not reserved before matching.** A real broker pre-blocks cash/
  shares at order placement (risk check service in the synchronous path).
  Here settlement is post-trade; the docs discuss adding a synchronous
  `risk-service` call (or a funds-reservation saga) before Kafka publish.
- **At-least-once, not exactly-once.** Dedupe sets + DB unique constraints make
  redelivery harmless. Upgrading to Kafka transactions (read-process-write
  atomicity) is the documented next step.
- **Order-service write is "dual write" (DB then Kafka).** A crash between the
  two could lose the publish. Production fix: transactional outbox table +
  Debezium CDC. Mentioned deliberately — interviewers love this.
