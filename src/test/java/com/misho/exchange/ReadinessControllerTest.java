package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;

class ReadinessControllerTest {
    @Test
    void onlyReportsReadyAfterStartupAndWhileAcceptingTraffic() {
        var availability = mock(ApplicationAvailability.class);
        var writer = mock(com.misho.exchange.trading.MarketWriter.class);
        when(writer.isOwner()).thenReturn(true);
        var controller = new ReadinessController(availability, writer);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertEquals(503, controller.ready().getStatusCode().value());
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        assertEquals(200, controller.ready().getStatusCode().value());
        when(writer.isOwner()).thenReturn(false);
        assertEquals(503, controller.ready().getStatusCode().value());
    }
}
