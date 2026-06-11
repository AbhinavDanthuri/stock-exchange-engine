# Resume description + interview Q&A

## Resume entry (pick 3–4 bullets, tailor to the JD)

**Distributed Stock Exchange Engine** — Java 21, Spring Boot 3, Kafka, Redis, MySQL, Docker
*github.com/<you>/stock-exchange-engine*

- Engineered a distributed stock exchange backend with 6 Spring Boot microservices
  (API gateway, service discovery, user, order, matching engine, portfolio,
  market data) communicating through Kafka events, containerized with Docker Compose.
- Built an in-memory price-time-priority order matching engine using a sharded
  single-writer concurrency model (per-symbol thread affinity over TreeMap/FIFO
  order books), eliminating lock contention on the matching hot path while
  supporting market orders, limit orders, partial fills, and cancellations.
- Designed an event-driven flow (orders.placed → trades.executed → settlement)
  with idempotent at-least-once consumers, retry topics with dead-lettering,
  and optimistic-locking-based trade settlement into wallets and holdings.
- Implemented edge security and resilience at a Spring Cloud Gateway: JWT
  verification with identity-header injection, Redis token-bucket rate limiting
  shared across replicas, and Resilience4j circuit breakers with fallbacks.
- Delivered real-time market data via Kafka→WebSocket (STOMP) fan-out with a
  Redis write-through ticker cache; instrumented matching latency percentiles
  with Micrometer/Prometheus.
- Verified correctness with JUnit 5 + Mockito plus a multithreaded stress test
  (20k concurrent orders across 8 producer threads) asserting matching invariants.

One-liner for the summary section:
"Built a Kafka-based distributed stock exchange with a lock-free in-memory
matching engine in Java 21 / Spring Boot."

## Interview Q&A

**Q1. Walk me through what happens when a user places a buy order.**
Gateway verifies the JWT and injects X-User-Id → order-service validates
(LIMIT needs price, quantity > 0), checks the Idempotency-Key in Redis with
SETNX, inserts the order row (status NEW) in MySQL, and publishes
OrderPlacedEvent to `orders.placed` keyed by symbol, returning 202. The
matching engine consumes it, dedupes the orderId, routes it to the shard
thread owning that symbol's book, and matches it against the opposite side
(best price first, FIFO within a level). Each fill emits a TradeExecutedEvent;
the taker's final state emits an OrderStatusChangedEvent. Portfolio-service
settles both legs (debit/credit wallet, adjust holdings, write immutable
transaction rows); order-service updates the row's status; market-data pushes
the trade to WebSocket subscribers and updates the Redis ticker.

**Q2. How does the matching engine handle thousands of concurrent orders without locks?**
Sharded single-writer. Symbols are hashed onto N single-threaded executors,
so each order book is only ever touched by one thread — the data structures
(TreeMap of price levels, ArrayDeque per level, HashMap for cancel lookup)
can stay completely unsynchronized. Parallelism comes from partitioning the
keyspace, not from making one structure concurrent. Kafka reinforces this:
keying by symbol means a symbol's commands arrive in order on one partition.
This is the LMAX single-writer principle; it also makes matching deterministic
and replayable, which matters for audit.

**Q3. Why is the order book a TreeMap + Deques and what are the complexities?**
Bids in a TreeMap with reverse ordering, asks ascending — best price is
firstKey() in O(log P). Each price level is a FIFO deque, giving time priority
in O(1) per fill. Cancellation uses a HashMap orderId→Order for O(1) lookup,
then removal from its level. A production engine might use arrays of price
buckets or an intrusive doubly-linked list for O(1) cancel, but TreeMap is the
right clarity/performance balance and is what most reference implementations use.

**Q4. The engine keeps state in memory. What happens if it crashes?**
Kafka is the durable log. The consumer group's committed offsets plus
`auto-offset-reset` let a restarted instance replay `orders.placed` and apply
terminal statuses to rebuild books — classic event sourcing. To bound replay
time you add periodic book snapshots (e.g. to Redis) and replay only the tail.
MySQL never loses the system-of-record view because order rows and trades are
persisted by the other services regardless of engine restarts.

**Q5. How do you guarantee a trade isn't settled twice?**
Kafka gives at-least-once delivery, so consumers are idempotent rather than
assuming exactly-once. Settlement checks `(trade_id, user_id)` and the table
has a unique constraint on that pair — a replayed event hits the constraint
path and becomes a no-op. Same philosophy elsewhere: the engine dedupes
orderIds; the status consumer's update is naturally convergent.

**Q6. Two settlements update the same wallet concurrently. How is that safe?**
JPA @Version optimistic locking on wallet and holding rows. A conflicting
commit throws OptimisticLockingFailureException, and @Retryable re-runs the
transaction with backoff. Optimistic beats pessimistic here because conflicts
are rare (same user on both sides of simultaneous trades) and we never hold DB
locks across the consumer's processing time.

**Q7. What's the consistency model between order status in MySQL and the book?**
Eventual. The engine is authoritative for matching; MySQL converges via the
`orders.status` topic, typically within milliseconds. The API reflects this
honestly: POST returns 202 with status NEW, and clients learn fills via
WebSocket or polling. Strong consistency on the hot path would mean
synchronous DB writes inside matching — that's the throughput killer we
deliberately avoided.

**Q8. Where's the weakest link in your write path?**
The dual write in order-service: DB insert then Kafka publish. A crash between
them leaves a NEW order that never reaches the engine. Fix: transactional
outbox — write the event into an outbox table in the same DB transaction, and
let Debezium/CDC publish it. I kept the simple version in code and documented
the outbox as the production upgrade. (Volunteering this is better than being
caught by it.)

**Q9. Why Kafka over RabbitMQ?**
I needed a replayable, partitioned, ordered log: replay powers engine recovery;
partition-by-symbol powers both ordering and horizontal engine scaling;
consumer groups give independent fan-out to settlement and market data.
RabbitMQ is excellent for task queues and complex routing, but it's a message
*broker*, not a *log* — replay and per-key ordering aren't its native shape.

**Q10. How does this scale horizontally? What's the bottleneck?**
Stateless tiers scale by replicas. The engine scales by Kafka partitions:
6 partitions → up to 6 instances, each owning a disjoint symbol set. The hard
ceiling is a single hot symbol — its book is inherently sequential (that's true
of real exchanges too). Mitigations: faster per-thread code, more partitions to
isolate the hot symbol on dedicated hardware. Cross-symbol throughput is
embarrassingly parallel.

**Q11. How is the system secured?**
BCrypt(12) password hashes; HS256 JWTs issued by user-service and verified at
the gateway edge; the gateway strips client-supplied X-User-* headers and
injects verified ones, so internal services trust a single authentication
point; internal services aren't exposed outside the Docker network; per-user
Redis token-bucket rate limiting protects against abuse. Production deltas:
RS256 with key rotation, short-lived tokens + refresh, mTLS between services.

**Q12. Why BigDecimal for prices and long for quantities?**
Binary floating point cannot exactly represent most decimal fractions
(0.1 + 0.2 != 0.3), and rounding drift in money is a correctness bug, not a
display issue. DECIMAL(18,4) in MySQL mirrors it. Quantities are whole shares,
so long is exact and fast.

**Q13. What do market orders do when there's not enough liquidity?**
They fill whatever is available and the remainder is cancelled — market orders
never rest on the book (an unbounded resting market order is a price-risk
hazard). Zero liquidity → REJECTED with NO_LIQUIDITY. Limit orders rest at
their price after any immediate partial fill. Also note: trades always execute
at the maker's price, so an aggressive limit buy gets price improvement.

**Q14. How would you add funds checking so users can't buy beyond their balance?**
A synchronous risk check before Kafka publish: order-service calls
portfolio-service to *reserve* cash (buy) or shares (sell) — a saga step.
On FILLED, the reservation converts to settlement; on CANCELLED/REJECTED,
a compensating release. That's the standard saga-with-reservation pattern for
distributed transactions without 2PC, and it's the first feature I'd add next.

**Q15. How do you observe and debug this in production?**
Micrometer → Prometheus: engine.match.latency p50/p99, trades counter, Kafka
consumer lag, JVM metrics; /actuator/health for liveness. Every order has a
UUID flowing through every event and log line, giving correlation; the
audit_log table records each state transition. Next step: OpenTelemetry
tracing so one trace spans HTTP → Kafka → engine → settlement.

## 60-second project pitch
"I built a distributed stock exchange in Java 21 and Spring Boot — six
microservices behind an API gateway, integrated through Kafka. The core is an
in-memory matching engine: price-time-priority order books with a sharded
single-writer concurrency model, so there are no locks on the matching hot
path and matching stays deterministic. Orders flow in as Kafka events keyed by
symbol, trades flow out to a settlement service that updates wallets with
optimistic locking, and to a market-data service that pushes live prices over
WebSockets with a Redis ticker cache. Everything is idempotent because Kafka
is at-least-once, MySQL stays the system of record, and the whole stack runs
with one docker compose command. The interesting trade-offs were keeping the
database off the matching hot path and handling the dual-write problem —
happy to go deep on either."
