package com.misho.exchange.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.trading.PersistedOrder;
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
class MarketDepthApiTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private StockRepository stocks;
    @Autowired private UserService users;
    @Autowired private TradingService trading;
    @Autowired private JdbcTemplate jdbc;
    private final List<String> symbols = new ArrayList<>();
    private Long userId;

    private String stock() {
        String symbol = "DEPTH" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        stocks.saveAndFlush(new StockEntity(symbol, "Depth API test", new BigDecimal("100")));
        symbols.add(symbol);
        return symbol;
    }

    private PersistedOrder submit(String symbol, OrderSide side, String price, int quantity) {
        if (userId == null) {
            String name = "depth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            userId = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "DepthApiTestPassword!")).id();
        }
        return trading.submitOrder(userId, symbol, side, OrderType.LIMIT, new BigDecimal(price), quantity);
    }

    private JsonNode depth(String symbol, String query) throws Exception {
        var response = new ApiTestClient(port, json).get("/api/stocks/" + symbol + "/depth" + query);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private void level(JsonNode levels, int index, String price, long quantity) {
        assertEquals(0, new BigDecimal(price).compareTo(levels.get(index).get("price").decimalValue()));
        assertEquals(quantity, levels.get(index).get("quantity").asLong());
        assertEquals(2, levels.get(index).size());
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
    void publicDepthAggregatesEquivalentPricesSortsEachSideAndBoundsLevels() throws Exception {
        String symbol = stock();
        for (int i : new int[]{97, 99, 96, 98}) { submit(symbol, OrderSide.BUY, Integer.toString(i), i-90); }
        submit(symbol, OrderSide.BUY, "99.00", 2);
        for (int i : new int[]{104, 101, 103, 102}) { submit(symbol, OrderSide.SELL, Integer.toString(i), i-100); }
        submit(symbol, OrderSide.SELL, "101.0000", 4);
        var result = depth(symbol.toLowerCase(Locale.ROOT), "");
        assertEquals(symbol, result.get("symbol").asText());
        assertEquals(3, result.size());
        assertEquals(3, result.get("bids").size());
        assertEquals(3, result.get("asks").size());
        level(result.get("bids"), 0, "99", 11);
        level(result.get("bids"), 1, "98", 8);
        level(result.get("bids"), 2, "97", 7);
        level(result.get("asks"), 0, "101", 5);
        level(result.get("asks"), 1, "102", 2);
        level(result.get("asks"), 2, "103", 3);
        var one = depth(symbol, "?levels=1");
        assertEquals(1, one.get("bids").size());
        assertEquals(1, one.get("asks").size());
        var all = depth(symbol, "?levels=100");
        assertEquals(4, all.get("bids").size());
        assertEquals(4, all.get("asks").size());
        level(all.get("bids"), 3, "96", 6);
        level(all.get("asks"), 3, "104", 4);
    }

    @Test
    void emptyOneSidedAndLargeAggregatesAreRepresentedWithoutOverflow() throws Exception {
        String symbol = stock();
        var empty = depth(symbol, "");
        assertEquals(0, empty.get("bids").size());
        assertEquals(0, empty.get("asks").size());
        submit(symbol, OrderSide.BUY, "99", Integer.MAX_VALUE);
        submit(symbol, OrderSide.BUY, "99", Integer.MAX_VALUE);
        var oneSided = depth(symbol, "");
        level(oneSided.get("bids"), 0, "99", 2L * Integer.MAX_VALUE);
        assertEquals(0, oneSided.get("asks").size());
    }

    @Test
    void depthReflectsPartialFillsCancellationAmendmentAndRecoveryWithoutAffectingOtherStock() throws Exception {
        String symbol = stock(), other = stock();
        submit(other, OrderSide.BUY, "80", 9);
        var untouched = depth(other, "");
        var first = submit(symbol, OrderSide.SELL, "101", 5);
        var second = submit(symbol, OrderSide.SELL, "101", 3);
        submit(symbol, OrderSide.BUY, "102", 4);
        level(depth(symbol, "").get("asks"), 0, "101", 4);
        assertTrue(trading.cancelOrder(userId, second.id()));
        level(depth(symbol, "").get("asks"), 0, "101", 1);
        assertTrue(trading.amendOrder(userId, first.id(), new BigDecimal("103"), 2));
        var amended = depth(symbol, "");
        assertEquals(1, amended.get("asks").size());
        level(amended.get("asks"), 0, "103", 2);
        trading.recoverMarket(symbol);
        assertEquals(amended, depth(symbol, ""));
        submit(symbol, OrderSide.BUY, "103", 2);
        assertEquals(0, depth(symbol, "").get("asks").size());
        assertEquals(untouched, depth(other, ""));
    }

    @Test
    void badLimitsUnknownSymbolsAndUnavailableMarketsHaveStableErrors() throws Exception {
        String symbol = stock();
        var client = new ApiTestClient(port, json);
        for (String limit : List.of("0", "-1", "101", "abc", "1.5", "2147483648")) {
            var response = client.get("/api/stocks/" + symbol + "/depth?levels=" + limit);
            assertEquals(400, response.statusCode(), limit);
            assertTrue(json.readTree(response.body()).has("error"));
        }
        assertEquals(404, client.get("/api/stocks/UNKNOWN_DEPTH/depth").statusCode());
        assertEquals(400, client.get("/api/stocks/THIS_SYMBOL_IS_LONGER_THAN_TWENTY/depth").statusCode());
        submit(symbol, OrderSide.BUY, "99", 1);
        jdbc.update("UPDATE exchange.orders SET remaining_quantity=1, filled_quantity=1, status='PARTIALLY_FILLED' WHERE stock_symbol=?", symbol);
        assertThrows(IllegalStateException.class, () -> trading.recoverMarket(symbol));
        var unavailable = client.get("/api/stocks/" + symbol + "/depth");
        assertEquals(503, unavailable.statusCode());
        assertEquals("Market data is unavailable.", json.readTree(unavailable.body()).get("error").asText());
    }
}
