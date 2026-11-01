package com.misho.exchange.externalmarket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class AlpacaMarketClientTest {
    private final HttpClient http = mock(HttpClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);
    private final AlpacaMarketClient client = new AlpacaMarketClient(http, new ObjectMapper(), "test-key", "test-secret", clock);
    @SuppressWarnings("unchecked")
    private void response(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    @Test void parsesQuotesAndKeepsCredentialsInHeaders() throws Exception {
        response(200, """
            {"AAPL":{"latestTrade":{"p":102,"t":"2026-10-30T19:00:00Z"},"latestQuote":{"bp":101,"ap":0,"t":"2026-10-30T19:00:01Z"},"prevDailyBar":{"c":100},"dailyBar":{"o":101,"h":103,"l":99,"v":10.5,"n":4,"vw":101.5,"t":"2026-10-30T04:00:00Z"}}}
            """);
        var quote = client.quote("AAPL");
        assertEquals(0, quote.change().compareTo(new java.math.BigDecimal("2")));
        assertEquals(0, quote.changePercent().compareTo(new java.math.BigDecimal("2")));
        assertNull(quote.ask());
        assertEquals(LocalDate.of(2026, 10, 30), quote.sessionDate());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertEquals("test-key", request.getValue().headers().firstValue("APCA-API-KEY-ID").orElseThrow());
        assertFalse(request.getValue().uri().toString().contains("test-key"));
        assertTrue(request.getValue().uri().getQuery().contains("feed=iex"));
    }
    @Test void historyReturnsLatestSessionChronologicallyWithoutInventingIdleBars() throws Exception {
        response(200, """
            {"bars":{"AAPL":[{"t":"2026-10-30T19:05:00Z","o":101,"h":103,"l":100,"c":102,"v":20,"n":2},{"t":"2026-10-30T19:00:00Z","o":100,"h":102,"l":99,"c":101,"v":10,"n":1},{"t":"2026-10-29T19:00:00Z","o":100,"h":102,"l":99,"c":101,"v":10,"n":1}]},"next_page_token":null}
            """);
        var result = client.history("AAPL", "1D");
        assertEquals(2, result.candles().size());
        assertEquals(Instant.parse("2026-10-30T19:00:00Z"), result.candles().get(0).startTime());
        assertEquals(15, result.delayMinutes());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertTrue(request.getValue().uri().getQuery().contains("end=2026-11-01T11:45:00Z"));
    }
    @Test void providerErrorsDoNotExposeBodiesOrSecrets() throws Exception {
        response(403, "test-secret private provider details");
        var failure = assertThrows(ExternalMarketException.class, () -> client.quote("AAPL"));
        assertFalse(failure.getMessage().contains("test-secret"));
        assertEquals("MARKET_DATA_UNAVAILABLE", failure.code());
    }
    @Test void malformedHistoryIsRejected() throws Exception {
        response(200, "{\"bars\":[]}");
        assertThrows(ExternalMarketException.class, () -> client.history("AAPL", "1M"));
    }
    @Test void missingCredentialsNeverCallProvider() {
        var empty = new AlpacaMarketClient(http, new ObjectMapper(), "", "", clock);
        assertThrows(ExternalMarketException.class, () -> empty.quote("AAPL"));
        verifyNoInteractions(http);
    }
}
