package com.misho.exchange;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReadinessController {
    private final ApplicationAvailability availability;
    private final com.misho.exchange.trading.MarketWriter writer;

    public ReadinessController(ApplicationAvailability availability, com.misho.exchange.trading.MarketWriter writer) {
        this.availability = availability;
        this.writer = writer;
    }

    @GetMapping("/api/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean accepting = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC && writer.isOwner();
        return ResponseEntity.status(accepting ? 200 : 503)
                .body(Map.of("status", accepting ? "UP" : "STARTING_OR_STOPPING"));
    }
}
