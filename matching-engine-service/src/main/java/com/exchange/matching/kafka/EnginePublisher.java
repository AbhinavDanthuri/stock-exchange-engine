package com.exchange.matching.kafka;

import com.exchange.common.events.OrderStatusChangedEvent;
import com.exchange.common.events.TradeExecutedEvent;
import com.exchange.matching.model.Trade;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class EnginePublisher {

    public static final String TOPIC_TRADES = "trades.executed";
    public static final String TOPIC_ORDER_STATUS = "orders.status";

    private final KafkaTemplate<String, Object> kafka;

    public EnginePublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publishTrade(Trade t) {
        var evt = new TradeExecutedEvent(t.tradeId(), t.symbol(), t.buyOrderId(), t.sellOrderId(),
                t.buyerId(), t.sellerId(), t.price(), t.quantity(), t.executedAt());
        kafka.send(TOPIC_TRADES, t.symbol(), evt); // key=symbol keeps per-symbol ordering
    }

    public void publishStatus(OrderStatusChangedEvent evt) {
        kafka.send(TOPIC_ORDER_STATUS, evt.symbol(), evt);
    }
}
