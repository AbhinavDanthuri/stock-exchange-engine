package com.exchange.marketdata.kafka;

import com.exchange.common.events.TradeExecutedEvent;
import com.exchange.marketdata.service.TickerCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka -> WebSocket bridge. groupId includes the instance id so EVERY
 * market-data replica gets EVERY trade (broadcast semantics, not work-sharing).
 */
@Component
public class TradeBroadcastConsumer {

    private final SimpMessagingTemplate ws;
    private final TickerCacheService ticker;

    public TradeBroadcastConsumer(SimpMessagingTemplate ws, TickerCacheService ticker) {
        this.ws = ws;
        this.ticker = ticker;
    }

    @KafkaListener(topics = "trades.executed",
                   groupId = "market-data-#{T(java.util.UUID).randomUUID().toString()}")
    public void onTrade(TradeExecutedEvent t) {
        // 1. update Redis ticker cache (shared across instances)
        var tick = ticker.applyTrade(t);

        // 2. push to subscribers in real time
        ws.convertAndSend("/topic/trades." + t.symbol(), Map.of(
                "tradeId", t.tradeId(), "symbol", t.symbol(),
                "price", t.price(), "quantity", t.quantity(), "executedAt", t.executedAt().toString()));
        ws.convertAndSend("/topic/ticker." + t.symbol(), tick);
        ws.convertAndSend("/topic/ticker.all", tick);
    }
}
