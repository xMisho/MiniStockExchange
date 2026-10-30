package com.misho.exchange.trading;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class MarketDataController {
    private final TradingService trading;

    public MarketDataController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping("/{symbol}/snapshot")
    public MarketSnapshotResponse snapshot(@PathVariable String symbol) {
        return MarketSnapshotResponse.from(trading.getMarketSnapshot(symbol));
    }
}
