package com.misho.exchange.workspace;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class PortfolioCalculationTest {
    @Test void partialCloseAndReversalKeepLongAndShortCostBasis() {
        var h = new PortfolioService.Holding();
        h.fill(10, new BigDecimal("100")); h.fill(-4, new BigDecimal("120"));
        assertEquals(6, h.quantity); assertEquals(0, h.realized.compareTo(new BigDecimal("80")));
        h.fill(-10, new BigDecimal("90"));
        assertEquals(-4, h.quantity); assertEquals(0, h.average.compareTo(new BigDecimal("90")));
        assertEquals(0, h.realized.compareTo(new BigDecimal("20")));
        h.fill(4, new BigDecimal("80"));
        assertEquals(0, h.quantity); assertEquals(0, h.average.signum());
        assertEquals(0, h.realized.compareTo(new BigDecimal("60")));
    }
}
