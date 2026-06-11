package com.exchange.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Rate limiting key: authenticated user id when present, else client IP.
 * The actual limiting is Spring Cloud Gateway's RequestRateLimiter filter
 * backed by Redis (token bucket via atomic Lua script) -- see application.yml.
 * Redis-backed = limits are shared across ALL gateway replicas.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    KeyResolver principalOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null) return Mono.just("u:" + userId);
            return Mono.just("ip:" + Objects.requireNonNull(
                    exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress());
        };
    }
}
