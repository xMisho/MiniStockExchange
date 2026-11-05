package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.misho.exchange.*;
import com.misho.exchange.stock.*;
import com.misho.exchange.user.*;
import com.misho.exchange.marketdata.MarketEvents;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="verdex.risk.enabled=true")
class TradingReliabilityTest {
    @Autowired TradingService trading;
    @Autowired UserService users;
    @Autowired StockRepository stocks;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.misho.exchange.workspace.PortfolioService portfolio;
    @MockitoSpyBean TradeRepository trades;
    @MockitoSpyBean MarketEvents events;
    @MockitoSpyBean MarketRegistry registry;
    String symbol;
    long buyer, seller, admin;
    final List<Long> owners = new ArrayList<>();
    long user(String role) {
        String name = "reliable_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
        long id = users.registerUser(new RegisterUserRequest(name,name+"@example.test","ReliabilityPassword123!")).id();
        owners.add(id); if (role.equals("ADMIN")) jdbc.update("UPDATE exchange.users SET role='ADMIN' WHERE id=?",id);
        return id;
    }
    @BeforeEach void setup() {
        symbol="REL"+UUID.randomUUID().toString().replace("-", "").substring(0,10).toUpperCase();
        stocks.saveAndFlush(new StockEntity(symbol,"Reliability fixture",new BigDecimal("100")));
        buyer=user("USER"); seller=user("USER"); admin=user("ADMIN");
        trading.allocate(admin,seller,symbol,10);
        clearInvocations(events);
    }
    @AfterEach void cleanup() {
        reset(trades, registry);
        jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol=?",symbol);
        jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol=?",symbol);
        jdbc.update("DELETE FROM exchange.paper_allocations WHERE stock_symbol=?",symbol);
        jdbc.update("DELETE FROM exchange.stocks WHERE symbol=?",symbol);
        for(long id:owners) jdbc.update("DELETE FROM exchange.users WHERE id=?",id);
    }
    PersistedOrder submit(long user, OrderSide side, String price, int quantity, String key) {
        return trading.submitOrder(user,symbol,side,price==null?OrderType.MARKET:OrderType.LIMIT,
                price==null?null:new BigDecimal(price),quantity,key);
    }
    @Test void duplicatesReplayOriginalResultAfterFillsAndRecoveryWithoutNewTrades() {
        String key=UUID.randomUUID().toString();
        var first=submit(buyer,OrderSide.BUY,"90",4,key);
        submit(seller,OrderSide.SELL,"90",4,UUID.randomUUID().toString());
        assertEquals(OrderStatus.FILLED,trading.getOrder(buyer,first.id()).status());
        trading.recoverMarket(symbol);
        var replay=submit(buyer,OrderSide.BUY,"90.0000",4,key);
        assertEquals(first,replay);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM exchange.trades WHERE stock_symbol=?",Integer.class,symbol));
        assertThrows(TradingConflictException.class,()->submit(buyer,OrderSide.BUY,"91",4,key));
        assertNotEquals(first.id(),submit(seller,OrderSide.BUY,"90",4,key).id());
    }
    @Test void simultaneousIdenticalKeysCreateExactlyOneOrder() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            String key=UUID.randomUUID().toString();
            var tasks=new ArrayList<Callable<Long>>();
            for(int i=0;i<8;i++) tasks.add(()->submit(buyer,OrderSide.BUY,"80",1,key).id());
            Set<Long> ids=new HashSet<>();for(var f:pool.invokeAll(tasks))ids.add(f.get());
            assertEquals(1,ids.size());
        } finally {pool.shutdownNow();}
    }
    @Test void sharesCashAmendmentAndCancellationReserveAtomically() {
        assertThrows(TradingConflictException.class,()->submit(buyer,OrderSide.SELL,"100",1,UUID.randomUUID().toString()));
        var resting=submit(seller,OrderSide.SELL,"100",10,UUID.randomUUID().toString());
        assertEquals(0,trading.balance(seller,symbol).availableShares());
        assertThrows(TradingConflictException.class,()->submit(seller,OrderSide.SELL,"100",1,UUID.randomUUID().toString()));
        submit(buyer,OrderSide.BUY,null,5,UUID.randomUUID().toString());
        assertEquals(5,trading.balance(seller,symbol).reservedShares());
        assertEquals(5,trading.balance(buyer,symbol).availableShares());
        assertThrows(TradingConflictException.class,()->trading.amendOrder(seller,resting.id(),new BigDecimal("100"),6));
        assertTrue(trading.cancelOrder(seller,resting.id()));
        assertEquals(5,trading.balance(seller,symbol).availableShares());
        var buy=submit(buyer,OrderSide.BUY,"90",100,UUID.randomUUID().toString());
        assertEquals(0,new BigDecimal("9000").compareTo(trading.balance(buyer,symbol).reservedCash()));
        assertThrows(TradingConflictException.class,()->trading.amendOrder(buyer,buy.id(),new BigDecimal("1000"),100));
        assertEquals(100,trading.getOrder(buyer,buy.id()).remainingQuantity());
        assertTrue(trading.cancelOrder(buyer,buy.id()));
        assertEquals(0,trading.balance(buyer,symbol).reservedCash().signum());
    }
    @Test void marketBuysUseCurrentExecutableCostAndNoLiquidityReservesNothing() {
        assertThrows(TradingConflictException.class,()->submit(buyer,OrderSide.BUY,"1000",101,UUID.randomUUID().toString()));
        submit(seller,OrderSide.SELL,"20000",6,UUID.randomUUID().toString());
        assertThrows(TradingConflictException.class,()->submit(buyer,OrderSide.BUY,null,6,UUID.randomUUID().toString()));
        assertThrows(TradingConflictException.class,()->trading.submitOrder(buyer,symbol,OrderSide.SELL,OrderType.MARKET,null,1,UUID.randomUUID().toString()));
        // Buyer still has no shares, so sells must fail rather than shorting even without liquidity.
    }
    @Test void rollbackDoesNotConsumeKeyReserveFundsOrEmitAnEvent() {
        String key=UUID.randomUUID().toString();
        doThrow(new org.springframework.dao.DataIntegrityViolationException("injected failure")).when(trades).saveAllAndFlush(any());
        assertThrows(TradingOperationException.class,()->submit(buyer,OrderSide.BUY,"80",1,key));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM exchange.order_requests WHERE user_id=?",Integer.class,buyer));
        assertEquals(0,trading.balance(buyer,symbol).reservedCash().signum());
        verify(events,never()).committed(symbol);
        reset(trades);
        var result=submit(buyer,OrderSide.BUY,"80",1,key);
        assertEquals(result,submit(buyer,OrderSide.BUY,"80",1,key));
        verify(events,times(1)).committed(symbol);
    }
    @Test void committedSubmissionCanReplayEvenIfPublicationAndRecoveryFail() {
        String key=UUID.randomUUID().toString();
        trading.getMarketSnapshot(symbol); // Load the engine before injecting a post-commit publication failure.
        doThrow(new IllegalStateException("publication failure")).when(registry).publish(eq(symbol),any());
        assertThrows(TradingOperationException.class,()->submit(buyer,OrderSide.BUY,"80",1,key));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM exchange.order_requests WHERE user_id=?",Integer.class,buyer));
        var replay=submit(buyer,OrderSide.BUY,"80",1,key);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM exchange.orders WHERE stock_symbol=?",Integer.class,symbol));
        assertEquals(OrderStatus.OPEN,replay.status());
        reset(registry);trading.recoverMarket(symbol);
    }

    @Test void publicationOccursAfterTransactionAndAllocationsAreAdminOnlyAndFunded() {
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(events).committed(symbol);
        submit(buyer,OrderSide.BUY,"80",1,UUID.randomUUID().toString());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->trading.allocate(buyer,buyer,symbol,1));
        assertThrows(TradingConflictException.class,()->trading.allocate(admin,buyer,symbol,1001));
        assertEquals(0,new BigDecimal("99000").compareTo(portfolio.portfolio(seller).cash()));
        assertEquals(10,portfolio.portfolio(seller).positions().get(0).quantity());
    }
}
