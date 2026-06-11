package com.exchange.gateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/orders")
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> ordersFallback() {
        return Map.of("error", "order-service temporarily unavailable, please retry");
    }
}
