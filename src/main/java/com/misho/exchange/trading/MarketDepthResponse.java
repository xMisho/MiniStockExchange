package com.misho.exchange.trading;

import com.misho.exchange.MarketSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.NavigableMap;

public record MarketDepthResponse(String symbol, List<PriceLevelResponse> bids, List<PriceLevelResponse> asks) {
    public MarketDepthResponse {
        bids = List.copyOf(bids);
        asks = List.copyOf(asks);
    }

    static MarketDepthResponse from(MarketSnapshot snapshot, int levels) {
        return new MarketDepthResponse(snapshot.symbol(), levels(snapshot.buyDepth(), levels),
                levels(snapshot.sellDepth(), levels));
    }

    private static List<PriceLevelResponse> levels(NavigableMap<BigDecimal, Long> depth, int limit) {
        return depth.entrySet().stream().limit(limit)
                .map(entry -> new PriceLevelResponse(entry.getKey(), entry.getValue())).toList();
    }
}
