package com.exchange.marketdata.service;

import com.exchange.common.events.TradeExecutedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis caching strategy (market data side):
 *
 *  Key                      Type   TTL    Purpose
 *  ticker:{SYMBOL}          hash   none   last price, day volume, high, low
 *  ticker:symbols           set    none   all active symbols (for /api/market/tickers)
 *
 * Redis hashes give atomic HINCRBY for volume; multiple market-data instances
 * can apply trades concurrently without losing updates. Cache-aside applies on
 * reads (REST below); writes here are write-through from the Kafka stream.
 */
@Service
public class TickerCacheService {

    private final StringRedisTemplate redis;

    public TickerCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Map<String, String> applyTrade(TradeExecutedEvent t) {
        String key = "ticker:" + t.symbol();
        var ops = redis.opsForHash();

        ops.put(key, "symbol", t.symbol());
        ops.put(key, "lastPrice", t.price().toPlainString());
        ops.put(key, "lastTradeAt", t.executedAt().toString());
        redis.opsForHash().increment(key, "volume", t.quantity());

        // high / low maintenance (read-modify-write acceptable: monotonic values)
        Object high = ops.get(key, "high");
        if (high == null || t.price().compareTo(new java.math.BigDecimal(high.toString())) > 0)
            ops.put(key, "high", t.price().toPlainString());
        Object low = ops.get(key, "low");
        if (low == null || t.price().compareTo(new java.math.BigDecimal(low.toString())) < 0)
            ops.put(key, "low", t.price().toPlainString());

        redis.opsForSet().add("ticker:symbols", t.symbol());

        Map<String, String> tick = new HashMap<>();
        ops.entries(key).forEach((k, v) -> tick.put(k.toString(), v.toString()));
        return tick;
    }

    public Map<String, String> get(String symbol) {
        Map<String, String> out = new HashMap<>();
        redis.opsForHash().entries("ticker:" + symbol).forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }
}
