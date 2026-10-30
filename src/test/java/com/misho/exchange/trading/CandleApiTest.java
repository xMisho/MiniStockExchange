package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.*;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CandleApiTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private StockRepository stocks;
    @Autowired private UserService users;
    @Autowired private TradingService trading;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CandleRepository candles;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<String> symbols = new ArrayList<>();
    private Long userId;
    private static final Instant START = Instant.parse("2026-10-30T09:00:00Z");

    private String stock() {
        String symbol = "CANDLE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        stocks.saveAndFlush(new StockEntity(symbol, "Candle API test", new BigDecimal("100")));
        symbols.add(symbol);
        return symbol;
    }

    private void trade(String symbol, String price, int quantity, Instant time) {
        if (userId == null) {
            String name = "candle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            userId = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "CandleTestPassword!")).id();
        }
        trading.submitOrder(userId, symbol, OrderSide.SELL, OrderType.LIMIT, new BigDecimal(price), quantity);
        trading.submitOrder(userId, symbol, OrderSide.BUY, OrderType.LIMIT, new BigDecimal(price), quantity);
        jdbc.update("UPDATE exchange.trades SET executed_at=? WHERE id=(SELECT max(id) FROM exchange.trades WHERE stock_symbol=?)",
                Timestamp.from(time), symbol);
    }

    private JsonNode get(String symbol, String query) throws Exception {
        var response = new ApiTestClient(port, json).get("/api/stocks/" + symbol + "/candles" + query);
        assertEquals(200, response.statusCode(), response.body());
        var body = json.readTree(response.body());
        assertTrue(body.isArray());
        return body;
    }

    private void decimal(JsonNode body, String field, String value) {
        assertEquals(0, new BigDecimal(value).compareTo(body.get(field).decimalValue()), field);
    }

    @AfterEach
    void cleanup() {
        for (String symbol : symbols) {
            jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol=?", symbol);
            jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol=?", symbol);
            stocks.deleteById(symbol);
        }
        if (userId != null) { jdbc.update("DELETE FROM exchange.users WHERE id=?", userId); }
        trading.restoreOnStartup();
    }

    @Test
    void aggregatesByUtcMinuteWithTimestampOrderingAndTradeIdTieBreaks() throws Exception {
        String symbol = stock();
        trade(symbol, "100", 2, START.plusSeconds(20));
        trade(symbol, "110", 3, START.plusSeconds(10));
        trade(symbol, "90", 4, START.plusSeconds(20));
        trade(symbol, "105", 5, START.plusSeconds(20));
        trade(symbol, "108", 6, START.plusSeconds(10));
        trade(symbol, "99", 1, START.plusSeconds(60));
        var response = get(symbol.toLowerCase(Locale.ROOT), "");
        assertEquals(2, response.size());
        var first = response.get(0);
        assertEquals(START, Instant.parse(first.get("startTime").asText()));
        assertEquals(7, first.size());
        decimal(first, "open", "110");
        decimal(first, "high", "110");
        decimal(first, "low", "90");
        decimal(first, "close", "105");
        assertEquals(20, first.get("volume").asLong());
        assertEquals(5, first.get("tradeCount").asLong());
        assertEquals(START.plusSeconds(60), Instant.parse(response.get(1).get("startTime").asText()));
        var fromOffset = get(symbol, "?from=2026-10-30T12:00:00%2B03:00&to=2026-10-30T12:01:00%2B03:00");
        assertEquals(1, fromOffset.size());
        assertEquals(first, fromOffset.get(0));
        var nonUtc = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Honolulu'");
            return candles.find(symbol, null, null, 100);
        });
        assertEquals(START, nonUtc.get(0).startTime());
        assertEquals(0, new BigDecimal("110").compareTo(nonUtc.get(0).open()));
    }

    @Test
    void filtersExecutionsWithInclusiveFromExclusiveToAndSubMicrosecondBounds() throws Exception {
        String symbol = stock();
        trade(symbol, "100", 1, START);
        trade(symbol, "101", 2, START.plusSeconds(60));
        trade(symbol, "102", 3, START.plusSeconds(120));
        assertEquals(1, get(symbol, "?from=" + START + "&to=" + START.plusSeconds(60)).size());
        var adjacent = get(symbol, "?from=" + START.plusSeconds(60) + "&to=" + START.plusSeconds(120));
        assertEquals(1, adjacent.size());
        decimal(adjacent.get(0), "open", "101");
        assertEquals(2, get(symbol, "?from=" + START.plusSeconds(60)).size());
        assertEquals(1, get(symbol, "?to=" + START.plusSeconds(60)).size());
        assertEquals(0, get(symbol, "?from=" + START.plusNanos(1) + "&to=" + START.plusSeconds(60)).size());
        assertEquals(1, get(symbol, "?from=" + START + "&to=" + START.plusNanos(1)).size());
        trade(symbol, "103", 4, START.plusNanos(1000));
        var partial = get(symbol, "?from=" + START.plusNanos(1) + "&to=" + START.plusNanos(1001));
        assertEquals(1, partial.size());
        decimal(partial.get(0), "open", "103");
        assertEquals(4, partial.get(0).get("volume").asLong());
    }

    @Test
    void latestLimitReturnsChronologicalCandlesWithoutInventingIdleMinutes() throws Exception {
        String symbol = stock();
        for (int i = 0; i < 101; i++) { trade(symbol, "100", 1, START.plusSeconds(i * 120L)); }
        var defaults = get(symbol, "");
        assertEquals(100, defaults.size());
        assertEquals(START.plusSeconds(120), Instant.parse(defaults.get(0).get("startTime").asText()));
        assertEquals(START.plusSeconds(12000), Instant.parse(defaults.get(99).get("startTime").asText()));
        var two = get(symbol, "?limit=2");
        assertEquals(2, two.size());
        assertEquals(START.plusSeconds(11880), Instant.parse(two.get(0).get("startTime").asText()));
        assertEquals(101, get(symbol, "?limit=1000").size());
        var window = get(symbol, "?to=" + START.plusSeconds(600) + "&limit=2");
        assertEquals(2, window.size());
        assertEquals(START.plusSeconds(360), Instant.parse(window.get(0).get("startTime").asText()));
        assertEquals(START.plusSeconds(480), Instant.parse(window.get(1).get("startTime").asText()));
    }

    @Test
    void emptyIsolatedAndBlockedMarketsRemainReadableWithLongVolume() throws Exception {
        String first = stock(), second = stock();
        assertEquals(0, get(first, "").size());
        trade(first, "100", Integer.MAX_VALUE, START);
        trade(first, "101", Integer.MAX_VALUE, START.plusSeconds(1));
        var before = get(first, "");
        assertEquals(2L * Integer.MAX_VALUE, before.get(0).get("volume").asLong());
        assertEquals(0, get(second, "").size());
        trade(second, "200", 3, START);
        assertEquals(before, get(first, ""));
        assertEquals(0, get(first, "?from=" + START.plusSeconds(60)).size());
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=1, filled_quantity=filled_quantity-1, status='PARTIALLY_FILLED' WHERE stock_symbol=? AND side='SELL'", first);
        assertThrows(IllegalStateException.class, () -> trading.recoverMarket(first));
        assertEquals(before, get(first, ""));
    }

    @Test
    void validatesInputsAndAppliesTheTimeRangeIndexMigration() throws Exception {
        String symbol = stock();
        var client = new ApiTestClient(port, json);
        for (String query : List.of("limit=0", "limit=-1", "limit=1001", "limit=abc", "limit=1.5",
                "from=bad", "to=2026-10-30", "from=" + START + "&to=" + START,
                "from=" + START.plusSeconds(1) + "&to=" + START, "from=+1000000000-01-01T00:00:00Z")) {
            var response = client.get("/api/stocks/" + symbol + "/candles?" + query);
            assertEquals(400, response.statusCode(), query);
            assertTrue(json.readTree(response.body()).has("error"));
        }
        assertEquals(404, client.get("/api/stocks/UNKNOWN_CANDLES/candles").statusCode());
        assertEquals(400, client.get("/api/stocks/THIS_SYMBOL_IS_LONGER_THAN_TWENTY/candles").statusCode());
        String index = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname='exchange' AND indexname='trades_stock_execution_time'", String.class);
        assertTrue(index.contains("(stock_symbol, executed_at, id)"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE version='6' AND success", Integer.class));
    }
}
