package com.misho.exchange.trading;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = TradingController.class)
public class TradingExceptionHandler {
    @ExceptionHandler({OrderNotFoundException.class, UnknownMarketException.class})
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(OrderNotOpenException.class)
    public ResponseEntity<Map<String, Object>> notOpen() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Order is no longer open."));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid order request."));
    }

    @ExceptionHandler(TradingOperationException.class)
    public ResponseEntity<Map<String, Object>> uncertainOutcome() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Trading outcome is uncertain. Check order history before retrying.",
                "code", "TRADING_OUTCOME_UNCERTAIN", "retrySafe", false));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "Trading is unavailable. Check order history before retrying.", "retrySafe", false));
    }

}
