package com.misho.exchange.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.misho.exchange.externalmarket.*;
import com.misho.exchange.stock.StockService;
import com.misho.exchange.trading.TradingService;
import com.misho.exchange.user.*;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceApiTest {
    static final String PASSWORD = "WorkspaceFixturePassword123!";
    @Autowired UserService users;
    @Autowired StockService stocks;
    @Autowired TradingService trading;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean ExternalMarketService market;
    String a, b, symbol;
    long userA, userB;
    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        a = "workspace_a_" + suffix; b = "workspace_b_" + suffix; symbol = "WS" + suffix.toUpperCase();
        userA = users.registerUser(new RegisterUserRequest(a, a + "@example.test", PASSWORD)).id();
        userB = users.registerUser(new RegisterUserRequest(b, b + "@example.test", PASSWORD)).id();
        when(market.instrument("AAPL")).thenReturn(new AlpacaMarketClient.Instrument("AAPL", "Apple Inc.", "USD"));
        when(market.quote("AAPL")).thenReturn(quote("90", "2026-11-02T13:00:00Z", false));
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM exchange.trades WHERE stock_symbol=?", symbol);
        jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol=?", symbol);
        jdbc.update("DELETE FROM exchange.stocks WHERE symbol=?", symbol);
        jdbc.update("DELETE FROM exchange.users WHERE id IN (?,?)", userA, userB);
    }
    ApiTestClient browser(String user) throws Exception { var client = new ApiTestClient(port, json); assertEquals(200, client.login(user, PASSWORD).statusCode()); return client; }
    MarketEnvelope<MarketQuote> quote(String price, String time, boolean stale) {
        return new MarketEnvelope<>("Alpaca", "IEX", "USD", "Single exchange", Instant.parse(time), stale, stale ? "Unavailable" : null,
                new MarketQuote("AAPL", new BigDecimal(price), Instant.parse(time), null, null, null, null, null, null, null, null, null, null, null, null, null));
    }
    @Test void watchlistIsAuthenticatedCsrfProtectedAndOwnedByCaller() throws Exception {
        var anonymous = new ApiTestClient(port, json);
        assertEquals(401, anonymous.get("/api/watchlist").statusCode());
        assertEquals(401, anonymous.get("/api/portfolio").statusCode());
        var owner = browser(a); var other = browser(b);
        assertEquals(403, owner.postWithToken("/api/watchlist", "{\"symbol\":\"AAPL\"}", "application/json", null).statusCode());
        assertEquals(204, owner.postJson("/api/watchlist", "{\"symbol\":\"AAPL\"}").statusCode());
        assertEquals(204, owner.postJson("/api/watchlist", "{\"symbol\":\"AAPL\"}").statusCode());
        assertEquals(1, json.readTree(owner.get("/api/watchlist").body()).size());
        assertEquals(0, json.readTree(other.get("/api/watchlist").body()).size());
        assertEquals(204, other.changeJson("DELETE", "/api/watchlist/AAPL", "", true).statusCode());
        assertEquals(1, json.readTree(owner.get("/api/watchlist").body()).size());
    }
    @Test void alertsTriggerOnlyOnNewObservedCrossingsAndRemainPrivate() throws Exception {
        var owner = browser(a); var other = browser(b);
        assertEquals(204, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":\"ABOVE\",\"target\":100}").statusCode());
        var armed = json.readTree(owner.get("/api/alerts").body()).get("alerts").get(0);
        assertTrue(armed.get("triggeredAt").isNull());
        long id = armed.get("id").asLong();
        assertEquals(404, other.changeJson("DELETE", "/api/alerts/" + id, "", true).statusCode());
        when(market.quote("AAPL")).thenReturn(quote("110", "2026-11-02T13:05:00Z", false));
        var triggeredResponse = json.readTree(owner.get("/api/alerts").body());
        assertEquals(id, triggeredResponse.get("newlyTriggeredIds").get(0).asLong());
        var fired = triggeredResponse.get("alerts").get(0);
        assertEquals(0, json.readTree(owner.get("/api/alerts").body()).get("newlyTriggeredIds").size());
        assertEquals("2026-11-02T13:05:00Z", fired.get("triggeredAt").asText());
        assertEquals(0, fired.get("triggeredPrice").decimalValue().compareTo(new BigDecimal("110")));
        assertEquals(0, json.readTree(other.get("/api/alerts").body()).get("alerts").size());
        assertEquals(204, owner.changeJson("DELETE", "/api/alerts/" + id, "", true).statusCode());
    }
    @Test void staleQuotesDoNotTriggerAlertsAndInvalidTargetsAreRejected() throws Exception {
        var owner = browser(a);
        assertEquals(400, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":null,\"target\":100}").statusCode());
        assertEquals(400, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":\"ABOVE\",\"target\":-1}").statusCode());
        assertEquals(204, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":\"ABOVE\",\"target\":100}").statusCode());
        when(market.quote("AAPL")).thenReturn(quote("110", "2026-11-02T13:05:00Z", true));
        var state = json.readTree(owner.get("/api/alerts").body());
        assertFalse(state.get("warning").isNull());
        assertTrue(state.get("alerts").get(0).get("triggeredAt").isNull());
    }
    @Test void alertsIgnoreAlreadyPassedTargetsAndOutOfOrderTrades() throws Exception {
        var owner = browser(a);
        when(market.quote("AAPL")).thenReturn(quote("110", "2026-11-02T13:00:00Z", false));
        assertEquals(204, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":\"ABOVE\",\"target\":100}").statusCode());
        assertTrue(json.readTree(owner.get("/api/alerts").body()).get("alerts").get(0).get("triggeredAt").isNull());
        when(market.quote("AAPL")).thenReturn(quote("90", "2026-11-02T13:05:00Z", false)); owner.get("/api/alerts");
        when(market.quote("AAPL")).thenReturn(quote("110", "2026-11-02T13:01:00Z", false));
        assertTrue(json.readTree(owner.get("/api/alerts").body()).get("alerts").get(0).get("triggeredAt").isNull());
        when(market.quote("AAPL")).thenReturn(quote("110", "2026-11-02T13:10:00Z", false));
        assertFalse(json.readTree(owner.get("/api/alerts").body()).get("alerts").get(0).get("triggeredAt").isNull());
        assertEquals(204, owner.postJson("/api/alerts", "{\"symbol\":\"AAPL\",\"direction\":\"BELOW\",\"target\":100}").statusCode());
        when(market.quote("AAPL")).thenReturn(quote("95", "2026-11-02T13:15:00Z", false));
        assertFalse(json.readTree(owner.get("/api/alerts").body()).get("alerts").get(0).get("triggeredAt").isNull());
    }

    @Test void portfolioUsesCommittedLocalFillsAndRealizesAverageCostPnl() throws Exception {
        stocks.registerStock(symbol, "Workspace stock", new BigDecimal("100"));
        trading.submitOrder(userB, symbol, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("100"), 10);
        trading.submitOrder(userA, symbol, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("100"), 10);
        trading.submitOrder(userA, symbol, OrderSide.SELL, OrderType.LIMIT, new BigDecimal("120"), 4);
        trading.submitOrder(userB, symbol, OrderSide.BUY, OrderType.LIMIT, new BigDecimal("120"), 4);
        var p = json.readTree(browser(a).get("/api/portfolio").body());
        assertEquals(0, p.get("cash").decimalValue().compareTo(new BigDecimal("99480")));
        assertEquals(0, p.get("equity").decimalValue().compareTo(new BigDecimal("100200")));
        assertEquals(0, p.get("realizedPnl").decimalValue().compareTo(new BigDecimal("80")));
        assertEquals(0, p.get("unrealizedPnl").decimalValue().compareTo(new BigDecimal("120")));
        assertEquals(6, p.get("positions").get(0).get("quantity").asLong());
        verify(market, never()).quote(symbol);
    }
    @Test void adminStockRegistrationIsStillDeniedToOrdinaryUsers() throws Exception {
        var owner = browser(a);
        String body = json.writeValueAsString(java.util.Map.of("symbol", symbol, "companyName", "Admin test", "referencePrice", 100));
        assertEquals(403, owner.postJson("/api/stocks", body).statusCode());
        jdbc.update("UPDATE exchange.users SET role='ADMIN' WHERE id=?", userA);
        var admin = browser(a);
        assertEquals(201, admin.postJson("/api/stocks", body).statusCode());
        assertEquals(409, admin.postJson("/api/stocks", body).statusCode());
    }
}
