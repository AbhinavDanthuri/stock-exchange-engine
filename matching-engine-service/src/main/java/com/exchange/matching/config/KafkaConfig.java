package com.exchange.matching.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    // 6 partitions per topic = up to 6 parallel engine instances per consumer group.
    @Bean NewTopic ordersPlaced()    { return TopicBuilder.name("orders.placed").partitions(6).replicas(1).build(); }
    @Bean NewTopic ordersCancel()    { return TopicBuilder.name("orders.cancel-requested").partitions(6).replicas(1).build(); }
    @Bean NewTopic tradesExecuted()  { return TopicBuilder.name("trades.executed").partitions(6).replicas(1).build(); }
    @Bean NewTopic ordersStatus()    { return TopicBuilder.name("orders.status").partitions(6).replicas(1).build(); }
}
