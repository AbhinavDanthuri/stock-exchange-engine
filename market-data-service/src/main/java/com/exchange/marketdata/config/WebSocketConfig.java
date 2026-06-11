package com.exchange.marketdata.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket. Clients connect to /ws and subscribe to:
 *   /topic/trades.{SYMBOL}   -- every executed trade
 *   /topic/ticker.{SYMBOL}   -- last price / OHLC-lite tick
 *   /topic/ticker.all        -- firehose of all symbols
 *
 * Horizontal scaling note: with multiple market-data instances, switch
 * enableSimpleBroker -> enableStompBrokerRelay (external RabbitMQ/Artemis) so
 * messages fan out across instances; alternatively use Redis pub/sub.
 * Because every instance independently consumes Kafka with a UNIQUE group id,
 * each instance already receives all trades -- the simple broker works for
 * N instances too, at the cost of duplicate Kafka consumption.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
