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
class MarketSnapshotApiTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private StockRepository stocks;
    @Autowired private TradingService trading;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> symbols = new ArrayList<>();
    private Long userId;

    private String stock() {
        String symbol = "SNAP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        stocks.saveAndFlush(new StockEntity(symbol, "Snapshot test", new BigDecimal("100")));
        symbols.add(symbol);
        return symbol;
    }

    private void submit(String symbol, OrderSide side, String price, int quantity) {
        if (userId == null) {
            String name = "snap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            userId = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "SnapshotTestPassword!")).id();
        }
        trading.submitOrder(userId, symbol, side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private JsonNode snapshot(String symbol) throws Exception {
        var response = new ApiTestClient(port, json).get("/api/stocks/" + symbol + "/snapshot");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
        return json.readTree(response.body());
    }

    private void decimal(JsonNode body, String field, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(body.get(field).decimalValue()), field);
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
    void anonymousEmptyMarketHasExplicitNullPricesAndZeroCounters() throws Exception {
        String symbol = stock();
        var body = snapshot(symbol.toLowerCase(Locale.ROOT));
        assertEquals(symbol, body.get("symbol").asText());
        for (String field : List.of("lastTradedPrice", "priceChange", "priceChangePercent", "bestBidPrice",
                "bestAskPrice", "spread", "vwap")) {
            assertTrue(body.has(field), field);
            assertTrue(body.get(field).isNull(), field);
        }
        assertEquals(0, body.get("volume").asLong());
        assertEquals(0, body.get("tradeCount").asInt());
        assertEquals(0, body.get("openOrderCount").asInt());
        assertEquals(11, body.size());
        assertFalse(body.has("latestCandle"));
        assertFalse(body.has("buyDepth"));
        assertFalse(body.has("sellDepth"));
    }

    @Test
    void restingOrdersChangeQuotesButOnlyExecutionsChangePricesAndAnalytics() throws Exception {
        String symbol = stock();
        submit(symbol, OrderSide.BUY, "99", 5);
        var oneSided = snapshot(symbol);
        decimal(oneSided, "bestBidPrice", "99");
        assertTrue(oneSided.get("bestAskPrice").isNull());
        assertTrue(oneSided.get("spread").isNull());
        submit(symbol, OrderSide.SELL, "101", 5);
        var resting = snapshot(symbol);
        decimal(resting, "spread", "2");
        assertTrue(resting.get("lastTradedPrice").isNull());
        assertEquals(2, resting.get("openOrderCount").asInt());
        submit(symbol, OrderSide.BUY, "102", 2);
        var matched = snapshot(symbol);
        decimal(matched, "lastTradedPrice", "101");
        decimal(matched, "priceChange", "1");
        decimal(matched, "priceChangePercent", "1");
        decimal(matched, "bestBidPrice", "99");
        decimal(matched, "bestAskPrice", "101");
        decimal(matched, "spread", "2");
        decimal(matched, "vwap", "101");
        assertEquals(2, matched.get("volume").asLong());
        assertEquals(1, matched.get("tradeCount").asInt());
        assertEquals(2, matched.get("openOrderCount").asInt());
        trading.recoverMarket(symbol);
        assertEquals(matched, snapshot(symbol));
    }

    @Test
    void differentStocksHaveIndependentSnapshotsAndWeightedExecutionStatistics() throws Exception {
        String first = stock(), second = stock();
        submit(first, OrderSide.SELL, "101", 2);
        submit(first, OrderSide.SELL, "102", 3);
        submit(first, OrderSide.BUY, "103", 5);
        var body = snapshot(first);
        decimal(body, "lastTradedPrice", "102");
        decimal(body, "vwap", "101.6");
        assertEquals(5, body.get("volume").asLong());
        assertEquals(2, body.get("tradeCount").asInt());
        assertEquals(0, body.get("openOrderCount").asInt());
        assertTrue(body.get("spread").isNull());
        var empty = snapshot(second);
        assertEquals(0, empty.get("tradeCount").asInt());
        assertTrue(empty.get("lastTradedPrice").isNull());
        submit(second, OrderSide.BUY, "98", 1);
        submit(second, OrderSide.SELL, "97", 1);
        decimal(snapshot(second), "priceChange", "-2");
        decimal(snapshot(second), "priceChangePercent", "-2");
        assertEquals(body, snapshot(first));
    }

    @Test
    void missingMalformedAndUnavailableMarketsReturnSafeErrors() throws Exception {
        var client = new ApiTestClient(port, json);
        var missing = client.get("/api/stocks/UNKNOWN_SNAPSHOT/snapshot");
        assertEquals(404, missing.statusCode());
        assertTrue(json.readTree(missing.body()).has("error"));
        assertEquals(400, client.get("/api/stocks/THIS_SYMBOL_IS_LONGER_THAN_TWENTY/snapshot").statusCode());
        String symbol = stock();
        submit(symbol, OrderSide.BUY, "99", 1);
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=1, filled_quantity=1, status='PARTIALLY_FILLED' WHERE stock_symbol=?", symbol);
        assertThrows(IllegalStateException.class, () -> trading.recoverMarket(symbol));
        var unavailable = client.get("/api/stocks/" + symbol + "/snapshot");
        assertEquals(503, unavailable.statusCode());
        assertEquals("Market data is unavailable.", json.readTree(unavailable.body()).get("error").asText());
        assertFalse(unavailable.body().contains("history"));
    }
}
