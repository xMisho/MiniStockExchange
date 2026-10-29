package com.misho.exchange.trading;

import com.misho.exchange.MarketSnapshot;
import com.misho.exchange.MatchingEngine;
import com.misho.exchange.OhlcCandle;
import com.misho.exchange.Order;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderSnapshot;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import com.misho.exchange.Trade;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.user.UserEntity;
import com.misho.exchange.user.UserRepository;
import com.misho.exchange.user.UserRole;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TradingService {

    private final StockRepository stocks;
    private final UserRepository users;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final MarketLoader loader;
    private final MarketRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TradingService(StockRepository stocks, UserRepository users, OrderRepository orders,
                          TradeRepository trades, MarketLoader loader, MarketRegistry registry,
                          JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.stocks = stocks;
        this.users = users;
        this.orders = orders;
        this.trades = trades;
        this.loader = loader;
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void restoreOnStartup() {
        requireNoOuterTransaction();
        try {
            var restored = transaction(() -> {
                var result = new HashMap<String, MatchingEngine>();
                for (var stock : stocks.findAllByOrderBySymbolAsc()) {
                    result.put(stock.getSymbol(), loader.load(stock.getSymbol()).engine());
                }
                return result;
            });
            registry.replaceAll(restored);
        } catch (RuntimeException exception) {
            registry.markAllUnavailable();
            throw exception;
        }
    }

    public synchronized PersistedOrder submitOrder(Long actorId, String symbol, OrderSide side,
                                                    OrderType type, BigDecimal price, int quantity) {
        String canonical = symbol(symbol);
        if (side == null || type == null || quantity <= 0) {
            throw new IllegalArgumentException("Valid side, type, and positive quantity are required.");
        }
        BigDecimal validatedPrice;
        if (type == OrderType.LIMIT) { validatedPrice = PersistenceValues.price(price); }
        else {
            if (price != null) { throw new IllegalArgumentException("Market orders have no limit price."); }
            validatedPrice = null;
        }
        return change(canonical, market -> {
            UserEntity owner = actor(actorId);
            var row = orders.saveAndFlush(new OrderEntity(owner, market.stock(), side, type,
                    validatedPrice, quantity, orders.nextPrioritySequence(), Instant.now()));
            market.orders().put(row.getId(), row);
            market.engine().submitOrder(Order.restore(new OrderSnapshot(row.getId(), side, type,
                    validatedPrice, quantity, quantity, 0, OrderStatus.OPEN, row.getCreatedAt().toEpochMilli())));
            return new Change(row, row.getPrioritySequence(), true);
        }).order();
    }

    public synchronized boolean cancelOrder(Long actorId, long orderId) {
        String symbol = orderSymbol(actorId, orderId);
        if (symbol == null) { return false; }
        return change(symbol, market -> {
            var row = market.orders().get(orderId);
            authorize(actor(actorId), row);
            boolean cancelled = market.engine().cancelOrder(orderId);
            return new Change(row, row.getPrioritySequence(), cancelled);
        }).changed();
    }

    public synchronized boolean amendOrder(Long actorId, long orderId, BigDecimal newPrice, int remaining) {
        String symbol = orderSymbol(actorId, orderId);
        if (symbol == null) { return false; }
        return change(symbol, market -> {
            var row = market.orders().get(orderId);
            authorize(actor(actorId), row);
            if (row.getStatus() == OrderStatus.FILLED || row.getStatus() == OrderStatus.CANCELLED) {
                return new Change(row, row.getPrioritySequence(), false);
            }
            BigDecimal price = PersistenceValues.price(newPrice);
            if (remaining <= 0) { throw new IllegalArgumentException("Remaining quantity must be positive."); }
            boolean losesPriority = row.getLimitPrice().compareTo(price) != 0 || remaining > row.getRemainingQuantity();
            long priority = losesPriority ? orders.nextPrioritySequence() : row.getPrioritySequence();
            boolean amended = market.engine().amendOrder(orderId, price, remaining);
            return new Change(row, priority, amended);
        }).changed();
    }

    public synchronized PersistedOrder getOrder(Long actorId, long orderId) {
        return transaction(() -> {
            UserEntity actor = actor(actorId);
            var row = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException());
            authorize(actor, row);
            return PersistedOrder.from(row);
        });
    }

    public synchronized List<PersistedOrder> getOrders(Long actorId) {
        return transaction(() -> {
            actor(actorId);
            return orders.findByUser_IdOrderByIdAsc(actorId).stream().map(PersistedOrder::from).toList();
        });
    }

    public synchronized MarketSnapshot getMarketSnapshot(String symbol) {
        return market(symbol(symbol)).getMarketSnapshot();
    }

    public synchronized List<Trade> getTradeHistory(String symbol) {
        return market(symbol(symbol)).getTradeHistory();
    }

    public synchronized NavigableMap<Long, OhlcCandle> getOhlcCandles(String symbol) {
        return market(symbol(symbol)).getOhlcCandles();
    }

    public synchronized void recoverMarket(String symbol) {
        String canonical = symbol(symbol);
        try {
            MatchingEngine engine = transaction(() -> loader.load(canonical).engine());
            registry.publish(canonical, engine);
        } catch (UnknownMarketException exception) {
            registry.forget(canonical);
            throw exception;
        } catch (RuntimeException exception) {
            registry.markUnavailable(canonical);
            throw exception;
        }
    }

    private Result change(String symbol, Function<LoadedMarket, Change> action) {
        requireNoOuterTransaction();
        registry.requireAvailable(symbol);
        try {
            Pending pending = transaction(() -> {
                LoadedMarket market = loader.load(symbol);
                Change change = action.apply(market);
                persist(market, change);
                return new Pending(market.engine(), new Result(PersistedOrder.from(change.row()), change.changed()));
            });
            registry.publish(symbol, pending.engine());
            return pending.result();
        } catch (UnknownMarketException exception) {
            registry.forget(symbol);
            throw exception;
        } catch (RuntimeException exception) {
            try { recoverMarket(symbol); }
            catch (RuntimeException recoveryFailure) { exception.addSuppressed(recoveryFailure); }
            if (exception instanceof IllegalArgumentException || exception instanceof AccessDeniedException) {
                throw exception;
            }
            throw new TradingOperationException(exception);
        }
    }

    private void persist(LoadedMarket market, Change change) {
        Instant now = Instant.now();
        for (OrderSnapshot snapshot : market.engine().getOrderSnapshots()) {
            OrderEntity row = market.orders().get(snapshot.id());
            long priority = row.getId().equals(change.row().getId()) ? change.priority() : row.getPrioritySequence();
            if (row.getStatus() != snapshot.status() || row.getRemainingQuantity() != snapshot.remainingQuantity()
                    || row.getFilledQuantity() != snapshot.filledQuantity() || !Objects.equals(row.getLimitPrice(), snapshot.price())
                    || priority != row.getPrioritySequence()) {
                row.updateState(snapshot.price(), snapshot.remainingQuantity(), snapshot.filledQuantity(), snapshot.status(),
                        priority, now.isBefore(row.getUpdatedAt()) ? row.getUpdatedAt() : now);
            }
        }
        var history = market.engine().getTradeHistory();
        var newTrades = history.subList(market.previousTradeCount(), history.size()).stream()
                .map(trade -> new TradeEntity(market.orders().get(trade.getBuyOrderId()),
                        market.orders().get(trade.getSellOrderId()), trade.getExecutionPrice(),
                        trade.getQuantity(), Instant.ofEpochMilli(trade.getTimestamp()))).toList();
        trades.saveAllAndFlush(newTrades);
    }

    private MatchingEngine market(String symbol) {
        requireNoOuterTransaction();
        MatchingEngine engine = registry.get(symbol);
        if (engine == null) { recoverMarket(symbol); engine = registry.get(symbol); }
        return engine;
    }

    private String orderSymbol(Long actorId, long orderId) {
        return transaction(() -> {
            UserEntity actor = actor(actorId);
            var row = orders.findById(orderId).orElse(null);
            if (row == null) { return null; }
            authorize(actor, row);
            return row.getStock().getSymbol();
        });
    }

    private UserEntity actor(Long actorId) {
        if (actorId == null) { throw new AccessDeniedException("A user is required."); }
        return users.findById(actorId).orElseThrow(() -> new AccessDeniedException("User not found."));
    }

    private void authorize(UserEntity actor, OrderEntity order) {
        if (!actor.getId().equals(order.getUser().getId()) && actor.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Order belongs to another user.");
        }
    }

    private <T> T transaction(Supplier<T> operation) {
        requireNoOuterTransaction();
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            jdbc.execute("SELECT pg_advisory_xact_lock(8243061201)");
            return operation.get();
        });
    }

    private void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TradingService must own its transaction boundary.");
        }
    }

    private String symbol(String symbol) {
        if (symbol == null || symbol.isBlank() || symbol.strip().length() > 20) {
            throw new IllegalArgumentException("Invalid stock symbol.");
        }
        return symbol.strip().toUpperCase(Locale.ROOT);
    }

    private record Change(OrderEntity row, long priority, boolean changed) { }
    private record Result(PersistedOrder order, boolean changed) { }
    private record Pending(MatchingEngine engine, Result result) { }
}
