package com.misho.exchange.externalmarket;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
public class ExternalMarketController {
    public record Instrument(String symbol, String companyName, String currency) { }
    public record Configuration(String provider, String feed, boolean configured, List<Instrument> instruments) { }
    private final ExternalMarketService market;
    public ExternalMarketController(ExternalMarketService market) { this.market = market; }
    @GetMapping("/instruments")
    public Configuration instruments() {
        return new Configuration("Alpaca", "IEX", market.configured(), ExternalMarketService.INSTRUMENTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(entry -> new Instrument(entry.getKey(), entry.getValue(), "USD")).toList());
    }
    @GetMapping("/search")
    public List<AlpacaMarketClient.Instrument> search(@RequestParam String q) { return market.search(q); }
    @GetMapping("/session")
    public MarketEnvelope<AlpacaMarketClient.Session> session() { return market.session(); }
    @GetMapping("/{symbol}/quote")
    public MarketEnvelope<MarketQuote> quote(@PathVariable String symbol) { return market.quote(symbol); }
    @GetMapping("/{symbol}/candles")
    public MarketEnvelope<MarketHistory> candles(@PathVariable String symbol, @RequestParam(defaultValue = "1D") String range) { return market.history(symbol, range); }
    @ExceptionHandler(ExternalMarketException.class)
    public ResponseEntity<Map<String, Object>> unavailable(ExternalMarketException exception) {
        return ResponseEntity.status(503).body(Map.of("error", exception.getMessage(), "code", exception.code()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) { return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage())); }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> missing(NoSuchElementException exception) { return ResponseEntity.status(404).body(Map.of("error", exception.getMessage())); }
}
