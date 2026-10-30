package com.misho.exchange.trading;

import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

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

    @GetMapping("/{symbol}/depth")
    public MarketDepthResponse depth(@PathVariable String symbol,
                                     @RequestParam(defaultValue = "3") int levels) {
        if (levels < 1 || levels > 100) {
            throw new IllegalArgumentException("Levels must be between 1 and 100.");
        }
        return MarketDepthResponse.from(trading.getMarketSnapshot(symbol), levels);
    }

    @GetMapping("/{symbol}/trades")
    public TradeHistoryResponse trades(@PathVariable String symbol,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return TradeHistoryResponse.from(trading.getTradeHistory(symbol, page, size));
    }

    @GetMapping("/{symbol}/candles")
    public List<CandleResponse> candles(@PathVariable String symbol,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        return trading.getCandles(symbol, from, to, limit);
    }
}
