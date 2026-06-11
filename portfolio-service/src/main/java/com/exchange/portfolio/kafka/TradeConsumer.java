package com.exchange.portfolio.kafka;

import com.exchange.common.events.TradeExecutedEvent;
import com.exchange.portfolio.service.SettlementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class TradeConsumer {

    private final SettlementService settlement;

    public TradeConsumer(SettlementService settlement) {
        this.settlement = settlement;
    }

    @RetryableTopic(attempts = "5", backoff = @Backoff(delay = 1000, multiplier = 2.0),
                    dltTopicSuffix = "-dlt")
    @KafkaListener(topics = "trades.executed", groupId = "portfolio-service", concurrency = "3")
    public void onTrade(TradeExecutedEvent trade) {
        settlement.settle(trade);
    }
}
