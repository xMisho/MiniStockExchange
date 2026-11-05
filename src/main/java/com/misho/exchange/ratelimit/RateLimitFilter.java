package com.misho.exchange.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RateLimitFilter extends OncePerRequestFilter {
    private final RequestLimits limits;
    private final boolean loginPhase;
    private com.misho.exchange.observability.TradingMetrics metrics;
    public RateLimitFilter(RequestLimits limits, boolean loginPhase, com.misho.exchange.observability.TradingMetrics metrics) {
        this(limits, loginPhase); this.metrics = metrics;
    }
    public RateLimitFilter(RequestLimits limits, boolean loginPhase) { this.limits = limits; this.loginPhase = loginPhase; }
    @Override protected String getAlreadyFilteredAttributeName() { return getClass().getName() + loginPhase; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        String method = request.getMethod();
        RequestLimits.Policy policy = null;
        String identity = request.getRemoteAddr(); // Never parse caller-supplied forwarding headers here.
        if (loginPhase) {
            if (method.equals("POST") && path.equals("/api/auth/login")) policy = RequestLimits.Policy.LOGIN;
            if (method.equals("POST") && java.util.Set.of("/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/verify-email", "/api/auth/email-verification").contains(path)) policy = RequestLimits.Policy.RECOVERY;
            if (method.equals("POST") && path.equals("/api/users")) policy = RequestLimits.Policy.REGISTRATION;
        } else {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
            if (authenticated && ((method.equals("POST") && path.matches("/api/stocks/[^/]+/orders"))
                    || ((method.equals("PATCH") || method.equals("DELETE")) && path.matches("/api/orders/[^/]+")))) {
                policy = RequestLimits.Policy.TRADING;
                identity = auth.getName();
            } else if (method.equals("GET") && path.startsWith("/api/market/")) policy = RequestLimits.Policy.EXTERNAL;
            else if (method.equals("GET") && path.startsWith("/api/")
                    && !path.equals("/api/live") && !path.equals("/api/health") && !path.equals("/api/ready")) policy = RequestLimits.Policy.READS;
        }
        long retry = policy == null ? 0 : limits.acquire(policy, identity);
        if (retry == 0) { chain.doFilter(request, response); return; }
        if (metrics != null) metrics.increment("rate_limit." + policy.name().toLowerCase(java.util.Locale.ROOT));
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Too many requests. Wait " + retry
                + " seconds before trying again.\",\"code\":\"RATE_LIMITED\",\"retryAfterSeconds\":" + retry + "}");
    }
}
