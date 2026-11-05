package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Open LIMIT orders themselves are durable reservations; no duplicate reservation ledger can drift. */
@Component
public class RiskControls {
    public record Balance(BigDecimal cash, BigDecimal reservedCash, BigDecimal availableCash,
                          long holdings, long reservedShares, long availableShares) { }
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public RiskControls(JdbcTemplate jdbc, @Value("${verdex.risk.enabled:true}") boolean enabled) {
        this.jdbc = jdbc; this.enabled = enabled;
    }
    public Balance balance(long user, String symbol, Long excludeOrder) {
        BigDecimal cash = new BigDecimal("100000").add(jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.execution_price * t.quantity *
              ((CASE WHEN s.user_id=? THEN 1 ELSE 0 END) - (CASE WHEN b.user_id=? THEN 1 ELSE 0 END))),0)
            FROM exchange.trades t JOIN exchange.orders b ON b.id=t.buy_order_id JOIN exchange.orders s ON s.id=t.sell_order_id
            WHERE b.user_id=? OR s.user_id=?
            """, BigDecimal.class, user, user, user, user));
        cash = cash.subtract(jdbc.queryForObject("SELECT COALESCE(SUM(price * quantity),0) FROM exchange.paper_allocations WHERE user_id=?", BigDecimal.class, user));
        Long holdings = jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.quantity::BIGINT *
              ((CASE WHEN b.user_id=? THEN 1 ELSE 0 END) - (CASE WHEN s.user_id=? THEN 1 ELSE 0 END))),0)
            FROM exchange.trades t JOIN exchange.orders b ON b.id=t.buy_order_id JOIN exchange.orders s ON s.id=t.sell_order_id
            WHERE t.stock_symbol=? AND (b.user_id=? OR s.user_id=?)
            """, Long.class, user, user, symbol, user, user);
        holdings = Math.addExact(holdings, jdbc.queryForObject("SELECT COALESCE(SUM(quantity::BIGINT),0) FROM exchange.paper_allocations WHERE user_id=? AND stock_symbol=?", Long.class, user, symbol));
        Map<String,Object> reservations = jdbc.queryForMap("""
            SELECT COALESCE(SUM(CASE WHEN side='BUY' THEN limit_price * remaining_quantity ELSE 0 END),0) AS cash,
                   COALESCE(SUM(CASE WHEN side='SELL' AND stock_symbol=? THEN remaining_quantity::BIGINT ELSE 0 END),0) AS shares
            FROM exchange.orders WHERE user_id=? AND order_type='LIMIT' AND status IN ('OPEN','PARTIALLY_FILLED')
                AND (?::BIGINT IS NULL OR id<>?)
            """, symbol, user, excludeOrder, excludeOrder);
        BigDecimal reserved = (BigDecimal) reservations.get("cash");
        long shares = ((Number) reservations.get("shares")).longValue();
        return new Balance(cash, reserved, cash.subtract(reserved), holdings, shares, Math.subtractExact(holdings, shares));
    }
    public void requireSolvent(long user, String symbol) {
        if (!enabled) return;
        Balance b = balance(user, symbol, null);
        if (b.availableCash().signum() < 0 || b.availableShares() < 0)
            throw new TradingConflictException("INSUFFICIENT_COLLATERAL", "A participating account has insufficient collateral. Cancel or fund its existing orders before matching.");
    }
    public void require(long user, String symbol, OrderSide side, int quantity, BigDecimal maximumCost, Long exclude) {
        if (!enabled) return;
        Balance b = balance(user, symbol, exclude);
        if (side == OrderSide.BUY && b.availableCash().compareTo(maximumCost) < 0)
            throw new TradingConflictException("INSUFFICIENT_CASH", "Insufficient available virtual cash for this order.");
        if (side == OrderSide.SELL && b.availableShares() < quantity)
            throw new TradingConflictException("INSUFFICIENT_SHARES", "Insufficient available shares. Short selling is disabled.");
    }
}
