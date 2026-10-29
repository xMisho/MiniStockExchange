package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;

import com.misho.exchange.MatchingEngine;
import com.misho.exchange.MiniStockExchangeApplication;
import com.misho.exchange.Order;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(120)
class TradingServiceTest {

    @Autowired private TradingService service;
    @Autowired private StockRepository stocks;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private MarketLoader loader;
    @MockitoSpyBean private MarketRegistry registry;
    @MockitoSpyBean private TradeRepository trades;

    private final List<String> symbols = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private Long ownerId;
    private String symbol;

    private Long newUser(boolean admin) {
        String username = "trader_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Long id = users.registerUser(new RegisterUserRequest(username,
                username + "@example.test", "TradingTestPassword123!")).id();
        userIds.add(id);
        if (admin) { jdbc.update("UPDATE exchange.users SET role = 'ADMIN' WHERE id = ?", id); }
        return id;
    }

    private Long owner() {
        if (ownerId == null) { ownerId = newUser(false); }
        return ownerId;
    }

    private String symbol() {
        if (symbol == null) {
            symbol = "LIVE" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                    .toUpperCase(java.util.Locale.ROOT);
            symbols.add(symbol);
            stocks.saveAndFlush(new StockEntity(symbol, "Persistent Matching Test", new BigDecimal("100")));
        }
        return symbol;
    }

    private PersistedOrder limit(OrderSide side, String price, int quantity) {
        return service.submitOrder(owner(), symbol(), side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM exchange." + table + " WHERE stock_symbol = ?",
                Integer.class, symbol());
    }

    @AfterEach
    void cleanup() {
        reset(loader, registry, trades);
        for (String stock : symbols) {
            jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol = ?", stock);
            jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol = ?", stock);
            stocks.deleteById(stock);
        }
        for (Long user : userIds) { jdbc.update("DELETE FROM exchange.users WHERE id = ?", user); }
        service.restoreOnStartup();
    }

    @Test
    void savesSubmissionPartialFillsAndMarketRemainderAtomically() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        PersistedOrder buy = limit(OrderSide.BUY, "105", 2);
        assertEquals(OrderStatus.FILLED, buy.status());
        var partial = service.getOrder(owner(), sell.id());
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.status());
        assertEquals(3, partial.remainingQuantity());
        assertEquals(2, partial.filledQuantity());
        assertEquals(1, count("trades"));
        PersistedOrder market = service.submitOrder(owner(), symbol(), OrderSide.BUY, OrderType.MARKET, null, 10);
        assertEquals(OrderStatus.CANCELLED, market.status());
        assertEquals(7, market.remainingQuantity());
        assertEquals(3, market.filledQuantity());
        assertEquals(OrderStatus.FILLED, service.getOrder(owner(), sell.id()).status());
        assertEquals(2, count("trades"));
        assertEquals(5, service.getMarketSnapshot(symbol()).volume());
        assertEquals(0, service.getMarketSnapshot(symbol()).openOrderCount());
    }

    @Test
    void marketWithNoLiquidityIsPersistedAsCancelledNotResting() {
        PersistedOrder order = service.submitOrder(owner(), symbol(), OrderSide.BUY, OrderType.MARKET, null, 4);
        assertEquals(OrderStatus.CANCELLED, order.status());
        assertEquals(4, order.remainingQuantity());
        assertEquals(0, order.filledQuantity());
        assertEquals(0, count("trades"));
        service.recoverMarket(symbol());
        assertEquals(0, service.getMarketSnapshot(symbol()).openOrderCount());
    }

    @Test
    void oneIncomingOrderCreatesSeveralTradesAtRestingPrices() {
        limit(OrderSide.SELL, "100", 3);
        limit(OrderSide.SELL, "101", 4);
        PersistedOrder third = limit(OrderSide.SELL, "102", 8);
        PersistedOrder buy = limit(OrderSide.BUY, "105", 10);
        assertEquals(OrderStatus.FILLED, buy.status());
        var history = service.getTradeHistory(symbol());
        assertEquals(List.of(3, 4, 3), history.stream().map(t -> t.getQuantity()).toList());
        assertEquals(List.of(new BigDecimal("100.0000"), new BigDecimal("101.0000"), new BigDecimal("102.0000")),
                history.stream().map(t -> t.getExecutionPrice()).toList());
        assertEquals(5, service.getOrder(owner(), third.id()).remainingQuantity());
        assertEquals(new BigDecimal("101.0000"), service.getMarketSnapshot(symbol()).vwap());
    }

    @Test
    void reductionKeepsFifoAndNoOpDoesNotChangeVersionOrPriority() {
        PersistedOrder first = limit(OrderSide.SELL, "100", 5);
        PersistedOrder second = limit(OrderSide.SELL, "100", 5);
        assertTrue(service.amendOrder(owner(), first.id(), new BigDecimal("100"), 5));
        assertEquals(first, service.getOrder(owner(), first.id()));
        assertTrue(service.amendOrder(owner(), first.id(), new BigDecimal("100"), 3));
        assertEquals(first.prioritySequence(), service.getOrder(owner(), first.id()).prioritySequence());
        limit(OrderSide.BUY, "100", 4);
        var history = service.getTradeHistory(symbol());
        assertEquals(first.id().longValue(), history.get(0).getSellOrderId());
        assertEquals(second.id().longValue(), history.get(1).getSellOrderId());
        assertEquals(4, service.getOrder(owner(), second.id()).remainingQuantity());
    }

    @Test
    void priceAmendmentMatchesImmediatelyAtRestingBuyPrice() {
        PersistedOrder buy = limit(OrderSide.BUY, "105", 5);
        PersistedOrder sell = limit(OrderSide.SELL, "110", 5);
        assertTrue(service.amendOrder(owner(), sell.id(), new BigDecimal("100"), 3));
        var saved = service.getOrder(owner(), sell.id());
        assertEquals(5, saved.originalQuantity());
        assertEquals(3, saved.filledQuantity());
        assertEquals(OrderStatus.FILLED, saved.status());
        assertTrue(saved.prioritySequence() > sell.prioritySequence());
        assertEquals(new BigDecimal("105.0000"), service.getTradeHistory(symbol()).get(0).getExecutionPrice());
        assertEquals(2, service.getOrder(owner(), buy.id()).remainingQuantity());
        assertFalse(service.amendOrder(owner(), sell.id(), null, -1));
        assertFalse(service.cancelOrder(owner(), sell.id()));
        assertFalse(service.amendOrder(owner(), Long.MAX_VALUE, null, -1));
    }

    @Test
    void userCannotManageAnotherOwnersOrderButAdminCan() {
        PersistedOrder order = limit(OrderSide.SELL, "100", 5);
        Long other = newUser(false);
        assertThrows(AccessDeniedException.class, () -> service.getOrder(other, order.id()));
        assertThrows(AccessDeniedException.class, () -> service.cancelOrder(other, order.id()));
        assertThrows(AccessDeniedException.class, () -> service.amendOrder(other, order.id(), BigDecimal.TEN, 2));
        assertTrue(service.getOrders(other).isEmpty());
        Long admin = newUser(true);
        assertTrue(service.amendOrder(admin, order.id(), new BigDecimal("99"), 2));
        assertEquals(owner(), service.getOrder(admin, order.id()).userId());
        assertTrue(service.cancelOrder(admin, order.id()));
        assertEquals(OrderStatus.CANCELLED, service.getOrder(owner(), order.id()).status());
    }

    @Test
    void realRestartRestoresQuantitiesFifoAndAnalyticsBeforeAcceptingAnotherOrder() {
        PersistedOrder first = limit(OrderSide.SELL, "100", 5);
        PersistedOrder second = limit(OrderSide.SELL, "100", 5);
        limit(OrderSide.BUY, "100", 2);
        assertTrue(service.amendOrder(owner(), first.id(), new BigDecimal("100"), 8));
        PersistedOrder cancelled = limit(OrderSide.BUY, "99", 4);
        assertTrue(service.cancelOrder(owner(), cancelled.id()));
        var before = service.getMarketSnapshot(symbol());
        var candles = service.getOhlcCandles(symbol());
        long timestamp = service.getTradeHistory(symbol()).get(0).getTimestamp();
        try (var restarted = new SpringApplicationBuilder(MiniStockExchangeApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run()) {
            TradingService recovered = restarted.getBean(TradingService.class);
            var after = recovered.getMarketSnapshot(symbol());
            assertEquals(before.volume(), after.volume());
            assertEquals(before.tradeCount(), after.tradeCount());
            assertEquals(before.vwap(), after.vwap());
            assertEquals(before.lastTradedPrice(), after.lastTradedPrice());
            assertEquals(before.sellDepth(), after.sellDepth());
            assertEquals(before.buyDepth(), after.buyDepth());
            assertEquals(8, recovered.getOrder(owner(), first.id()).remainingQuantity());
            assertEquals(2, recovered.getOrder(owner(), first.id()).filledQuantity());
            assertEquals(first.createdAt(), recovered.getOrder(owner(), first.id()).createdAt());
            assertEquals(timestamp, recovered.getTradeHistory(symbol()).get(0).getTimestamp());
            assertEquals(candles.keySet(), recovered.getOhlcCandles(symbol()).keySet());
            for (Long minute : candles.keySet()) {
                var a = candles.get(minute);
                var b = recovered.getOhlcCandles(symbol()).get(minute);
                assertEquals(a.getOpen(), b.getOpen());
                assertEquals(a.getHigh(), b.getHigh());
                assertEquals(a.getLow(), b.getLow());
                assertEquals(a.getClose(), b.getClose());
            }
            PersistedOrder incoming = recovered.submitOrder(owner(), symbol(), OrderSide.BUY, OrderType.MARKET, null, 6);
            assertTrue(incoming.id() > cancelled.id());
            var history = recovered.getTradeHistory(symbol());
            assertEquals(3, history.size());
            assertEquals(second.id().longValue(), history.get(1).getSellOrderId());
            assertEquals(5, history.get(1).getQuantity());
            assertEquals(first.id().longValue(), history.get(2).getSellOrderId());
            assertEquals(7, recovered.getOrder(owner(), first.id()).remainingQuantity());
        }
        service.recoverMarket(symbol());
        assertEquals(3, service.getMarketSnapshot(symbol()).tradeCount());
    }

    @Test
    void databaseFailureAfterMatchingRollsBackOrdersAndTradesWithoutMutatingPublishedEngine() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        MatchingEngine published = registry.get(symbol());
        doAnswer(invocation -> {
            jdbc.execute("SELECT 1 / 0");
            return invocation.callRealMethod();
        }).when(trades).saveAllAndFlush(org.mockito.ArgumentMatchers.<TradeEntity>anyList());
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertEquals(5, published.getMarketSnapshot().sellDepth().firstEntry().getValue());
        assertRollbackBaseline(sell);
    }

    @Test
    void engineExceptionAfterActualCandidateFillsDoesNotLeakMutations() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        AtomicBoolean inject = new AtomicBoolean(true);
        doAnswer(invocation -> {
            LoadedMarket real = (LoadedMarket) invocation.callRealMethod();
            if (!inject.getAndSet(false)) { return real; }
            MatchingEngine candidate = spy(real.engine());
            doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Injected engine failure"); })
                    .when(candidate).submitOrder(any(Order.class));
            return new LoadedMarket(real.stock(), real.orders(), candidate, real.previousTradeCount());
        }).when(loader).load(symbol());
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertRollbackBaseline(sell);
    }

    @Test
    void failureImmediatelyBeforeCommitRollsBackAlreadyFlushedChanges() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        injectTransactionFailure(false);
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertRollbackBaseline(sell);
    }

    @Test
    void exceptionAfterDatabaseCommitReconcilesCommittedStateWithoutReexecutingTrade() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        injectTransactionFailure(true);
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertEquals(2, count("orders"));
        assertEquals(1, count("trades"));
        assertEquals(3, service.getOrder(owner(), sell.id()).remainingQuantity());
        assertEquals(1, service.getMarketSnapshot(symbol()).tradeCount());
        assertEquals(2, service.getMarketSnapshot(symbol()).volume());
        assertTrue(service.cancelOrder(owner(), sell.id()));
        assertEquals(1, count("trades"));
    }

    @Test
    void publicationFailureAfterCommitRebuildsTheCommittedEngine() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        doThrow(new IllegalStateException("Injected publication failure")).doCallRealMethod()
                .when(registry).publish(eq(symbol()), any(MatchingEngine.class));
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertEquals(1, count("trades"));
        assertEquals(3, service.getOrder(owner(), sell.id()).remainingQuantity());
        assertEquals(1, service.getMarketSnapshot(symbol()).tradeCount());
    }

    @Test
    void failedRecoveryMakesTheMarketUnavailableUntilExplicitRecovery() {
        PersistedOrder sell = limit(OrderSide.SELL, "100", 5);
        doCallRealMethod().doThrow(new IllegalStateException("Injected recovery failure"))
                .when(loader).load(symbol());
        doThrow(new DataIntegrityViolationException("Injected save failure"))
                .when(trades).saveAllAndFlush(org.mockito.ArgumentMatchers.<TradeEntity>anyList());
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 2));
        assertThrows(IllegalStateException.class, () -> service.getMarketSnapshot(symbol()));
        assertThrows(IllegalStateException.class, () -> limit(OrderSide.BUY, "100", 1));
        assertEquals(5, service.getOrder(owner(), sell.id()).remainingQuantity());
        reset(loader, trades);
        service.recoverMarket(symbol());
        assertEquals(1, service.getMarketSnapshot(symbol()).openOrderCount());
    }

    @Test
    void startupRejectsUnfinishedMarketOrdersInsteadOfDiscardingThem() {
        owner(); symbol();
        jdbc.update("INSERT INTO exchange.orders(user_id,stock_symbol,side,order_type,original_quantity,remaining_quantity,filled_quantity,status) "
                + "VALUES (?, ?, 'BUY','MARKET',1,1,0,'OPEN')", owner(), symbol());
        assertThrows(RuntimeException.class, () -> {
            try (var ignored = new SpringApplicationBuilder(MiniStockExchangeApplication.class)
                    .profiles("test").web(WebApplicationType.NONE)
                    .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run()) { }
        });
        assertEquals(1, count("orders"));
        assertEquals(0, count("trades"));
    }

    @Test
    void recoveryRejectsCrossedBooksAndFillHistoryMismatchWithoutInventingExecutions() {
        PersistedOrder buy = limit(OrderSide.BUY, "100", 5);
        jdbc.update("INSERT INTO exchange.orders(user_id,stock_symbol,side,order_type,limit_price,original_quantity,remaining_quantity,filled_quantity,status) "
                + "VALUES (?, ?, 'SELL','LIMIT',100,5,5,0,'OPEN')", owner(), symbol());
        assertThrows(IllegalStateException.class, () -> service.recoverMarket(symbol()));
        assertEquals(0, count("trades"));
        jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol = ? AND side = 'SELL'", symbol());
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=4,filled_quantity=1,status='PARTIALLY_FILLED' WHERE id=?", buy.id());
        assertThrows(IllegalStateException.class, () -> service.recoverMarket(symbol()));
        assertThrows(IllegalStateException.class, () -> service.getMarketSnapshot(symbol()));
        assertEquals(0, count("trades"));
    }

    @Test
    void tradingCannotCommitInsideACallersTransaction() {
        owner(); symbol();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> limit(OrderSide.BUY, "100", 1)));
        assertEquals(0, count("orders"));
    }

    @Test
    void concurrentCommandsAndSnapshotReadsHaveNoLostFillsOrDeadlock() throws Exception {
        Long actor = owner(); String stock = symbol();
        var workers = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 4; t++) {
                OrderSide side = t % 2 == 0 ? OrderSide.BUY : OrderSide.SELL;
                futures.add(workers.submit(() -> {
                    start.await();
                    for (int i = 0; i < 15; i++) {
                        service.submitOrder(actor, stock, side, OrderType.LIMIT, new BigDecimal("100"), 1);
                    }
                    return null;
                }));
            }
            futures.add(workers.submit(() -> {
                start.await();
                for (int i = 0; i < 150; i++) {
                    var snapshot = service.getMarketSnapshot(stock);
                    assertEquals(snapshot.tradeCount(), snapshot.volume());
                    if (snapshot.tradeCount() > 0) { assertNotNull(snapshot.lastTradedPrice()); }
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) { future.get(45, TimeUnit.SECONDS); }
        } finally { workers.shutdownNow(); }
        assertEquals(60, count("orders"));
        assertEquals(30, count("trades"));
        assertEquals(30, service.getMarketSnapshot(stock).volume());
        assertEquals(0, service.getMarketSnapshot(stock).openOrderCount());
        service.recoverMarket(stock);
        assertEquals(30, service.getMarketSnapshot(stock).tradeCount());
    }

    @Test
    void cancellationAndAmendmentFailuresPreserveStateAndOriginalFifo() {
        PersistedOrder first = limit(OrderSide.SELL, "100", 5);
        limit(OrderSide.SELL, "100", 5);
        doThrow(new DataIntegrityViolationException("Injected state persistence failure"))
                .when(trades).saveAllAndFlush(org.mockito.ArgumentMatchers.<TradeEntity>anyList());
        assertThrows(TradingOperationException.class,
                () -> service.amendOrder(owner(), first.id(), new BigDecimal("99"), 8));
        assertEquals(first, service.getOrder(owner(), first.id()));
        assertThrows(TradingOperationException.class, () -> service.cancelOrder(owner(), first.id()));
        assertEquals(first, service.getOrder(owner(), first.id()));
        assertEquals(2, service.getMarketSnapshot(symbol()).openOrderCount());
        reset(trades);
        limit(OrderSide.BUY, "100", 1);
        assertEquals(first.id().longValue(), service.getTradeHistory(symbol()).get(0).getSellOrderId());
    }

    @Test
    void readersWaitForCommitAndNeverSeeTheUncommittedCandidate() throws Exception {
        limit(OrderSide.SELL, "100", 5);
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        AtomicBoolean inject = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            if (inject.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        beforeCommit.countDown();
                        try {
                            if (!releaseCommit.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Commit wait timed out"); }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }
                });
            }
            return loaded;
        }).when(loader).load(symbol());
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writer = workers.submit(() -> limit(OrderSide.BUY, "100", 2));
            assertTrue(beforeCommit.await(10, TimeUnit.SECONDS));
            var reader = workers.submit(() -> service.getMarketSnapshot(symbol()));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
            releaseCommit.countDown();
            writer.get(10, TimeUnit.SECONDS);
            assertEquals(1, reader.get(10, TimeUnit.SECONDS).tradeCount());
            assertEquals(1, count("trades"));
        } finally {
            releaseCommit.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void unknownStockReadDoesNotPermanentlyDisableAFutureListing() {
        owner();
        symbol = "NEW" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(java.util.Locale.ROOT);
        symbols.add(symbol);
        assertThrows(IllegalArgumentException.class, () -> service.getMarketSnapshot(symbol));
        stocks.saveAndFlush(new StockEntity(symbol, "New Listing", new BigDecimal("100")));
        limit(OrderSide.BUY, "99", 1);
        assertEquals(1, service.getMarketSnapshot(symbol).openOrderCount());
    }

    @Test
    void cumulativeFillOverflowAbortsTheCandidateWithoutLosingRemainingQuantity() {
        owner(); symbol();
        String insert = "INSERT INTO exchange.orders(user_id,stock_symbol,side,order_type,limit_price,original_quantity,"
                + "remaining_quantity,filled_quantity,status) VALUES (?, ?, ?, 'LIMIT',100,2147483647,?,2147483647,?) RETURNING id";
        Long buy = jdbc.queryForObject(insert, Long.class, owner(), symbol(), "BUY", 0, "FILLED");
        Long sell = jdbc.queryForObject(insert, Long.class, owner(), symbol(), "SELL", 1, "PARTIALLY_FILLED");
        jdbc.update("INSERT INTO exchange.trades(stock_symbol,buy_order_id,sell_order_id,execution_price,quantity,executed_at) "
                + "VALUES (?, ?, ?, 100,2147483647,CURRENT_TIMESTAMP)", symbol(), buy, sell);
        service.recoverMarket(symbol());
        assertThrows(TradingOperationException.class, () -> limit(OrderSide.BUY, "100", 1));
        assertEquals(2, count("orders"));
        assertEquals(1, count("trades"));
        assertEquals(1, service.getOrder(owner(), sell).remainingQuantity());
        assertEquals(Integer.MAX_VALUE, service.getOrder(owner(), sell).filledQuantity());
        assertEquals(Integer.MAX_VALUE, service.getMarketSnapshot(symbol()).volume());
    }

    @Test
    void restorationReservesTerminalIdsWithoutReplayingTheirFills() {
        var buy = new com.misho.exchange.OrderSnapshot(1, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("100"), 2, 0, 2, OrderStatus.FILLED, 1000);
        var sell = new com.misho.exchange.OrderSnapshot(2, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("100"), 2, 0, 2, OrderStatus.FILLED, 1000);
        var cancelled = new com.misho.exchange.OrderSnapshot(3, OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("99"), 5, 5, 0, OrderStatus.CANCELLED, 1000);
        MatchingEngine restored = MatchingEngine.restore(new com.misho.exchange.Stock("TEST", "Test", BigDecimal.TEN),
                List.of(buy, sell, cancelled), List.of(new com.misho.exchange.Trade(1, 2, new BigDecimal("100"), 2, 2000)));
        assertEquals(0, restored.getOpenOrderCount());
        assertEquals(1, restored.getTradeCount());
        assertEquals(2, restored.getTotalTradedVolume());
        assertThrows(IllegalArgumentException.class, () -> restored.submitOrder(new Order(1, OrderSide.BUY, BigDecimal.TEN, 1)));
        assertThrows(IllegalArgumentException.class, () -> restored.submitOrder(new Order(3, OrderSide.BUY, BigDecimal.TEN, 1)));
    }

    @Test
    void failedFullRestorationNeverLeavesAnOldCachedMarketReadable() {
        PersistedOrder order = limit(OrderSide.SELL, "100", 5);
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=4,filled_quantity=1,status='PARTIALLY_FILLED' WHERE id=?", order.id());
        assertThrows(IllegalStateException.class, service::restoreOnStartup);
        assertThrows(IllegalStateException.class, () -> service.getMarketSnapshot(symbol()));
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=5,filled_quantity=0,status='OPEN' WHERE id=?", order.id());
        service.recoverMarket(symbol());
        assertEquals(1, service.getMarketSnapshot(symbol()).openOrderCount());
    }

    private void assertRollbackBaseline(PersistedOrder sell) {
        assertEquals(1, count("orders"));
        assertEquals(0, count("trades"));
        assertEquals(5, service.getOrder(owner(), sell.id()).remainingQuantity());
        assertEquals(0, service.getOrder(owner(), sell.id()).filledQuantity());
        assertEquals(0, service.getMarketSnapshot(symbol()).tradeCount());
    }

    private void injectTransactionFailure(boolean afterCommit) {
        AtomicBoolean inject = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            if (inject.getAndSet(false)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        if (!afterCommit) { throw new IllegalStateException("Injected before-commit failure"); }
                    }
                    @Override public void afterCommit() {
                        if (afterCommit) { throw new IllegalStateException("Injected after-commit failure"); }
                    }
                });
            }
            return loaded;
        }).when(loader).load(symbol());
    }
}
