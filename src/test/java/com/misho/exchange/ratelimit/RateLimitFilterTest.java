package com.misho.exchange.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private final RequestLimits limits = new RequestLimits(new MockEnvironment()
            .withProperty("verdex.rate-limit.login", "1").withProperty("verdex.rate-limit.registration", "1")
            .withProperty("verdex.rate-limit.trading", "1").withProperty("verdex.rate-limit.external", "1")
            .withProperty("verdex.rate-limit.reads", "1").withProperty("verdex.rate-limit.recovery", "1"), () -> 0);
    private MockHttpServletResponse send(String method, String path, boolean early, String forwarded) throws Exception {
        var request = new MockHttpServletRequest(method, path); request.setServletPath(path);
        request.setRemoteAddr("192.0.2.1"); request.addHeader("X-Forwarded-For", forwarded);
        var response = new MockHttpServletResponse();
        new RateLimitFilter(limits, early).doFilter(request, response, (req, res) -> res.getWriter().write("accepted"));
        return response;
    }
    @Test void allFivePoliciesReturn429BeforeDownstreamWorkAndIgnoreRawForwardingHeaders() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "", java.util.List.of()));
        String[][] routes = {{"POST", "/api/auth/login", "true"}, {"POST", "/api/users", "true"},
                {"POST", "/api/stocks/AAPL/orders", "false"}, {"GET", "/api/market/AAPL/quote", "false"},
                {"GET", "/api/stocks/AAPL/depth", "false"}};
        for (var route : routes) {
            assertEquals("accepted", send(route[0], route[1], Boolean.parseBoolean(route[2]), "1.1.1.1").getContentAsString());
            var rejected = send(route[0], route[1], Boolean.parseBoolean(route[2]), "2.2.2.2");
            assertEquals(429, rejected.getStatus()); assertNotNull(rejected.getHeader("Retry-After"));
            assertTrue(rejected.getContentAsString().contains("RATE_LIMITED"));
            assertFalse(rejected.getContentAsString().contains("accepted"));
        }
    }
    @Test void tradingActionsShareUserBudgetAndOtherUsersAreIndependent() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "", java.util.List.of()));
        assertEquals(200, send("POST", "/api/stocks/AAPL/orders", false, "").getStatus());
        assertEquals(429, send("PATCH", "/api/orders/1", false, "").getStatus());
        assertEquals(429, send("DELETE", "/api/orders/1", false, "").getStatus());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("bob", "", java.util.List.of()));
        assertEquals(200, send("POST", "/api/stocks/TSLA/orders", false, "").getStatus());
    }
    @Test void recoveryEndpointsShareAnIpBudget() throws Exception {
        assertEquals(200,send("POST","/api/auth/forgot-password",true,"").getStatus());
        for(String path:new String[]{"/api/auth/reset-password","/api/auth/verify-email","/api/auth/email-verification"})
            assertEquals(429,send("POST",path,true,"").getStatus());
    }
    @Test void healthAndStaticAssetsStayAvailable() throws Exception {
        for (int i = 0; i < 10; i++) for (String path : new String[]{"/api/ready", "/api/health", "/assets/app.js"})
            assertEquals("accepted", send("GET", path, false, "").getContentAsString());
    }
}
