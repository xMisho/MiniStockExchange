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
    @SuppressWarnings("unchecked")
    private HttpResponse<String> body(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body); return response;
    }
    @Test
    @SuppressWarnings("unchecked")
    void calendarHandlesHolidaysAndEarlyCloseInNewYorkTime() throws Exception {
        var replies = java.util.List.of(
            body("{\"timestamp\":\"2026-11-27T18:30:00Z\",\"next_open\":\"2026-11-30T14:30:00Z\",\"next_close\":\"2026-11-30T21:00:00Z\",\"is_open\":false}"),
            body("[{\"date\":\"2026-11-27\",\"open\":\"09:30\",\"close\":\"13:00\"}]"),
            body("{\"timestamp\":\"2026-11-26T15:00:00Z\",\"next_open\":\"2026-11-27T14:30:00Z\",\"next_close\":\"2026-11-27T18:00:00Z\",\"is_open\":false}"), body("[]"));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(replies.get(0), replies.get(1), replies.get(2), replies.get(3));
        var early = client.session();
        assertEquals("AFTER_HOURS", early.status());
        assertEquals(Instant.parse("2026-11-27T18:00:00Z"), early.regularClose());
        assertEquals("CLOSED", client.session().status());
    }
    @Test void catalogIncludesOnlySupportedActiveTradableSymbols() throws Exception {
        response(200, "[{\"symbol\":\"NVDA\",\"name\":\"NVIDIA Corporation\",\"tradable\":true},{\"symbol\":\"BAD/WS\",\"name\":\"Unsupported\",\"tradable\":true},{\"symbol\":\"OLD\",\"name\":\"Inactive\",\"tradable\":false}]");
        var result = client.instruments();
        assertEquals(1, result.size()); assertEquals("NVDA", result.get(0).symbol());
    }
    @Test void minuteHistoryKeepsMoreThanFourHundredBarsAndStopsAtThePriorSession() throws Exception {
        var values = new java.util.ArrayList<String>();
        for (int i = 0; i < 600; i++) values.add("{\"t\":\"" + Instant.parse("2026-10-30T23:00:00Z").minusSeconds(i * 60L) + "\",\"o\":100,\"h\":102,\"l\":99,\"c\":101,\"v\":1,\"n\":1}");
        values.add("{\"t\":\"2026-10-29T19:00:00Z\",\"o\":100,\"h\":102,\"l\":99,\"c\":101,\"v\":1,\"n\":1}");
        response(200, "{\"bars\":{\"AAPL\":[" + String.join(",", values) + "]},\"next_page_token\":\"older-session\"}");
        var history = client.history("AAPL", "1D");
        assertEquals("1Min", history.interval());
        assertEquals(600, history.candles().size());
        assertTrue(history.candles().get(0).startTime().isBefore(history.candles().get(599).startTime()));
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any());
        assertTrue(request.getValue().uri().getQuery().contains("timeframe=1Min"));
    }
    @Test void monthUsesHourlyBarsAndYearUsesDailyBars() throws Exception {
        response(200, "{\"bars\":{\"AAPL\":[]},\"next_page_token\":null}");
        assertEquals("1Hour", client.history("AAPL", "1M").interval());
        assertEquals("1Day", client.history("AAPL", "1Y").interval());
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(request.capture(), any());
        assertTrue(request.getAllValues().get(0).uri().getQuery().contains("timeframe=1Hour"));
        assertTrue(request.getAllValues().get(1).uri().getQuery().contains("timeframe=1Day"));
    }
}
