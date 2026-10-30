package com.misho.exchange.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.trading.TradingService;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradeHistoryApiTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private StockRepository stocks;
    @Autowired private UserService users;
    @Autowired private TradingService trading;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> symbols = new ArrayList<>();
    private Long userId;

    private String stock() {
        String symbol = "HIST" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        stocks.saveAndFlush(new StockEntity(symbol, "Trade history test", new BigDecimal("100")));
        symbols.add(symbol);
        return symbol;
    }

    private void submit(String symbol, OrderSide side, String price, int quantity) {
        if (userId == null) {
            String name = "hist_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            userId = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "TradeHistoryPassword!")).id();
        }
        trading.submitOrder(userId, symbol, side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private JsonNode history(String symbol, String query) throws Exception {
        var response = new ApiTestClient(port, json).get("/api/stocks/" + symbol + "/trades" + query);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
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
    void anonymousEmptyAndOutOfRangePagesHaveStableMetadata() throws Exception {
        String symbol = stock();
        var empty = history(symbol.toLowerCase(Locale.ROOT), "");
        assertEquals(0, empty.get("content").size());
        assertEquals(0, empty.get("page").asInt());
        assertEquals(25, empty.get("size").asInt());
        assertEquals(0, empty.get("totalElements").asLong());
        assertEquals(0, empty.get("totalPages").asInt());
        submit(symbol, OrderSide.SELL, "100", 2);
        submit(symbol, OrderSide.BUY, "100", 2);
        var beyond = history(symbol, "?page=9&size=1");
        assertEquals(0, beyond.get("content").size());
        assertEquals(9, beyond.get("page").asInt());
        assertEquals(1, beyond.get("totalElements").asLong());
        assertEquals(1, beyond.get("totalPages").asInt());
    }

    @Test
    void databasePagesUseDescendingIdsDespiteTiedOrBackwardTimestampsAndHidePrivateIds() throws Exception {
        String symbol = stock();
        submit(symbol, OrderSide.SELL, "100", 1);
        submit(symbol, OrderSide.SELL, "101", 2);
        submit(symbol, OrderSide.SELL, "102", 3);
        submit(symbol, OrderSide.BUY, "103", 6);
        List<Long> ids = jdbc.queryForList("SELECT id FROM exchange.trades WHERE stock_symbol=? ORDER BY id", Long.class, symbol);
        Instant tied = Instant.parse("2026-01-01T12:00:00Z"), earlier = tied.minusSeconds(60);
        jdbc.update("UPDATE exchange.trades SET executed_at=? WHERE stock_symbol=?", java.sql.Timestamp.from(tied), symbol);
        jdbc.update("UPDATE exchange.trades SET executed_at=? WHERE id=?", java.sql.Timestamp.from(earlier), ids.get(2));
        var first = history(symbol, "?page=0&size=2");
        assertEquals(3, first.get("totalElements").asLong());
        assertEquals(2, first.get("totalPages").asInt());
        assertEquals(2, first.get("content").size());
        var newest = first.get("content").get(0);
        assertEquals(ids.get(2).longValue(), newest.get("tradeId").asLong());
        assertEquals(earlier, Instant.parse(newest.get("executedAt").asText()));
        assertEquals(symbol, newest.get("symbol").asText());
        assertEquals(0, new BigDecimal("102").compareTo(newest.get("executionPrice").decimalValue()));
        assertEquals(3, newest.get("quantity").asInt());
        assertEquals(5, newest.size());
        for (String field : List.of("buyOrderId", "sellOrderId", "userId", "buyOrder", "sellOrder", "password")) {
            assertFalse(newest.has(field), field);
        }
        assertEquals(ids.get(1).longValue(), first.get("content").get(1).get("tradeId").asLong());
        var second = history(symbol, "?page=1&size=2");
        assertEquals(1, second.get("content").size());
        assertEquals(ids.get(0).longValue(), second.get("content").get(0).get("tradeId").asLong());
        assertEquals(tied, Instant.parse(second.get("content").get(0).get("executedAt").asText()));
        assertEquals(3, history(symbol, "?size=100").get("content").size());
    }

    @Test
    void stockHistoryIsIsolatedAndRemainsReadableWhenEngineRecoveryIsBlocked() throws Exception {
        String first = stock(), second = stock();
        submit(first, OrderSide.SELL, "100", 5);
        submit(first, OrderSide.BUY, "101", 2);
        var before = history(first, "");
        assertEquals(0, history(second, "").get("totalElements").asInt());
        submit(second, OrderSide.SELL, "200", 3);
        submit(second, OrderSide.BUY, "201", 3);
        assertEquals(before, history(first, ""));
        assertEquals(second, history(second, "").get("content").get(0).get("symbol").asText());
        jdbc.update("UPDATE exchange.orders SET filled_quantity=3 WHERE stock_symbol=? AND side='SELL'", first);
        assertThrows(IllegalStateException.class, () -> trading.recoverMarket(first));
        assertEquals(503, new ApiTestClient(port, json).get("/api/stocks/" + first + "/snapshot").statusCode());
        assertEquals(before, history(first, ""));
    }

    @Test
    void invalidPaginationAndSymbolsReturnSafeErrors() throws Exception {
        String symbol = stock();
        var client = new ApiTestClient(port, json);
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc", "size=1.5",
                "size=abc", "page=2147483648", "page=2147483647&size=100")) {
            var response = client.get("/api/stocks/" + symbol + "/trades?" + query);
            assertEquals(400, response.statusCode(), query);
            assertTrue(json.readTree(response.body()).has("error"));
        }
        assertEquals(404, client.get("/api/stocks/UNKNOWN_HISTORY/trades").statusCode());
        assertEquals(400, client.get("/api/stocks/THIS_SYMBOL_IS_LONGER_THAN_TWENTY/trades").statusCode());
    }
}
