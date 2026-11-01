package com.misho.exchange.externalmarket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class ExternalMarketServiceTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-11-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void coalescesCachedReadsAndBoundsStaleFallback() {
        var client = mock(AlpacaMarketClient.class);
        var time = new Time();
        when(client.configured()).thenReturn(true);
        var quote = new MarketQuote("AAPL", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        when(client.quote("AAPL")).thenReturn(quote).thenThrow(new ExternalMarketException("MARKET_DATA_UNAVAILABLE", "Unavailable"));
        var service = new ExternalMarketService(client, time);
        assertFalse(service.quote("aapl").stale());
        assertFalse(service.quote("AAPL").stale());
        verify(client, times(1)).quote("AAPL");
        time.now = time.now.plusSeconds(16);
        assertTrue(service.quote("AAPL").stale());
        assertTrue(service.quote("AAPL").stale());
        verify(client, times(2)).quote("AAPL");
        time.now = time.now.plusSeconds(901);
        assertThrows(ExternalMarketException.class, () -> service.quote("AAPL"));
    }
    @Test void rejectsUnsupportedSymbolsAndRangesBeforeFetching() {
        var client = mock(AlpacaMarketClient.class);
        var service = new ExternalMarketService(client, new Time());
        assertThrows(java.util.NoSuchElementException.class, () -> service.quote("OTHER"));
        assertThrows(IllegalArgumentException.class, () -> service.history("AAPL", "ALL"));
        verifyNoInteractions(client);
    }
}
