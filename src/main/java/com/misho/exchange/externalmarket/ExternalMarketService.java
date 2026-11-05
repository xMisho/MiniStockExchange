package com.misho.exchange.externalmarket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ExternalMarketService {
    public static final Map<String, String> INSTRUMENTS = Map.of(
            "AAPL", "Apple Inc.", "TSLA", "Tesla, Inc.", "NVDA", "NVIDIA Corporation",
            "MSFT", "Microsoft Corporation", "AMZN", "Amazon.com, Inc.", "GOOGL", "Alphabet Inc.",
            "META", "Meta Platforms, Inc.", "AMD", "Advanced Micro Devices, Inc.",
            "AVGO", "Broadcom Inc.", "PLTR", "Palantir Technologies Inc.");
    private record Cached(Object data, Instant fetchedAt, Instant retryAt, ExternalMarketException failure) { }
    private final AlpacaMarketClient client;
    private final Clock clock;
    private final java.util.LinkedHashMap<String, Cached> cache = new java.util.LinkedHashMap<>(32, .75f, true);
    private List<AlpacaMarketClient.Instrument> catalog = List.of();
    private Instant catalogAt = Instant.MIN;
    private Instant catalogRetryAt = Instant.MIN;
    private RuntimeException catalogFailure;
    @Autowired
    public ExternalMarketService(AlpacaMarketClient client) { this(client, Clock.systemUTC()); }
    ExternalMarketService(AlpacaMarketClient client, Clock clock) { this.client = client; this.clock = clock; }
    public boolean configured() { return client.configured(); }
    public MarketEnvelope<MarketQuote> quote(String symbol) {
        String canonical = symbol(symbol);
        return cached("quote:" + canonical, Duration.ofSeconds(15), () -> client.quote(canonical), MarketQuote.class);
    }
    public MarketEnvelope<MarketHistory> history(String symbol, String range) {
        String canonical = symbol(symbol);
        if (!java.util.Set.of("1D", "1M", "1Y").contains(range)) throw new IllegalArgumentException("Range must be 1D, 1M or 1Y.");
        return cached("bars:" + canonical + ':' + range, Duration.ofSeconds(60), () -> client.history(canonical, range), MarketHistory.class);
    }
    private synchronized <T> MarketEnvelope<T> cached(String key, Duration ttl, Supplier<T> load, Class<T> type) {
        if (!configured()) throw new ExternalMarketException("MARKET_DATA_NOT_CONFIGURED", "External market data needs an Alpaca API key and secret on the server.");
        Instant now = clock.instant();
        Cached result = cache.compute(key, (ignored, current) -> {
            if (current != null && now.isBefore(current.retryAt())) return current;
            try { return new Cached(load.get(), clock.instant(), clock.instant().plus(ttl), null); }
            catch (ExternalMarketException failure) {
                return new Cached(current == null ? null : current.data(), current == null ? null : current.fetchedAt(), clock.instant().plusSeconds(60), failure);
            }
        });
        while (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
        if (result.data() == null || result.fetchedAt().plus(Duration.ofMinutes(15)).isBefore(clock.instant())) {
            throw result.failure() == null ? new ExternalMarketException("MARKET_DATA_UNAVAILABLE", "External market data is unavailable.") : result.failure();
        }
        return new MarketEnvelope<>("Alpaca", "IEX", "USD", "Single exchange; not consolidated US market data", result.fetchedAt(), result.failure() != null,
                result.failure() == null ? null : "Provider refresh failed. Showing the last successful response.", type.cast(result.data()));
    }
    public synchronized List<AlpacaMarketClient.Instrument> search(String query) {
        if (query == null || query.isBlank() || query.strip().length() > 60) throw new IllegalArgumentException("Search must contain 1–60 characters.");
        String term = query.strip().toUpperCase(java.util.Locale.ROOT);
        return catalog().stream().filter(item -> item.symbol().contains(term) || item.companyName().toUpperCase(java.util.Locale.ROOT).contains(term))
                .sorted(java.util.Comparator.comparing((AlpacaMarketClient.Instrument item) -> !item.symbol().startsWith(term)).thenComparing(AlpacaMarketClient.Instrument::symbol)).limit(20).toList();
    }
    public synchronized AlpacaMarketClient.Instrument instrument(String symbol) {
        String canonical = canonical(symbol);
        if (INSTRUMENTS.containsKey(canonical)) return new AlpacaMarketClient.Instrument(canonical, INSTRUMENTS.get(canonical), "USD");
        return catalog().stream().filter(item -> item.symbol().equals(canonical)).findFirst().orElseThrow(() -> new java.util.NoSuchElementException("External stock was not found."));
    }
    private List<AlpacaMarketClient.Instrument> catalog() {
        if (catalogAt.plus(Duration.ofHours(12)).isBefore(clock.instant())) {
            if (clock.instant().isBefore(catalogRetryAt) && catalogFailure != null) throw catalogFailure;
            try { catalog = client.instruments(); catalogAt = clock.instant(); catalogFailure = null; }
            catch (RuntimeException failure) { catalogFailure = failure; catalogRetryAt = clock.instant().plusSeconds(60); throw failure; }
        }
        return catalog;
    }
    public MarketEnvelope<AlpacaMarketClient.Session> session() {
        return cached("session", Duration.ofSeconds(60), client::session, AlpacaMarketClient.Session.class);
    }
    private String symbol(String value) { return instrument(value).symbol(); }
    private String canonical(String value) {
        String canonical = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!canonical.matches("[A-Z][A-Z0-9.-]{0,14}")) throw new IllegalArgumentException("Invalid external symbol.");
        return canonical;
    }
}
