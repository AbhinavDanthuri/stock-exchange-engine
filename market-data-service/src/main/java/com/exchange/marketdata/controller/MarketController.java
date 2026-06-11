package com.exchange.marketdata.controller;

import com.exchange.marketdata.service.TickerCacheService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final TickerCacheService ticker;

    public MarketController(TickerCacheService ticker) {
        this.ticker = ticker;
    }

    /** Served straight from Redis -- no DB hit, < 1 ms. */
    @GetMapping("/ticker/{symbol}")
    public Map<String, String> ticker(@PathVariable String symbol) {
        return ticker.get(symbol.toUpperCase());
    }
}
