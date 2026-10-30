package com.misho.exchange.trading;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MarketDataController.class)
public class MarketDataExceptionHandler {
    @ExceptionHandler(UnknownMarketException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Stock is not registered."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSymbol() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid stock symbol."));
    }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Market data is unavailable."));
    }
}
