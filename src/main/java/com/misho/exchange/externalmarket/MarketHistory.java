package com.misho.exchange.externalmarket;

import java.util.List;

public record MarketHistory(String symbol, String range, String interval, int delayMinutes,
        String adjustment, List<MarketBar> candles) {
    public MarketHistory { candles = List.copyOf(candles); }
}
