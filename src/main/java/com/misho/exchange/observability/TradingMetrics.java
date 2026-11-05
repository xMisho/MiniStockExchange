package com.misho.exchange.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class TradingMetrics {
    private final MeterRegistry meters;
    public TradingMetrics(MeterRegistry meters) { this.meters = meters; }
    public void increment(String event) { meters.counter("verdex." + event).increment(); }
    public <T> T timed(String operation, java.util.function.Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try { return work.get(); }
        finally { sample.stop(meters.timer("verdex.trading.duration", "operation", operation)); }
    }
}
