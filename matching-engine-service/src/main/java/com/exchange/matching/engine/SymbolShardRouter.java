package com.exchange.matching.engine;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Concurrency strategy of the engine: SHARDED SINGLE-WRITER.
 *
 * Instead of wrapping each OrderBook in locks (contention, priority inversion,
 * non-determinism), we partition symbols across N single-threaded executors:
 *
 *      shard(symbol) = abs(symbol.hashCode()) % N
 *
 * All commands for a symbol (place, cancel, snapshot) are submitted to that
 * symbol's shard, so each OrderBook is only ever touched by one thread.
 * Benefits:
 *   - lock-free hot path, no contention inside a book
 *   - per-symbol total ordering (matches Kafka partitioning by symbol key)
 *   - deterministic, replayable matching -- crucial for audit in finance
 *   - scales horizontally: run multiple engine instances, each consuming a
 *     disjoint set of Kafka partitions; symbols never span instances.
 *
 * Platform threads (not virtual) are deliberate here: these are CPU-bound,
 * long-lived event loops -- exactly the case where pinned platform threads win.
 */
@Component
public class SymbolShardRouter {

    private final ExecutorService[] shards;

    public SymbolShardRouter(@Value("${engine.shards:4}") int shardCount) {
        this.shards = new ExecutorService[shardCount];
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "matching-shard-" + n.getAndIncrement());
            t.setDaemon(false);
            return t;
        };
        for (int i = 0; i < shardCount; i++) {
            shards[i] = Executors.newSingleThreadExecutor(tf);
        }
    }

    public <T> CompletableFuture<T> submit(String symbol, Supplier<T> command) {
        int shard = Math.floorMod(symbol.hashCode(), shards.length);
        return CompletableFuture.supplyAsync(command, shards[shard]);
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        for (ExecutorService s : shards) s.shutdown();
        for (ExecutorService s : shards) s.awaitTermination(5, TimeUnit.SECONDS);
    }
}
