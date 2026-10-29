package com.misho.exchange.trading;

import com.misho.exchange.MatchingEngine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class MarketRegistry {

    private final Map<String, MatchingEngine> markets = new HashMap<>();
    private final Set<String> unavailable = new HashSet<>();

    void requireAvailable(String symbol) {
        if (unavailable.contains(symbol)) {
            throw new IllegalStateException("Market unavailable until explicit recovery: " + symbol);
        }
    }

    MatchingEngine get(String symbol) {
        requireAvailable(symbol);
        return markets.get(symbol);
    }

    void publish(String symbol, MatchingEngine engine) {
        markets.put(symbol, engine);
        unavailable.remove(symbol);
    }

    void replaceAll(Map<String, MatchingEngine> restored) {
        markets.clear();
        markets.putAll(restored);
        unavailable.clear();
    }

    void forget(String symbol) {
        markets.remove(symbol);
        unavailable.remove(symbol);
    }

    void markAllUnavailable() {
        unavailable.addAll(markets.keySet());
        markets.clear();
    }

    void markUnavailable(String symbol) {
        markets.remove(symbol);
        unavailable.add(symbol);
    }
}
