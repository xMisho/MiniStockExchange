package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.misho.exchange.stock.StockEntity;
import com.misho.exchange.stock.StockRepository;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TradingApiTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private UserService users;
    @Autowired private StockRepository stocks;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TradingService trading;
    @MockitoSpyBean private MarketRegistry registry;
    private final List<Long> userIds = new ArrayList<>();
    private final String symbol = "API" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

    private ApiTestClient user(boolean admin) throws Exception {
        String name = "api_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var user = users.registerUser(new RegisterUserRequest(name, name + "@example.test", "TradingApiTestPassword!"));
        userIds.add(user.id());
        if (admin) { jdbc.update("UPDATE exchange.users SET role = 'ADMIN' WHERE id = ?", user.id()); }
        ApiTestClient client = new ApiTestClient(port, json);
        assertEquals(200, client.login(name, "TradingApiTestPassword!").statusCode());
        return client;
    }

    private void stock() {
        stocks.saveAndFlush(new StockEntity(symbol, "Trading API Test", new BigDecimal("100")));
    }

    private String order(String side, String price, int quantity) {
        return "{\"side\":\"" + side + "\",\"type\":\"LIMIT\",\"limitPrice\":" + price
                + ",\"quantity\":" + quantity + "}";
    }

    private long submit(ApiTestClient client, String side, String price, int quantity) throws Exception {
        var response = client.postJson("/api/stocks/" + symbol + "/orders", order(side, price, quantity));
        assertEquals(201, response.statusCode(), response.body());
        long id = json.readTree(response.body()).get("id").asLong();
        assertEquals("/api/orders/" + id, response.headers().firstValue("Location").orElseThrow());
        return id;
    }

    @AfterEach
    void cleanup() {
        reset(registry);
        jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol = ?", symbol);
        jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol = ?", symbol);
        stocks.deleteById(symbol);
        for (Long id : userIds) { jdbc.update("DELETE FROM exchange.users WHERE id = ?", id); }
        trading.restoreOnStartup();
    }

    @Test
    void authenticatedOrdersMatchPersistAndExposeOnlySafeFields() throws Exception {
        stock();
        var buyer = user(false);
        var seller = user(false);
        long sell = submit(seller, "SELL", "100", 5);
        long buy = submit(buyer, "BUY", "105", 3);
        var result = buyer.get("/api/orders/" + buy);
        assertEquals(200, result.statusCode());
        var body = json.readTree(result.body());
        assertEquals("FILLED", body.get("status").asText());
        assertEquals(3, body.get("filledQuantity").asInt());
        assertFalse(result.body().contains("password"));
        assertFalse(body.has("prioritySequence"));
        assertFalse(body.has("version"));
        assertEquals(2, json.readTree(seller.get("/api/orders/" + sell).body()).get("remainingQuantity").asInt());
        assertEquals(1, json.readTree(buyer.get("/api/orders/me").body()).get("content").size());
        assertEquals(1, trading.getTradeHistory(symbol).size());
    }

    @Test
    void ownershipAndAdminRulesApplyToReadsAmendmentsAndCancellation() throws Exception {
        stock();
        var owner = user(false);
        var other = user(false);
        var admin = user(true);
        long id = submit(owner, "BUY", "99", 5);
        String path = "/api/orders/" + id;
        String amendment = "{\"newPrice\":98,\"newRemainingQuantity\":3}";
        assertEquals(403, other.get(path).statusCode());
        assertEquals(403, other.changeJson("PATCH", path, amendment, true).statusCode());
        assertEquals(403, other.changeJson("DELETE", path, "", true).statusCode());
        assertEquals(0, json.readTree(other.get("/api/orders/me").body()).get("content").size());
        assertEquals(200, admin.get(path).statusCode());
        assertEquals(204, admin.changeJson("PATCH", path, amendment, true).statusCode());
        assertEquals(3, json.readTree(owner.get(path).body()).get("remainingQuantity").asInt());
        assertEquals(204, admin.changeJson("DELETE", path, "", true).statusCode());
        assertEquals("CANCELLED", json.readTree(owner.get(path).body()).get("status").asText());
        assertEquals(409, owner.changeJson("DELETE", path, "", true).statusCode());
        assertEquals(409, owner.changeJson("PATCH", path, amendment, true).statusCode());
    }

    @Test
    void authenticationAndCsrfAreRequired() throws Exception {
        stock();
        var anonymous = new ApiTestClient(port, json);
        assertEquals(401, anonymous.get("/api/orders/me").statusCode());
        assertEquals(401, anonymous.get("/api/orders/1").statusCode());
        assertEquals(401, anonymous.postJson("/api/stocks/" + symbol + "/orders", order("BUY", "99", 5)).statusCode());
        var owner = user(false);
        assertEquals(403, owner.postWithToken("/api/stocks/" + symbol + "/orders", order("BUY", "99", 5),
                "application/json", null).statusCode());
        long id = submit(owner, "BUY", "99", 5);
        assertEquals(403, owner.changeJson("PATCH", "/api/orders/" + id,
                "{\"newPrice\":98,\"newRemainingQuantity\":2}", false).statusCode());
        assertEquals(403, owner.changeJson("DELETE", "/api/orders/" + id, "", false).statusCode());
        assertEquals("OPEN", json.readTree(owner.get("/api/orders/" + id).body()).get("status").asText());
    }

    @Test
    void invalidMissingAndTerminalRequestsHaveStableResponses() throws Exception {
        stock();
        var owner = user(false);
        String endpoint = "/api/stocks/" + symbol + "/orders";
        for (String body : List.of("{}", "null", "{", order("BOGUS", "99", 5), order("BUY", "0", 5),
                order("BUY", "99", 0), order("BUY", "1.00001", 5),
                "{\"side\":\"BUY\",\"type\":\"MARKET\",\"limitPrice\":100,\"quantity\":2}")) {
            assertEquals(400, owner.postJson(endpoint, body).statusCode(), body);
        }
        assertEquals(404, owner.postJson("/api/stocks/UNKNOWN_API_STOCK/orders", order("BUY", "99", 5)).statusCode());
        String missing = "/api/orders/9223372036854775807";
        assertEquals(404, owner.get(missing).statusCode());
        assertEquals(404, owner.changeJson("DELETE", missing, "", true).statusCode());
        assertEquals(404, owner.changeJson("PATCH", missing,
                "{\"newPrice\":98,\"newRemainingQuantity\":2}", true).statusCode());
        long id = submit(owner, "BUY", "99", 5);
        assertEquals(400, owner.changeJson("PATCH", "/api/orders/" + id,
                "{\"newPrice\":null,\"newRemainingQuantity\":2}", true).statusCode());
        assertEquals(5, json.readTree(owner.get("/api/orders/" + id).body()).get("remainingQuantity").asInt());
        var market = owner.postJson(endpoint, "{\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":2}");
        assertEquals(201, market.statusCode());
        assertEquals("CANCELLED", json.readTree(market.body()).get("status").asText());
    }

    @Test
    void historyPaginationFilteringAndResponseFieldsStayOwnedAndBounded() throws Exception {
        stock();
        var owner = user(false);
        var other = user(false);
        long first = submit(owner, "BUY", "90", 2);
        long second = submit(owner, "BUY", "91", 3);
        long third = submit(owner, "BUY", "92", 4);
        submit(other, "BUY", "89", 1);
        assertEquals(204, owner.changeJson("DELETE", "/api/orders/" + second, "", true).statusCode());
        var firstPage = json.readTree(owner.get("/api/orders/me?page=0&size=2").body());
        assertEquals(3, firstPage.get("totalElements").asInt());
        assertEquals(2, firstPage.get("totalPages").asInt());
        assertEquals(0, firstPage.get("page").asInt());
        assertEquals(2, firstPage.get("size").asInt());
        assertEquals(third, firstPage.get("content").get(0).get("id").asLong());
        assertEquals(second, firstPage.get("content").get(1).get("id").asLong());
        assertFalse(firstPage.get("content").get(0).has("prioritySequence"));
        assertFalse(firstPage.get("content").get(0).has("version"));
        var secondPage = json.readTree(owner.get("/api/orders/me?page=1&size=2").body());
        assertEquals(1, secondPage.get("content").size());
        assertEquals(first, secondPage.get("content").get(0).get("id").asLong());
        var cancelled = json.readTree(owner.get("/api/orders/me?status=CANCELLED").body());
        assertEquals(1, cancelled.get("totalElements").asInt());
        assertEquals(second, cancelled.get("content").get(0).get("id").asLong());
        var open = json.readTree(owner.get("/api/orders/me?status=OPEN&size=1").body());
        assertEquals(2, open.get("totalElements").asInt());
        assertEquals(1, open.get("content").size());
        assertEquals(0, json.readTree(owner.get("/api/orders/me?page=10&size=2").body()).get("content").size());
        assertEquals(25, json.readTree(owner.get("/api/orders/me").body()).get("size").asInt());
        for (String query : List.of("page=-1", "size=0", "size=101", "page=abc", "size=abc",
                "status=BOGUS", "page=2147483648")) {
            var response = owner.get("/api/orders/me?" + query);
            assertEquals(400, response.statusCode(), query);
            assertTrue(json.readTree(response.body()).has("error"));
        }
        var admin = user(true);
        assertEquals(0, json.readTree(admin.get("/api/orders/me").body()).get("totalElements").asInt());
    }

    @Test
    void clientCannotChooseOwnerIdAndFilledOrdersRejectChanges() throws Exception {
        stock();
        var owner = user(false);
        var other = user(false);
        long otherId = userIds.get(1);
        String forged = order("SELL", "100", 2).replace("}", ",\"userId\":" + otherId + ",\"id\":1}");
        var result = owner.postJson("/api/stocks/" + symbol + "/orders", forged);
        assertEquals(201, result.statusCode());
        var saved = json.readTree(result.body());
        assertEquals(userIds.get(0).longValue(), saved.get("userId").asLong());
        long id = saved.get("id").asLong();
        submit(other, "BUY", "100", 2);
        assertEquals(409, owner.changeJson("PATCH", "/api/orders/" + id,
                "{\"newPrice\":101,\"newRemainingQuantity\":3}", true).statusCode());
        assertEquals(409, owner.changeJson("DELETE", "/api/orders/" + id, "", true).statusCode());
    }

    @Test
    void committedButFailedPublicationWarnsAgainstRetryAndKeepsDurableHistoryReadable() throws Exception {
        stock();
        var owner = user(false);
        doThrow(new IllegalStateException("internal failure must not leak")).doCallRealMethod()
                .when(registry).publish(eq(symbol), any());
        var result = owner.postJson("/api/stocks/" + symbol + "/orders", order("BUY", "99", 5));
        assertEquals(503, result.statusCode());
        var body = json.readTree(result.body());
        assertEquals("TRADING_OUTCOME_UNCERTAIN", body.get("code").asText());
        assertFalse(body.get("retrySafe").asBoolean());
        assertFalse(result.body().contains("internal failure"));
        assertEquals(1, json.readTree(owner.get("/api/orders/me").body()).get("content").size());
    }
}
