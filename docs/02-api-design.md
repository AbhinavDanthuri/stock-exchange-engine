# REST API design

All requests go through the gateway at `http://localhost:8080`.
Authenticated routes require `Authorization: Bearer <jwt>`.
The gateway injects `X-User-Id` / `X-User-Role` downstream — clients can never spoof these (gateway strips them).

## Auth (user-service)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | /api/auth/register | `{username, email, password}` | 201 `{token, userId, username, role}` |
| POST | /api/auth/login | `{username, password}` | 200 `{token, ...}` / 401 |

## Orders (order-service)

| Method | Path | Notes |
|---|---|---|
| POST | /api/orders | 202 Accepted — matching is async. Optional `Idempotency-Key` header. |
| DELETE | /api/orders/{id} | 202 — cancel *request*; engine decides (may already be filled) |
| GET | /api/orders?page=&size= | own orders, newest first |
| GET | /api/orders/{id} | single order with live status |

Place order body:
```json
{ "symbol": "AAPL", "side": "BUY", "type": "LIMIT", "price": 101.50, "quantity": 10 }
```
Market order: `"type": "MARKET"`, omit `price`.

Validation: LIMIT requires price; MARKET forbids price; quantity > 0;
ownership enforced on read/cancel; 409 on idempotency-key reuse.

## Portfolio (portfolio-service)

| Method | Path | Returns |
|---|---|---|
| GET | /api/portfolio | `{cashBalance, positions:[{symbol, quantity}]}` |
| GET | /api/portfolio/transactions?page=&size= | immutable trade legs |

## Market data (market-data-service)

| Method | Path | Notes |
|---|---|---|
| GET | /api/market/ticker/{symbol} | served from Redis, no DB |
| WS | /ws (SockJS+STOMP) | subscribe `/topic/trades.{SYM}`, `/topic/ticker.{SYM}`, `/topic/ticker.all` |

## Internal

| Method | Path | Notes |
|---|---|---|
| GET | matching-engine `/internal/book/{symbol}?depth=10` | live book snapshot (not exposed via gateway) |

## Status codes philosophy
202 for anything the engine decides asynchronously; 4xx only for things the
synchronous service can know (validation, ownership, idempotency conflict).
Order *rejection by the engine* (e.g. NO_LIQUIDITY market order) surfaces via
order status, not an HTTP error — the request was validly accepted.
