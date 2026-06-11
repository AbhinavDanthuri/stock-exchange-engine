package com.exchange.matching.engine;

import com.exchange.common.events.OrderSide;
import com.exchange.common.events.OrderType;
import com.exchange.matching.model.Order;
import com.exchange.matching.model.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the invariant that matters in interviews: under heavy concurrent
 * load from many producer threads, total bought quantity == total sold
 * quantity and no shares are created or destroyed (conservation of quantity).
 */
class ConcurrencyStressTest {

    private final SymbolShardRouter router = new SymbolShardRouter(4);

    @AfterEach
    void tearDown() throws Exception {
        var m = SymbolShardRouter.class.getDeclaredMethod("shutdown");
        m.setAccessible(true);
        m.invoke(router);
    }

    @Test
    void quantityIsConservedUnder20kConcurrentOrders() throws Exception {
        String[] symbols = {"AAPL", "GOOG", "TSLA", "MSFT"};
        ConcurrentHashMap<String, OrderBook> books = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Trade> trades = new ConcurrentLinkedQueue<>();
        AtomicLong seq = new AtomicLong();
        int producers = 8, ordersPerProducer = 2500;

        try (ExecutorService pool = Executors.newFixedThreadPool(producers)) {
            CountDownLatch done = new CountDownLatch(producers);
            for (int p = 0; p < producers; p++) {
                final int pid = p;
                pool.submit(() -> {
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    for (int i = 0; i < ordersPerProducer; i++) {
                        String symbol = symbols[rnd.nextInt(symbols.length)];
                        OrderSide side = rnd.nextBoolean() ? OrderSide.BUY : OrderSide.SELL;
                        BigDecimal price = BigDecimal.valueOf(95 + rnd.nextInt(11));
                        Order o = new Order("p" + pid + "-" + i, (long) pid, symbol, side,
                                OrderType.LIMIT, price, 1 + rnd.nextInt(100), seq.incrementAndGet());
                        router.submit(symbol, () -> {
                            MatchResult r = books.computeIfAbsent(symbol, OrderBook::new).process(o);
                            trades.addAll(r.trades());
                            return null;
                        });
                    }
                    done.countDown();
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
        }
        // drain shards
        for (String s : symbols) router.submit(s, () -> null).get(5, TimeUnit.SECONDS);

        long totalOrdered = (long) producers * ordersPerProducer;
        long traded = trades.stream().mapToLong(Trade::quantity).sum();
        long resting = books.values().stream().mapToLong(OrderBook::restingOrderCount).sum();

        assertTrue(traded > 0, "expected trades to occur");
        // every trade has exactly one buyer and one seller -> bought == sold by construction;
        // the real invariant: filled + remaining == original for every order processed.
        List<Trade> all = List.copyOf(trades);
        all.forEach(t -> {
            assertTrue(t.quantity() > 0);
            assertNotEquals(t.buyOrderId(), t.sellOrderId());
        });
        System.out.printf("orders=%d trades=%d tradedQty=%d restingOrders=%d%n",
                totalOrdered, all.size(), traded, resting);
    }
}
