package com.misho.exchange.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.misho.exchange.MiniStockExchangeApplication;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockPersistenceTest {

    @Autowired
    private StockService service;

    @Autowired
    private StockRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    private int port;

    private final List<String> createdSymbols = new ArrayList<>();

    private String symbol() {
        String symbol = "TEST" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(Locale.ROOT);
        createdSymbols.add(symbol);
        return symbol;
    }

    @AfterEach
    void removeOnlyThisTestsStocks() {
        repository.deleteAllByIdInBatch(createdSymbols);
    }

    @Test
    void savesExactPricesAndRejectsDuplicateSymbolsWithoutUpdatingTheOriginal() {
        String symbol = symbol();
        StockResponse saved = service.registerStock(" " + symbol.toLowerCase(Locale.ROOT) + " ",
                " Test Company ", new BigDecimal("250.1234"));
        assertEquals(symbol, saved.symbol());
        assertEquals("Test Company", saved.companyName());
        assertEquals(new BigDecimal("250.1234"), saved.referencePrice());
        assertEquals(saved, service.getStock(symbol).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> service.registerStock(symbol, "Replacement", BigDecimal.ONE));
        assertEquals(saved, service.getStock(symbol).orElseThrow());
    }

    @Test
    void databaseEnforcesRequiredFieldsPositivePriceAndPrimaryKey() {
        String symbol = symbol();
        String insert = "INSERT INTO exchange.stocks(symbol, company_name, reference_price) VALUES (?, ?, ?)";
        for (BigDecimal price : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-1")}) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update(insert, symbol, "Test Company", price));
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, symbol, null, BigDecimal.ONE));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, null, "Test Company", BigDecimal.ONE));
        service.registerStock(symbol, "Original", BigDecimal.TEN);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update(insert, symbol, "Replacement", BigDecimal.ONE));
        assertEquals("Original", service.getStock(symbol).orElseThrow().companyName());
    }

    @Test
    void readEndpointsReturnSavedMetadataAndSortedSymbols() throws Exception {
        String symbol = symbol();
        service.registerStock(symbol, "Test Company", new BigDecimal("250.1234"));
        HttpResponse<String> single = get("/api/stocks/" + symbol.toLowerCase(Locale.ROOT));
        assertEquals(200, single.statusCode());
        var stock = json.readTree(single.body());
        assertEquals(symbol, stock.get("symbol").asText());
        assertEquals("Test Company", stock.get("companyName").asText());
        assertEquals(0, new BigDecimal("250.1234").compareTo(stock.get("referencePrice").decimalValue()));
        assertEquals(3, stock.size());

        HttpResponse<String> all = get("/api/stocks");
        assertEquals(200, all.statusCode());
        var stocks = json.readTree(all.body());
        List<String> symbols = new ArrayList<>();
        for (var item : stocks) {
            symbols.add(item.get("symbol").asText());
        }
        assertTrue(symbols.contains(symbol));
        assertEquals(symbols.stream().sorted().toList(), symbols);
    }

    @Test
    void absentSymbolsReturn404AndInvalidSymbolsReturn400() throws Exception {
        assertEquals(404, get("/api/stocks/" + symbol()).statusCode());
        assertEquals(400, get("/api/stocks/" + "A".repeat(21)).statusCode());
        assertEquals(400, get("/api/stocks/%20").statusCode());
        assertThrows(IllegalArgumentException.class, () -> service.getStock(null));
    }

    @Test
    void committedStockSurvivesClosingAndRestartingTheApplication() {
        String symbol = symbol();
        try (var first = new SpringApplicationBuilder(MiniStockExchangeApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run()) {
            first.getBean(StockService.class).registerStock(symbol, "Persistent Company", new BigDecimal("42.1234"));
        }
        try (var restarted = new SpringApplicationBuilder(MiniStockExchangeApplication.class)
                .profiles("test").web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "logging.level.root=ERROR").run()) {
            StockResponse loaded = restarted.getBean(StockService.class).getStock(symbol).orElseThrow();
            assertEquals("Persistent Company", loaded.companyName());
            assertEquals(new BigDecimal("42.1234"), loaded.referencePrice());
        }
        assertFalse(service.getStock(symbol).isEmpty());
    }

    @Test
    void postCreatesStockAndReturnsLocationAndPersistedMetadata() throws Exception {
        String symbol = symbol();
        HttpResponse<String> created = post(json.writeValueAsString(new CreateStockRequest(
                symbol.toLowerCase(Locale.ROOT), " Posted Company ", new BigDecimal("123.4567"))));
        assertEquals(201, created.statusCode());
        assertEquals("http://localhost:" + port + "/api/stocks/" + symbol,
                created.headers().firstValue("Location").orElseThrow());
        var body = json.readTree(created.body());
        assertEquals(symbol, body.get("symbol").asText());
        assertEquals("Posted Company", body.get("companyName").asText());
        assertEquals(new BigDecimal("123.4567"), body.get("referencePrice").decimalValue());
        assertEquals(json.readTree(created.body()), json.readTree(get("/api/stocks/" + symbol).body()));
        assertEquals("Posted Company", service.getStock(symbol).orElseThrow().companyName());
    }

    @Test
    void postRejectsInvalidAndMalformedRequestsWithoutSavingAnything() throws Exception {
        String symbol = symbol();
        List<String> invalid = List.of(
                "{}", "null", "{broken", "[]",
                json.writeValueAsString(new CreateStockRequest(" ", "Company", BigDecimal.TEN)),
                json.writeValueAsString(new CreateStockRequest(symbol, null, BigDecimal.TEN)),
                json.writeValueAsString(new CreateStockRequest(symbol, " ", BigDecimal.TEN)),
                json.writeValueAsString(new CreateStockRequest(symbol, "Company", null)),
                json.writeValueAsString(new CreateStockRequest(symbol, "Company", BigDecimal.ZERO)),
                json.writeValueAsString(new CreateStockRequest(symbol, "Company", new BigDecimal("-1"))),
                json.writeValueAsString(new CreateStockRequest(symbol, "Company", new BigDecimal("1.00001"))));
        for (String body : invalid) {
            assertEquals(400, post(body).statusCode(), body);
        }
        assertTrue(service.getStock(symbol).isEmpty());
    }

    @Test
    void duplicatePostReturns409WithoutChangingTheOriginal() throws Exception {
        String symbol = symbol();
        String original = json.writeValueAsString(new CreateStockRequest(symbol, "Original", BigDecimal.TEN));
        assertEquals(201, post(original).statusCode());
        HttpResponse<String> duplicate = post(json.writeValueAsString(new CreateStockRequest(
                symbol.toLowerCase(Locale.ROOT), "Replacement", BigDecimal.ONE)));
        assertEquals(409, duplicate.statusCode());
        assertEquals("Stock symbol is already registered.", json.readTree(duplicate.body()).get("error").asText());
        assertEquals("Original", service.getStock(symbol).orElseThrow().companyName());
        assertEquals(new BigDecimal("10.0000"), service.getStock(symbol).orElseThrow().referencePrice());
    }

    @Test
    void concurrentDuplicatePostsCreateExactlyOneStock() throws Exception {
        String symbol = symbol();
        String body = json.writeValueAsString(new CreateStockRequest(symbol, "Concurrent Company", BigDecimal.TEN));
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return post(body).statusCode(); });
            var second = workers.submit(() -> { start.await(); return post(body).statusCode(); });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(
                    first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM exchange.stocks WHERE symbol = ?",
                    Integer.class, symbol));
        } finally {
            workers.shutdownNow();
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stocks"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
