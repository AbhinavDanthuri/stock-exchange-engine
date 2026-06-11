# Kafka event flow

## Topics

| Topic | Key | Producer | Consumers | Purpose |
|---|---|---|---|---|
| orders.placed | symbol | order-service | matching-engine | new orders into the book |
| orders.cancel-requested | symbol | order-service | matching-engine | async cancel commands |
| trades.executed | symbol | matching-engine | portfolio-service, market-data-service | executions |
| orders.status | symbol | matching-engine | order-service | ACCEPTED / PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED |
| *-dlt | — | retry framework | ops | poison messages after retries |

All topics: 6 partitions. **Key = symbol** is the load-bearing decision:
- per-symbol total ordering (a cancel can never overtake the order it cancels)
- natural sharding for horizontal engine scaling
- hot-symbol skew is the known cost (discussed in interview Q&A)

## End-to-end flow: placing a limit buy

```
client ──POST /api/orders──► gateway ──► order-service
                                            │ 1. validate + idempotency (Redis SETNX)
                                            │ 2. INSERT orders (status=NEW)
                                            │ 3. send orders.placed (key=AAPL)
                                            ▼
                                     kafka[orders.placed]
                                            │
                                            ▼
                                    matching-engine
                                            │ 4. dedupe orderId
                                            │ 5. route to shard(AAPL)
                                            │ 6. match against book
                                            ├──► kafka[trades.executed] ──► portfolio-service (settle wallets+holdings)
                                            │                          └──► market-data-service (Redis ticker + WebSocket push)
                                            └──► kafka[orders.status]  ──► order-service (UPDATE orders SET status, filled_qty)
```

Client sees 202 immediately with status NEW; the UI learns about fills over
the WebSocket or by polling GET /api/orders/{id}.

## Delivery semantics

- Producers: `acks=all`, `enable.idempotence=true` → no broker-side duplicates on retry.
- Consumers: at-least-once. Every consumer is idempotent:
  - engine: `seenOrderIds` set
  - order-service: status update converges (same input → same row state)
  - portfolio: `(trade_id, user_id)` unique constraint
- Failure isolation: `@RetryableTopic` exponential backoff → DLT.

## Why Kafka and not RabbitMQ here
Need: replayable ordered log (engine recovery via replay), per-key ordering,
high-throughput fan-out to multiple consumer groups. That is Kafka's exact
shape. RabbitMQ fits task-queue / routing-key workloads better.
