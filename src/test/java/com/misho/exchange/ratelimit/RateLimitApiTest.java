package com.misho.exchange.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "verdex.rate-limit.login=2", "verdex.rate-limit.registration=1", "verdex.rate-limit.trading=1",
    "verdex.rate-limit.external=1", "verdex.rate-limit.reads=20"})
class RateLimitApiTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Test void actualSecurityChainEnforcesAllPoliciesAndPreservesAuthenticationAndCsrf() throws Exception {
        String username = "ratelimit_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String password = "RateLimitTestPassword123!";
        users.registerUser(new RegisterUserRequest(username, username + "@example.test", password));
        try {
            var c = new ApiTestClient(port, json);
            assertEquals(400, c.postJson("/api/users", "{}").statusCode());
            assertEquals(429, c.postJson("/api/users", "{}").statusCode());
            assertEquals(401, c.login(username, "wrong").statusCode());
            assertEquals(200, c.login(username, password).statusCode());
            assertEquals(429, c.login(username, password).statusCode());
            assertEquals(403, c.changeJson("POST", "/api/stocks/UNKNOWN/orders", "{}", false).statusCode());
            assertEquals(400, c.postJson("/api/stocks/UNKNOWN/orders", "{}").statusCode());
            var blocked = c.changeJson("DELETE", "/api/orders/999999999", "", true);
            assertEquals(429, blocked.statusCode());
            assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) > 0);
            assertEquals(200, c.get("/api/market/instruments").statusCode());
            assertEquals(429, c.get("/api/market/instruments").statusCode());
            int status = 200;
            for (int i = 0; i < 21 && status == 200; i++) status = c.get("/api/stocks").statusCode();
            assertEquals(429, status);
            assertEquals(200, c.get("/api/health").statusCode());
        } finally { jdbc.update("DELETE FROM exchange.users WHERE username = ?", username); }
    }
}
