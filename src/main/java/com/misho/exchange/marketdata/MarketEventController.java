package com.misho.exchange.marketdata;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class MarketEventController {
    private final MarketEvents events;
    public MarketEventController(MarketEvents events) { this.events = events; }
    @GetMapping(value="/api/events", produces="text/event-stream")
    public SseEmitter events(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return events.subscribe(request.getRemoteAddr());
    }
    @ExceptionHandler(MarketEvents.StreamLimitException.class)
    public ResponseEntity<Map<String,Object>> full() {
        return ResponseEntity.status(429).header("Retry-After", "60").body(Map.of("error", "Too many live connections. Polling remains available.", "code", "RATE_LIMITED"));
    }
}
