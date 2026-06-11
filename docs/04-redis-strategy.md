# Redis caching strategy

| Key pattern | Type | TTL | Writer | Pattern |
|---|---|---|---|---|
| `ticker:{SYMBOL}` | hash | none | market-data (from trade stream) | write-through from events |
| `ticker:symbols` | set | none | market-data | registry of active symbols |
| `idem:order:{userId}:{key}` | string | 10 min | order-service | SETNX idempotency guard |
| `request_rate_limiter.{u:id|ip:x}` | strings | ~seconds | gateway (Lua) | token bucket, shared by all gateway replicas |

Design points worth saying out loud in an interview:

1. **Cache what is read-hot and write-streamed.** Ticker data is written once
   per trade and read constantly (REST + every reconnecting WS client) —
   perfect for a write-through hash. No DB behind it at all: Redis *is* the
   ticker store; durable truth remains the trades in Kafka/MySQL.
2. **Atomicity choices.** `HINCRBY` for volume (concurrent market-data
   replicas can't lose increments); plain read-modify-write is acceptable for
   high/low because the merge function is monotonic max/min.
3. **Idempotency keys** use `SET NX EX` — the canonical distributed
   "first writer wins" primitive; TTL bounds memory.
4. **Rate limiting** uses Spring Cloud Gateway's Redis token bucket (atomic
   Lua script): limits hold across gateway replicas, unlike in-memory buckets.
5. **What we deliberately don't cache:** order status (changes too fast,
   correctness-critical → read from MySQL) and the order book itself (lives
   in engine memory; snapshots are served by the engine, optionally
   materialized to Redis as `book:{SYMBOL}` for resilience).
