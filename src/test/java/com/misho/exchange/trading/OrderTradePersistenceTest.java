package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.misho.exchange.MiniStockExchangeApplication;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserEntity;
import com.misho.exchange.user.UserRepository;
import com.misho.exchange.user.UserService;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderTradePersistenceTest {

    private static final Instant CREATED = Instant.parse("2026-10-29T00:00:00Z");

    @Autowired private OrderRepository orders;
    @Autowired private TradeRepository trades;
    @Autowired private StockRepository stocks;
    @Autowired private UserRepository users;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagers;

    private final List<String> symbols = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private UserEntity owner;
    private StockEntity stock;

    private UserEntity newUser() {
        String name = "model_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Long id = userService.registerUser(new RegisterUserRequest(
                name, name + "@example.test", "PersistenceTestPassword123!")).id();
        userIds.add(id);
        return users.findById(id).orElseThrow();
    }

    private UserEntity owner() {
        if (owner == null) { owner = newUser(); }
        return owner;
    }

    private StockEntity newStock() {
        String symbol = "MODEL" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        symbols.add(symbol);
        return stocks.saveAndFlush(new StockEntity(symbol, "Persistence Test Company", new BigDecimal("100")));
    }

    private StockEntity stock() {
        if (stock == null) { stock = newStock(); }
        return stock;
    }

    private OrderEntity order(OrderSide side, OrderType type, int quantity) {
        return orders.saveAndFlush(new OrderEntity(owner(), stock(), side, type,
                type == OrderType.LIMIT ? new BigDecimal("100.1234") : null,
                quantity, orders.nextPrioritySequence(), CREATED));
    }

    @AfterEach
    void removeOnlyThisTestsRecordsInForeignKeyOrder() {
        for (String symbol : symbols) {
            jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol = ?", symbol);
        }
        for (String symbol : symbols) {
            jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol = ?", symbol);
            stocks.deleteById(symbol);
        }
        for (Long id : userIds) { users.deleteById(id); }
    }

    @Test
    void savesOwnedOrderAndLoadsItsExactMetadata() {
        OrderEntity saved = order(OrderSide.BUY, OrderType.LIMIT, 10);
        OrderEntity loaded = orders.findById(saved.getId()).orElseThrow();
        assertTrue(loaded.getId() > 0);
        assertEquals(owner().getId(), loaded.getUser().getId());
        assertEquals(stock().getSymbol(), orders.findRestingOrders(stock().getSymbol()).get(0).getStock().getSymbol());
        assertEquals(OrderSide.BUY, loaded.getSide());
        assertEquals(OrderType.LIMIT, loaded.getOrderType());
        assertEquals(new BigDecimal("100.1234"), loaded.getLimitPrice());
        assertEquals(10, loaded.getOriginalQuantity());
        assertEquals(10, loaded.getRemainingQuantity());
        assertEquals(0, loaded.getFilledQuantity());
        assertEquals(OrderStatus.OPEN, loaded.getStatus());
        assertEquals(CREATED, loaded.getCreatedAt());
        assertEquals(CREATED, loaded.getUpdatedAt());
        assertEquals(0L, loaded.getVersion());
        assertEquals(List.of(saved.getId()), orders.findUsedOrderIds(stock().getSymbol()));
    }

    @Test
    void amendedQuantityCanExceedOriginalWithoutLosingFillHistory() {
        OrderEntity saved = order(OrderSide.BUY, OrderType.LIMIT, 10);
        saved.updateState(saved.getLimitPrice(), 6, 4, OrderStatus.PARTIALLY_FILLED,
                saved.getPrioritySequence(), CREATED.plusSeconds(1));
        saved = orders.saveAndFlush(saved);
        long next = orders.nextPrioritySequence();
        saved.updateState(saved.getLimitPrice(), 12, 4, OrderStatus.PARTIALLY_FILLED,
                next, CREATED.plusSeconds(2));
        orders.saveAndFlush(saved);
        OrderEntity loaded = orders.findById(saved.getId()).orElseThrow();
        assertEquals(10, loaded.getOriginalQuantity());
        assertEquals(12, loaded.getRemainingQuantity());
        assertEquals(4, loaded.getFilledQuantity());
        assertEquals(next, loaded.getPrioritySequence());
        assertEquals(CREATED, loaded.getCreatedAt());
        assertEquals(CREATED.plusSeconds(2), loaded.getUpdatedAt());
        assertEquals(2L, loaded.getVersion());
    }

    @Test
    void recoveryOrderUsesPriorityRatherThanEqualCreationTimestamps() {
        OrderEntity first = order(OrderSide.SELL, OrderType.LIMIT, 5);
        OrderEntity second = order(OrderSide.SELL, OrderType.LIMIT, 5);
        OrderEntity third = order(OrderSide.SELL, OrderType.LIMIT, 5);
        first.updateState(first.getLimitPrice(), 3, 0, OrderStatus.OPEN,
                first.getPrioritySequence(), CREATED.plusSeconds(1));
        first = orders.saveAndFlush(first);
        assertEquals(List.of(first.getId(), second.getId(), third.getId()), restingIds());
        first.updateState(first.getLimitPrice(), 8, 0, OrderStatus.OPEN,
                orders.nextPrioritySequence(), CREATED.plusSeconds(2));
        orders.saveAndFlush(first);
        assertEquals(List.of(second.getId(), third.getId(), first.getId()), restingIds());
        assertEquals(CREATED, orders.findById(first.getId()).orElseThrow().getCreatedAt());
    }

    @Test
    void priceChangesRequireFreshPriorityAndInvalidUpdatesAreAtomic() {
        OrderEntity saved = order(OrderSide.BUY, OrderType.LIMIT, 5);
        long oldPriority = saved.getPrioritySequence();
        assertThrows(IllegalArgumentException.class, () -> saved.updateState(new BigDecimal("101"),
                5, 0, OrderStatus.OPEN, oldPriority, CREATED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> saved.updateState(saved.getLimitPrice(),
                5, 0, OrderStatus.FILLED, oldPriority, CREATED.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> saved.updateState(null,
                5, 0, OrderStatus.OPEN, oldPriority, CREATED.plusSeconds(1)));
        assertEquals(new BigDecimal("100.1234"), saved.getLimitPrice());
        assertEquals(oldPriority, saved.getPrioritySequence());
        assertEquals(OrderStatus.OPEN, saved.getStatus());
        long next = orders.nextPrioritySequence();
        saved.updateState(new BigDecimal("101.4321"), 5, 0, OrderStatus.OPEN, next, CREATED.plusSeconds(1));
        orders.saveAndFlush(saved);
        assertEquals(next, orders.findById(saved.getId()).orElseThrow().getPrioritySequence());
    }

    @Test
    void restorationExcludesTerminalAndMarketOrdersButFindsUnfinishedMarkets() {
        OrderEntity open = order(OrderSide.BUY, OrderType.LIMIT, 5);
        OrderEntity partial = order(OrderSide.SELL, OrderType.LIMIT, 5);
        partial.updateState(partial.getLimitPrice(), 3, 2, OrderStatus.PARTIALLY_FILLED,
                partial.getPrioritySequence(), CREATED.plusSeconds(1));
        orders.saveAndFlush(partial);
        OrderEntity filled = order(OrderSide.BUY, OrderType.LIMIT, 1);
        filled.updateState(filled.getLimitPrice(), 0, 1, OrderStatus.FILLED,
                filled.getPrioritySequence(), CREATED.plusSeconds(1));
        orders.saveAndFlush(filled);
        OrderEntity cancelled = order(OrderSide.SELL, OrderType.LIMIT, 1);
        cancelled.updateState(cancelled.getLimitPrice(), 1, 0, OrderStatus.CANCELLED,
                cancelled.getPrioritySequence(), CREATED.plusSeconds(1));
        orders.saveAndFlush(cancelled);
        OrderEntity market = order(OrderSide.BUY, OrderType.MARKET, 2);
        assertEquals(List.of(open.getId(), partial.getId()), restingIds());
        assertTrue(orders.findUnfinishedMarketOrders().stream().anyMatch(o -> o.getId().equals(market.getId())));
        assertEquals(5, orders.findUsedOrderIds(stock().getSymbol()).size());
        assertThrows(IllegalStateException.class, () -> filled.updateState(filled.getLimitPrice(),
                1, 0, OrderStatus.OPEN, filled.getPrioritySequence(), CREATED.plusSeconds(2)));
    }

    @Test
    void databaseRejectsBadEnumsPricesQuantitiesStatesAndMissingOwners() {
        OrderEntity saved = order(OrderSide.BUY, OrderType.LIMIT, 5);
        for (String assignment : List.of("side = 'BAD'", "order_type = 'BAD'", "limit_price = NULL",
                "limit_price = 0", "limit_price = 'NaN'::NUMERIC", "order_type = 'MARKET'",
                "original_quantity = 0", "remaining_quantity = -1", "filled_quantity = -1",
                "status = 'BAD'", "status = 'FILLED'", "status = 'PARTIALLY_FILLED'",
                "priority_sequence = 0", "user_id = NULL", "user_id = 9223372036854775807",
                "stock_symbol = 'MISSING_STOCK'", "updated_at = created_at - interval '1 second'")) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE exchange.orders SET " + assignment + " WHERE id = ?", saved.getId()), assignment);
        }
        OrderEntity other = order(OrderSide.SELL, OrderType.LIMIT, 5);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE exchange.orders SET priority_sequence = ? WHERE id = ?",
                saved.getPrioritySequence(), other.getId()));
    }

    @Test
    void ownershipQueryReturnsOnlyTheRequestedUsersOrders() {
        OrderEntity mine = order(OrderSide.BUY, OrderType.LIMIT, 5);
        UserEntity other = newUser();
        orders.saveAndFlush(new OrderEntity(other, stock(), OrderSide.SELL, OrderType.LIMIT,
                BigDecimal.TEN, 5, orders.nextPrioritySequence(), CREATED));
        assertEquals(List.of(mine.getId()), orders.findByUser_Id(owner().getId(), org.springframework.data.domain.PageRequest.of(0, 25))
                .stream().map(OrderEntity::getId).toList());
    }

    @Test
    void tradeHistoryPreservesExecutionSequenceEvenWhenTimestampsTieOrMoveBackward() {
        OrderEntity buy = order(OrderSide.BUY, OrderType.LIMIT, 5);
        OrderEntity sell = order(OrderSide.SELL, OrderType.LIMIT, 5);
        TradeEntity first = trades.saveAndFlush(new TradeEntity(buy, sell, new BigDecimal("100.1234"),
                2, CREATED.plusSeconds(2)));
        TradeEntity second = trades.saveAndFlush(new TradeEntity(buy, sell, new BigDecimal("100.4321"),
                3, CREATED.plusSeconds(1)));
        List<TradeEntity> loaded = trades.findByStock_SymbolOrderByIdAsc(stock().getSymbol());
        assertEquals(List.of(first.getId(), second.getId()), loaded.stream().map(TradeEntity::getId).toList());
        assertEquals(buy.getId(), loaded.get(0).getBuyOrder().getId());
        assertEquals(sell.getId(), loaded.get(0).getSellOrder().getId());
        assertEquals(new BigDecimal("100.1234"), loaded.get(0).getExecutionPrice());
        assertEquals(2, loaded.get(0).getQuantity());
        assertEquals(CREATED.plusSeconds(2), loaded.get(0).getExecutedAt());
        assertEquals(2, trades.findUserTrades(owner().getId()).size());
        assertTrue(trades.findUserTrades(newUser().getId()).isEmpty());
    }

    @Test
    void tradeModelRejectsWrongSidesDifferentStocksAndInvalidValues() {
        OrderEntity buy = order(OrderSide.BUY, OrderType.LIMIT, 5);
        OrderEntity sell = order(OrderSide.SELL, OrderType.LIMIT, 5);
        OrderEntity elsewhere = orders.saveAndFlush(new OrderEntity(owner(), newStock(), OrderSide.SELL,
                OrderType.LIMIT, BigDecimal.TEN, 5, orders.nextPrioritySequence(), CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(sell, buy, BigDecimal.TEN, 1, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, buy, BigDecimal.TEN, 1, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, elsewhere, BigDecimal.TEN, 1, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, sell, BigDecimal.ZERO, 1, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, sell, new BigDecimal("1.00001"), 1, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, sell, BigDecimal.TEN, 0, CREATED));
        assertThrows(IllegalArgumentException.class, () -> new TradeEntity(buy, sell, BigDecimal.TEN, 1, null));
    }

    @Test
    void databaseRejectsTradesWithCrossStockWrongSideOrMissingOrderReferences() {
        OrderEntity buy = order(OrderSide.BUY, OrderType.LIMIT, 5);
        OrderEntity sell = order(OrderSide.SELL, OrderType.LIMIT, 5);
        OrderEntity elsewhere = orders.saveAndFlush(new OrderEntity(owner(), newStock(), OrderSide.SELL,
                OrderType.LIMIT, BigDecimal.TEN, 5, orders.nextPrioritySequence(), CREATED));
        String insert = "INSERT INTO exchange.trades(stock_symbol,buy_order_id,sell_order_id,execution_price,quantity,executed_at) "
                + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
        for (Long[] pair : List.of(new Long[]{sell.getId(), buy.getId()}, new Long[]{buy.getId(), buy.getId()},
                new Long[]{buy.getId(), elsewhere.getId()}, new Long[]{buy.getId(), Long.MAX_VALUE})) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update(insert, stock().getSymbol(), pair[0], pair[1], BigDecimal.TEN, 1));
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, stock().getSymbol(), buy.getId(), sell.getId(), BigDecimal.TEN, 0));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, stock().getSymbol(), buy.getId(), sell.getId(), BigDecimal.ZERO, 1));
        trades.saveAndFlush(new TradeEntity(buy, sell, BigDecimal.TEN, 1, CREATED));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM exchange.orders WHERE id = ?", buy.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE exchange.orders SET side = 'SELL' WHERE id = ?", buy.getId()));
    }

    @Test
    void staleDatabaseSnapshotCannotOverwriteAnUpdatedOrder() {
        Long id = order(OrderSide.BUY, OrderType.LIMIT, 5).getId();
        try (var first = entityManagers.createEntityManager(); var second = entityManagers.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            try {
                OrderEntity a = first.find(OrderEntity.class, id);
                OrderEntity b = second.find(OrderEntity.class, id);
                a.updateState(a.getLimitPrice(), 4, 0, OrderStatus.OPEN, a.getPrioritySequence(), CREATED.plusSeconds(1));
                first.getTransaction().commit();
                b.updateState(b.getLimitPrice(), 3, 0, OrderStatus.OPEN, b.getPrioritySequence(), CREATED.plusSeconds(2));
                assertThrows(OptimisticLockException.class, second::flush);
            } finally {
                if (first.getTransaction().isActive()) { first.getTransaction().rollback(); }
                if (second.getTransaction().isActive()) { second.getTransaction().rollback(); }
            }
        }
        assertEquals(4, orders.findById(id).orElseThrow().getRemainingQuantity());
    }

    @Test
    void recordsCanBeLoadedByAFreshApplicationContextWithoutClaimingEngineRecovery() {
        OrderEntity first = order(OrderSide.SELL, OrderType.LIMIT, 5);
        OrderEntity second = order(OrderSide.SELL, OrderType.LIMIT, 5);
        first.updateState(first.getLimitPrice(), 8, 0, OrderStatus.OPEN,
                orders.nextPrioritySequence(), CREATED.plusSeconds(1));
        orders.saveAndFlush(first);
        try (var restarted = new SpringApplicationBuilder(MiniStockExchangeApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run()) {
            List<OrderEntity> loaded = restarted.getBean(OrderRepository.class).findRestingOrders(stock().getSymbol());
            assertEquals(List.of(second.getId(), first.getId()), loaded.stream().map(OrderEntity::getId).toList());
            assertEquals(8, loaded.get(1).getRemainingQuantity());
            assertEquals(CREATED, loaded.get(1).getCreatedAt());
        }
    }

    private List<Long> restingIds() {
        return orders.findRestingOrders(stock().getSymbol()).stream().map(OrderEntity::getId).toList();
    }
}
