package com.misho.exchange.marketdata;

import com.misho.exchange.MarketSnapshot;
import java.math.BigDecimal;

public record MarketSnapshotResponse(String symbol, BigDecimal lastTradedPrice,
                                     BigDecimal priceChange, BigDecimal priceChangePercent,
                                     BigDecimal bestBidPrice, BigDecimal bestAskPrice,
                                     BigDecimal spread, long volume, int tradeCount,
                                     int openOrderCount, BigDecimal vwap) {
    static MarketSnapshotResponse from(MarketSnapshot snapshot) {
        BigDecimal spread = snapshot.bestBidPrice() == null || snapshot.bestAskPrice() == null
                ? null : snapshot.bestAskPrice().subtract(snapshot.bestBidPrice());
        return new MarketSnapshotResponse(snapshot.symbol(), snapshot.lastTradedPrice(),
                snapshot.priceChange(), snapshot.priceChangePercent(), snapshot.bestBidPrice(),
                snapshot.bestAskPrice(), spread, snapshot.volume(), snapshot.tradeCount(),
                snapshot.openOrderCount(), snapshot.vwap());
    }
}
