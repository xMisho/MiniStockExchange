package com.misho.exchange.externalmarket;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ExternalMarketService {
    public static final Map<String, String> INSTRUMENTS = Map.of("AAPL", "Apple Inc.", "TSLA", "Tesla, Inc.");
    private record Cached(Object data, Instant fetchedAt, Instant retryAt, ExternalMarketException failure) { }
    private final AlpacaMarketClient client;
    private final Clock clock;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
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
    private <T> MarketEnvelope<T> cached(String key, Duration ttl, Supplier<T> load, Class<T> type) {
        if (!configured()) throw new ExternalMarketException("MARKET_DATA_NOT_CONFIGURED", "External market data needs an Alpaca API key and secret on the server.");
        Instant now = clock.instant();
        Cached result = cache.compute(key, (ignored, current) -> {
            if (current != null && now.isBefore(current.retryAt())) return current;
            try { return new Cached(load.get(), clock.instant(), clock.instant().plus(ttl), null); }
            catch (ExternalMarketException failure) {
                return new Cached(current == null ? null : current.data(), current == null ? null : current.fetchedAt(), clock.instant().plusSeconds(60), failure);
            }
        });
        if (result.data() == null || result.fetchedAt().plus(Duration.ofMinutes(15)).isBefore(clock.instant())) {
            throw result.failure() == null ? new ExternalMarketException("MARKET_DATA_UNAVAILABLE", "External market data is unavailable.") : result.failure();
        }
        return new MarketEnvelope<>("Alpaca", "IEX", "USD", "Single exchange; not consolidated US market data", result.fetchedAt(), result.failure() != null,
                result.failure() == null ? null : "Provider refresh failed. Showing the last successful response.", type.cast(result.data()));
    }
    private String symbol(String value) {
        String canonical = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!INSTRUMENTS.containsKey(canonical)) throw new java.util.NoSuchElementException("This external watchlist supports AAPL and TSLA.");
        return canonical;
    }
}
