package com.misho.exchange.stock;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StockResponse> registerStock(@RequestBody CreateStockRequest request) {
        StockResponse stock = service.registerStock(
                request.symbol(), request.companyName(), request.referencePrice());
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .pathSegment(stock.symbol()).build().toUri();
        return ResponseEntity.created(location).body(stock);
    }

    @GetMapping
    public List<StockResponse> getStocks() {
        return service.getStocks();
    }

    @GetMapping("/{symbol}")
    public StockResponse getStock(@PathVariable String symbol) {
        return service.getStock(symbol).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock is not registered."));
    }

    @ExceptionHandler(StockAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicateStock(StockAlreadyExistsException exception) {
        return Map.of("error", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidStock(IllegalArgumentException exception) {
        return Map.of("error", exception.getMessage());
    }
}
