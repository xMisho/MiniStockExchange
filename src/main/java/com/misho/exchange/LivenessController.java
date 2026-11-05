package com.misho.exchange;

import java.util.Map;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LivenessController {
    private final ApplicationAvailability availability;
    public LivenessController(ApplicationAvailability availability) { this.availability = availability; }
    @GetMapping("/api/live")
    public ResponseEntity<Map<String, String>> live() {
        boolean live = availability.getLivenessState() == LivenessState.CORRECT;
        return ResponseEntity.status(live ? 200 : 503).body(Map.of("status", live ? "UP" : "BROKEN"));
    }
}
