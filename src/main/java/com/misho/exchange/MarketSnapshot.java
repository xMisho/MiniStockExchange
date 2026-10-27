package com.misho.exchange;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

public record MarketSnapshot(
        String symbol,
        BigDecimal lastTradedPrice,
        BigDecimal priceChange,
        BigDecimal priceChangePercent,
        BigDecimal bestBidPrice,
        BigDecimal bestAskPrice,
        long volume,
        int tradeCount,
        int openOrderCount,
        BigDecimal vwap,
        OhlcCandle latestCandle,
        NavigableMap<BigDecimal, Long> buyDepth,
        NavigableMap<BigDecimal, Long> sellDepth) {

    public MarketSnapshot {
        buyDepth = Collections.unmodifiableNavigableMap(new TreeMap<>(buyDepth));
        sellDepth = Collections.unmodifiableNavigableMap(new TreeMap<>(sellDepth));
    }
}
