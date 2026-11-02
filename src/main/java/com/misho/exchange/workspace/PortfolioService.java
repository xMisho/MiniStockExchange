package com.misho.exchange.workspace;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioService {
    public record Position(String symbol, long quantity, BigDecimal averagePrice, BigDecimal markPrice,
                           BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal realizedPnl) { }
    public record Portfolio(BigDecimal startingCash, BigDecimal cash, BigDecimal equity,
                            BigDecimal realizedPnl, BigDecimal unrealizedPnl, List<Position> positions) { }
    private static final BigDecimal START = new BigDecimal("100000.00");
    private final JdbcTemplate jdbc;
    public PortfolioService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    static class Holding {
        long quantity;
        BigDecimal average = BigDecimal.ZERO, realized = BigDecimal.ZERO, mark = BigDecimal.ZERO;
        void fill(long delta, BigDecimal price) {
            if (quantity == 0 || Long.signum(quantity) == Long.signum(delta)) {
                long total = Math.addExact(Math.abs(quantity), Math.abs(delta));
                average = average.multiply(BigDecimal.valueOf(Math.abs(quantity))).add(price.multiply(BigDecimal.valueOf(Math.abs(delta)))).divide(BigDecimal.valueOf(total), 12, RoundingMode.HALF_UP);
            } else {
                long closed = Math.min(Math.abs(quantity), Math.abs(delta));
                realized = realized.add(price.subtract(average).multiply(BigDecimal.valueOf(closed * Long.signum(quantity))));
                if (Math.abs(delta) > Math.abs(quantity)) average = price;
                else if (Math.abs(delta) == Math.abs(quantity)) average = BigDecimal.ZERO;
            }
            quantity = Math.addExact(quantity, delta);
        }
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Portfolio portfolio(long user) {
        var holdings = new LinkedHashMap<String, Holding>();
        BigDecimal[] cash = { START };
        jdbc.query("""
            SELECT t.stock_symbol, t.execution_price, t.quantity, b.user_id AS buyer, s.user_id AS seller,
              (SELECT m.execution_price FROM exchange.trades m WHERE m.stock_symbol=t.stock_symbol ORDER BY m.id DESC LIMIT 1) AS mark
            FROM exchange.trades t JOIN exchange.orders b ON b.id=t.buy_order_id JOIN exchange.orders s ON s.id=t.sell_order_id
            WHERE b.user_id=? OR s.user_id=? ORDER BY t.id
            """, row -> {
            var holding = holdings.computeIfAbsent(row.getString("stock_symbol"), ignored -> new Holding());
            holding.mark = row.getBigDecimal("mark");
            long delta = (row.getLong("buyer") == user ? row.getLong("quantity") : 0) - (row.getLong("seller") == user ? row.getLong("quantity") : 0);
            if (delta == 0) return;
            BigDecimal price = row.getBigDecimal("execution_price");
            holding.fill(delta, price);
            cash[0] = cash[0].subtract(price.multiply(BigDecimal.valueOf(delta)));
        }, user, user);
        var positions = new ArrayList<Position>();
        BigDecimal realized = BigDecimal.ZERO, unrealized = BigDecimal.ZERO, value = BigDecimal.ZERO;
        for (var entry : holdings.entrySet()) {
            var h = entry.getValue();
            BigDecimal mv = h.mark.multiply(BigDecimal.valueOf(h.quantity));
            BigDecimal pnl = h.mark.subtract(h.average).multiply(BigDecimal.valueOf(h.quantity));
            realized = realized.add(h.realized); unrealized = unrealized.add(pnl); value = value.add(mv);
            positions.add(new Position(entry.getKey(), h.quantity, h.average, h.mark, mv, pnl, h.realized));
        }
        return new Portfolio(START, cash[0], cash[0].add(value), realized, unrealized, List.copyOf(positions));
    }
}
