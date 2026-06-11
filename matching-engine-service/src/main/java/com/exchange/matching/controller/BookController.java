package com.exchange.matching.controller;

import com.exchange.matching.engine.MatchingEngineService;
import com.exchange.matching.engine.OrderBook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/** Internal/diagnostic API: live order-book snapshot straight from memory. */
@RestController
public class BookController {

    private final MatchingEngineService engine;

    public BookController(MatchingEngineService engine) {
        this.engine = engine;
    }

    @GetMapping("/internal/book/{symbol}")
    public CompletableFuture<OrderBook.Snapshot> snapshot(@PathVariable String symbol,
                                                          @RequestParam(defaultValue = "10") int depth) {
        return engine.snapshot(symbol.toUpperCase(), depth);
    }
}
