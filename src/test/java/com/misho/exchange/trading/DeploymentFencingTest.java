package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.marketdata.CandleRepository;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserRepository;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(45)
class DeploymentFencingTest {
    @Autowired StockRepository stocks;
    @Autowired UserRepository users;
    @Autowired UserService registrations;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired CandleRepository candles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TradingService mainService;
    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String symbol = "FENCE" + suffix.toUpperCase();
    private Long userId;
    private Node old;
    private PersistedOrder sell;

    private record Node(TradingService service, MarketWriter writer, MarketLoader loader, MarketRegistry registry) { }

    private Node node() {
        var loader = spy(new MarketLoader(stocks, orders, trades));
        var registry = spy(new MarketRegistry());
        var writer = new MarketWriter(jdbc, true);
        return new Node(new TradingService(stocks, users, orders, trades, loader, registry,
                jdbc, transactions, candles, writer), writer, loader, registry);
    }

    @BeforeEach
    void setup() {
        stocks.saveAndFlush(new StockEntity(symbol, "Deployment fixture", BigDecimal.valueOf(100)));
        userId = registrations.registerUser(new RegisterUserRequest("fence_" + suffix,
                "fence_" + suffix + "@example.test", "DeploymentTestPassword!")).id();
        old = node();
        old.service.restoreOnStartup();
        sell = old.service.submitOrder(userId, symbol, OrderSide.SELL, OrderType.LIMIT, BigDecimal.valueOf(100), 5);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol = ?", symbol);
        jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol = ?", symbol);
        stocks.deleteById(symbol);
        if (userId != null) { jdbc.update("DELETE FROM exchange.users WHERE id = ?", userId); }
        mainService.restoreOnStartup();
    }

    private PersistedOrder buy(Node node, int quantity) {
        return node.service.submitOrder(userId, symbol, OrderSide.BUY, OrderType.MARKET, null, quantity);
    }

    private void assertUntouched() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exchange.trades WHERE stock_symbol = ?", Integer.class, symbol));
        assertEquals(5, jdbc.queryForObject("SELECT remaining_quantity FROM exchange.orders WHERE id = ?", Integer.class, sell.id()));
    }

    private void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    @Test
    void simultaneousClaimsLeaveExactlyOneWriterAndFenceAllOldMarketOperations() throws Exception {
        var first = node();
        var second = node();
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> { await(start); first.service.restoreOnStartup(); });
            var b = workers.submit(() -> { await(start); second.service.restoreOnStartup(); });
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        } finally { workers.shutdownNow(); }
        assertNotEquals(first.writer.isOwner(), second.writer.isOwner());
        Node winner = first.writer.isOwner() ? first : second;
        Node loser = first.writer.isOwner() ? second : first;
        assertThrows(IllegalStateException.class, () -> loser.service.getMarketSnapshot(symbol));
        assertThrows(IllegalStateException.class, () -> old.service.getMarketSnapshot(symbol));
        assertThrows(IllegalStateException.class, () -> old.service.preview(symbol, OrderSide.BUY, 2));
        assertThrows(IllegalStateException.class, () -> old.service.cancelOrder(userId, sell.id()));
        assertThrows(IllegalStateException.class, () -> old.service.amendOrder(userId, sell.id(), BigDecimal.valueOf(99), 2));
        assertThrows(TradingOperationException.class, () -> buy(old, 2));
        assertUntouched();
        assertEquals(5L, winner.service.getMarketSnapshot(symbol).sellDepth().firstEntry().getValue());
    }

    @Test
    void oldInFlightOrderCommitsBeforeReplacementRestorationAndIsIncludedInItsSnapshot() throws Exception {
        var beforeCommit = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object market = call.callRealMethod();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { beforeCommit.countDown(); await(release); }
            });
            return market;
        }).when(old.loader).forCommand(eq(symbol), any());
        var replacement = node();
        var workers = Executors.newFixedThreadPool(2);
        try {
            try {
                var order = workers.submit(() -> buy(old, 2));
                await(beforeCommit);
                var handoff = workers.submit(replacement.service::restoreOnStartup);
                assertThrows(TimeoutException.class, () -> handoff.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(2, order.get(10, TimeUnit.SECONDS).filledQuantity());
                handoff.get(10, TimeUnit.SECONDS);
                assertEquals(3L, replacement.service.getMarketSnapshot(symbol).sellDepth().firstEntry().getValue());
                assertEquals(1, replacement.service.getMarketSnapshot(symbol).tradeCount());
                assertThrows(IllegalStateException.class, () -> old.service.getMarketSnapshot(symbol));
            } finally { release.countDown(); }
        } finally { workers.shutdownNow(); }
    }

    @Test
    void anOrderDuringRestorationCannotCommitBetweenSnapshotAndOwnershipTransfer() throws Exception {
        var restored = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var replacement = node();
        doAnswer(call -> {
            Object market = call.callRealMethod();
            restored.countDown(); await(release);
            return market;
        }).when(replacement.loader).load(symbol);
        var workers = Executors.newFixedThreadPool(2);
        try {
            try {
                var handoff = workers.submit(replacement.service::restoreOnStartup);
                await(restored);
                var order = workers.submit(() -> assertThrows(TradingOperationException.class, () -> buy(old, 2)));
                assertThrows(TimeoutException.class, () -> order.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                handoff.get(10, TimeUnit.SECONDS); order.get(10, TimeUnit.SECONDS);
                assertUntouched();
                assertEquals(5L, replacement.service.getMarketSnapshot(symbol).sellDepth().firstEntry().getValue());
            } finally { release.countDown(); }
        } finally { workers.shutdownNow(); }
    }

    @Test
    void crashBeforeOwnershipCommitRollsBackClaimAndOldWriterKeepsWorking() {
        var replacement = node();
        doThrow(new IllegalStateException("Injected restore failure")).when(replacement.loader).load(symbol);
        assertThrows(IllegalStateException.class, replacement.service::restoreOnStartup);
        assertTrue(old.writer.isOwner());
        assertFalse(replacement.writer.isOwner());
        assertEquals(2, buy(old, 2).filledQuantity());
    }

    @Test
    void crashImmediatelyAfterOwnershipCommitFailsClosedAndRestartRecovers() {
        var replacement = node();
        doThrow(new IllegalStateException("Injected crash after commit")).when(replacement.registry).replaceAll(anyMap());
        assertThrows(IllegalStateException.class, replacement.service::restoreOnStartup);
        assertTrue(replacement.writer.isOwner());
        assertFalse(old.writer.isOwner());
        assertThrows(TradingOperationException.class, () -> buy(old, 2));
        assertUntouched();
        var restarted = node();
        restarted.service.restoreOnStartup();
        assertTrue(restarted.writer.isOwner());
        assertEquals(5L, restarted.service.getMarketSnapshot(symbol).sellDepth().firstEntry().getValue());
        assertEquals(2, buy(restarted, 2).filledQuantity());
        assertEquals(1, restarted.service.getMarketSnapshot(symbol).tradeCount());
    }
}
