# Deployment guide

## Prerequisites
Docker + Docker Compose v2, ~6 GB free RAM. (For local non-Docker dev: JDK 21, Maven 3.9.)

## One-command bring-up
```bash
docker compose up --build -d
docker compose ps                    # wait until all healthy/started
```
Startup order is handled by healthchecks: MySQL/Redis/Kafka first, then
discovery, then services. First build takes a few minutes (Maven downloads).

## Verify
```bash
# 1. Eureka dashboard — all services registered?
open http://localhost:8761

# 2. Register two traders
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@x.com","password":"password123"}'
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"bob","email":"bob@x.com","password":"password123"}'
# capture the two tokens as $ALICE and $BOB

# 3. Bob offers 10 AAPL at 101
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","side":"SELL","type":"LIMIT","price":101.00,"quantity":10}'

# 4. Alice lifts the offer with a market buy
curl -s -X POST localhost:8080/api/orders -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' \
  -d '{"symbol":"AAPL","side":"BUY","type":"MARKET","quantity":10}'

# 5. Watch it live: open http://localhost:8085 (WebSocket feed page)
#    Check portfolios:
curl -s localhost:8080/api/portfolio -H "Authorization: Bearer $ALICE"
curl -s localhost:8080/api/portfolio/transactions -H "Authorization: Bearer $ALICE"

# 6. Ticker straight from Redis:
curl -s localhost:8080/api/market/ticker/AAPL
```

## Scaling demo
```bash
docker compose up -d --scale order-service=3 --scale matching-engine-service=2
```
Gateway load-balances order-service via Eureka; the two engine instances
split the 6 Kafka partitions (≈3 each), i.e. each owns a disjoint symbol set.

## Configuration
Everything overridable via env vars (12-factor): `MYSQL_HOST`, `REDIS_HOST`,
`KAFKA_BOOTSTRAP`, `EUREKA_URL`, `JWT_SECRET`. Change `JWT_SECRET` anywhere
outside local dev.

## Production deltas (talk track, not in compose)
Kubernetes manifests with HPA; Kafka 3-broker cluster, replication.factor=3,
min.insync.replicas=2; MySQL primary + read replicas; managed Redis;
RS256 JWTs with key rotation; secrets in a vault; Prometheus + Grafana +
Loki/ELK; OpenTelemetry tracing across the Kafka hops.
