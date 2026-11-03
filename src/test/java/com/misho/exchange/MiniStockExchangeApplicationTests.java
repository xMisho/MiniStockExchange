package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MiniStockExchangeApplicationTests {

    @LocalServerPort
    private int port;

    @Test
    void readinessIsPublicAfterStartupButPrivateFilesAndApisStayProtected() throws Exception {
        var client = HttpClient.newHttpClient();
        for (String path : java.util.List.of("/api/ready", "/favicon.svg", "/.env", "/api/orders/me", "/api/not-a-route")) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            if (path.equals("/api/ready")) {
                assertEquals(200, response.statusCode());
                assertEquals("{\"status\":\"UP\"}", response.body());
            } else if (path.equals("/favicon.svg")) {
                org.junit.jupiter.api.Assertions.assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
            } else {
                assertEquals(401, response.statusCode(), path);
            }
        }
    }

    @Test
    void healthEndpointRespondsOverHttp() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/health"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("{\"status\":\"UP\"}", response.body());
    }
}
