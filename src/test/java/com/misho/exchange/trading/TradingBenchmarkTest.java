package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"logging.level.root=ERROR", "logging.level.org.hibernate.SQL=OFF",
                "logging.level.org.springframework.jdbc=OFF"})
@EnabledIfSystemProperty(named = "trading.benchmark", matches = "true")
class TradingBenchmarkTest {
    @Autowired private TradingService service;
    @Autowired private StockRepository stocks;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void measureHistoryScaling() {
        String name = "bench_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long user = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "BenchmarkPassword123!")).id();
        try {
            for (int history : new int[]{100, 1000, 10000}) {
                String symbol = "BENCH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
                try {
                    stocks.saveAndFlush(new StockEntity(symbol, "Isolated benchmark", new BigDecimal("100")));
                    List<Long> ids = jdbc.queryForList("SELECT nextval(pg_get_serial_sequence('exchange.orders','id')) FROM generate_series(1, ?)", Long.class, history * 2);
                    List<Object[]> rows = new ArrayList<>();
                    List<Object[]> fills = new ArrayList<>();
                    for (int i = 0; i < history; i++) {
                        rows.add(new Object[]{ids.get(i*2), user, symbol, "BUY"});
                        rows.add(new Object[]{ids.get(i*2+1), user, symbol, "SELL"});
                        fills.add(new Object[]{symbol, ids.get(i*2), ids.get(i*2+1)});
                    }
                    jdbc.batchUpdate("INSERT INTO exchange.orders(id,user_id,stock_symbol,side,order_type,limit_price,original_quantity,remaining_quantity,filled_quantity,status) VALUES (?,?,?,?,'LIMIT',100,1,0,1,'FILLED')", rows);
                    jdbc.batchUpdate("INSERT INTO exchange.trades(stock_symbol,buy_order_id,sell_order_id,execution_price,quantity,executed_at) VALUES (?,?,?,100,1,CURRENT_TIMESTAMP)", fills);
                    service.recoverMarket(symbol);
                    List<Double> submit = new ArrayList<>(), amend = new ArrayList<>(), cancel = new ArrayList<>();
                    List<Double> match = new ArrayList<>();
                    for (int i = 0; i < 15; i++) {
                        long start = System.nanoTime();
                        var order = service.submitOrder(user, symbol, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("90"), 5);
                        long submitted = System.nanoTime();
                        service.amendOrder(user, order.id(), new BigDecimal("91"), 6);
                        long amended = System.nanoTime();
                        service.cancelOrder(user, order.id());
                        long cancelled = System.nanoTime();
                        service.submitOrder(user, symbol, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("100"), 1);
                        long matching = System.nanoTime();
                        service.submitOrder(user, symbol, OrderSide.BUY, OrderType.MARKET, null, 1);
                        long matched = System.nanoTime();
                        if (i >= 5) {
                            submit.add((submitted-start)/1e6); amend.add((amended-submitted)/1e6);
                            cancel.add((cancelled-amended)/1e6); match.add((matched-matching)/1e6);
                        }
                    }
                    System.out.printf("BENCH historyOrders=%d historyTrades=%d samples=10 %s %s %s %s%n", history*2, history,
                            stats("submit",submit), stats("amend",amend), stats("cancel",cancel), stats("match",match));
                } finally {
                    jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol=?", symbol);
                    jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol=?", symbol);
                    stocks.deleteById(symbol);
                    service.restoreOnStartup();
                }
            }
        } finally { jdbc.update("DELETE FROM exchange.users WHERE id=?", user); }
    }

    private String stats(String operation, List<Double> samples) {
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return String.format("%s[median=%.3fms mean=%.3fms]", operation,
                (sorted[4]+sorted[5])/2, Arrays.stream(sorted).average().orElseThrow());
    }
}
