package com.misho.exchange.externalmarket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlpacaMarketClient {
    static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private final HttpClient http;
    private final ObjectMapper json;
    private final String key;
    private final String secret;
    private final Clock clock;

    @Autowired
    public AlpacaMarketClient(Environment environment, ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), json,
                environment.getProperty("ALPACA_API_KEY", environment.getProperty("ALPACA_API_KEY_ID", "")),
                environment.getProperty("ALPACA_API_SECRET", environment.getProperty("ALPACA_API_SECRET_KEY", "")), Clock.systemUTC());
    }

    AlpacaMarketClient(HttpClient http, ObjectMapper json, String key, String secret, Clock clock) {
        this.http = http; this.json = json; this.key = key.trim(); this.secret = secret.trim(); this.clock = clock;
    }

    public boolean configured() { return !key.isEmpty() && !secret.isEmpty(); }

    public MarketQuote quote(String symbol) {
        JsonNode snapshot = get("snapshots", Map.of("symbols", symbol)).path(symbol);
        if (snapshot.isMissingNode() || snapshot.isNull()) throw invalid();
        JsonNode trade = snapshot.path("latestTrade"), quote = snapshot.path("latestQuote");
        JsonNode day = snapshot.path("dailyBar"), previous = snapshot.path("prevDailyBar");
        BigDecimal price = positive(trade, "p"), previousClose = positive(previous, "c");
        BigDecimal change = price != null && previousClose != null ? price.subtract(previousClose) : null;
        BigDecimal percent = change == null ? null : change.multiply(BigDecimal.valueOf(100)).divide(previousClose, 4, RoundingMode.HALF_UP);
        Instant session = time(day, "t");
        return new MarketQuote(symbol, price, time(trade, "t"), positive(quote, "bp"), positive(quote, "ap"),
                time(quote, "t"), previousClose, change, percent, positive(day, "o"), positive(day, "h"),
                positive(day, "l"), number(day, "v"), count(day, "n"), positive(day, "vw"),
                session == null ? null : session.atZone(EXCHANGE_ZONE).toLocalDate());
    }

    public MarketHistory history(String symbol, String range) {
        Instant end = clock.instant().minus(Duration.ofMinutes(15));
        boolean intraday = range.equals("1D");
        Instant start = intraday ? end.minus(Duration.ofDays(10))
                : end.atZone(EXCHANGE_ZONE).minusMonths(range.equals("1M") ? 1 : 12).toInstant();
        String interval = intraday ? "5Min" : "1Day";
        Map<String, String> parameters = new LinkedHashMap<>(Map.of("symbols", symbol, "timeframe", interval,
                "start", start.toString(), "end", end.toString(), "sort", "desc", "limit", "1000", "adjustment", "split"));
        List<MarketBar> bars = new ArrayList<>();
        String token = null;
        for (int page = 0; page < 3; page++) {
            if (token != null) parameters.put("page_token", token);
            JsonNode response = get("bars", parameters);
            if (!response.path("bars").isObject()) throw invalid();
            JsonNode values = response.path("bars").path(symbol);
            if (!values.isMissingNode() && !values.isNull()) {
                if (!values.isArray() || values.size() > 1000) throw invalid();
                for (JsonNode value : values) {
                    MarketBar bar = bar(value);
                    if (bar.startTime().isBefore(start) || bar.startTime().isAfter(end)
                            || (!bars.isEmpty() && !bar.startTime().isBefore(bars.get(bars.size() - 1).startTime()))) throw invalid();
                    bars.add(bar);
                }
            }
            JsonNode next = response.path("next_page_token");
            if (next.isMissingNode() || next.isNull()) { token = null; break; }
            if (!next.isString() || next.asText().isBlank() || next.asText().length() > 2048) throw invalid();
            token = next.asText();
        }
        if (token != null) throw invalid();
        if (intraday && !bars.isEmpty()) {
            var date = bars.get(0).startTime().atZone(EXCHANGE_ZONE).toLocalDate();
            bars.removeIf(bar -> !bar.startTime().atZone(EXCHANGE_ZONE).toLocalDate().equals(date));
        }
        if (bars.size() > 400) bars = new ArrayList<>(bars.subList(0, 400));
        Collections.reverse(bars);
        return new MarketHistory(symbol, range, interval, 15, "split", bars);
    }

    private JsonNode get(String endpoint, Map<String, String> parameters) {
        if (!configured()) throw new ExternalMarketException("MARKET_DATA_NOT_CONFIGURED", "External market data needs an Alpaca API key and secret on the server.");
        StringBuilder query = new StringBuilder("feed=iex&currency=USD");
        parameters.forEach((name, value) -> query.append('&').append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://data.alpaca.markets/v2/stocks/" + endpoint + "?" + query))
                .timeout(Duration.ofSeconds(5)).header("APCA-API-KEY-ID", key).header("APCA-API-SECRET-KEY", secret)
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new ExternalMarketException("MARKET_DATA_UNAVAILABLE",
                    response.statusCode() == 429 ? "Alpaca rate limit reached. Market data will retry shortly."
                            : response.statusCode() == 401 || response.statusCode() == 403 ? "Alpaca credentials or feed access need checking on the server."
                            : "Alpaca market data is temporarily unavailable.");
            if (response.body() == null || response.body().length() > 2_000_000) throw invalid();
            JsonNode data = json.readTree(response.body());
            if (data == null || !data.isObject()) throw invalid();
            return data;
        } catch (ExternalMarketException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception exception) { throw unavailable(); }
    }

    private MarketBar bar(JsonNode value) {
        BigDecimal open = positive(value, "o"), high = positive(value, "h"), low = positive(value, "l"), close = positive(value, "c"), volume = number(value, "v");
        Instant time = time(value, "t");
        if (time == null || open == null || high == null || low == null || close == null || volume == null
                || high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0) throw invalid();
        return new MarketBar(time, open, high, low, close, volume, count(value, "n"));
    }
    private BigDecimal positive(JsonNode node, String field) { BigDecimal value = number(node, field); return value != null && value.signum() > 0 ? value : null; }
    private BigDecimal number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw invalid();
        BigDecimal result = value.decimalValue();
        if (result.signum() < 0 || result.precision() > 40 || Math.abs(result.scale()) > 20) throw invalid();
        return result;
    }
    private Long count(JsonNode node, String field) {
        BigDecimal value = number(node, field);
        if (value == null) return null;
        try { return value.longValueExact(); } catch (ArithmeticException exception) { throw invalid(); }
    }
    private Instant time(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        try { return Instant.parse(value.asText()); } catch (Exception exception) { throw invalid(); }
    }
    private ExternalMarketException invalid() { return new ExternalMarketException("MARKET_DATA_INVALID", "Alpaca returned incomplete or invalid market data."); }
    private ExternalMarketException unavailable() { return new ExternalMarketException("MARKET_DATA_UNAVAILABLE", "Couldn’t reach Alpaca. Market data will retry shortly."); }
}
