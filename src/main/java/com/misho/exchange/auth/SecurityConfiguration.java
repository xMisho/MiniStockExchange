package com.misho.exchange.auth;

import com.misho.exchange.user.UserService;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, UserService users, org.springframework.jdbc.core.JdbcTemplate jdbc, ObjectMapper json, RememberedLoginService rememberedLogin, com.misho.exchange.ratelimit.RequestLimits limits, com.misho.exchange.observability.TradingMetrics metrics)
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.svg", "/assets/**", "/THIRD_PARTY_NOTICES.md", "/api/ready", "/api/live", "/api/health", "/api/events", "/api/stocks", "/api/stocks/*",
                                "/api/auth/csrf", "/api/auth/capabilities", "/api/market/search", "/api/market/session", "/api/market/instruments", "/api/market/*/quote", "/api/market/*/candles", "/api/stocks/*/snapshot", "/api/stocks/*/depth", "/api/stocks/*/trades", "/api/stocks/*/candles").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/users", "/api/auth/login", "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/verify-email").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/stocks", "/api/admin/paper-allocations").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/paper/instruments").permitAll()
                        .requestMatchers("/api/paper/**").authenticated()
                        .requestMatchers("/api/auth/email-preferences", "/api/auth/email-verification").authenticated()
                        .requestMatchers("/api/watchlist", "/api/watchlist/*", "/api/alerts", "/api/alerts/*", "/api/portfolio").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/orders/me", "/api/orders/*", "/api/orders/*/lifecycle", "/api/stocks/*/order-preview", "/api/stocks/*/balance").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/stocks/*/orders").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/orders/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/*").authenticated()
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/*", "/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Authentication required.\",\"code\":\"AUTHENTICATION_REQUIRED\"}");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            boolean csrf = exception instanceof org.springframework.security.web.csrf.CsrfException;
                            response.getWriter().write(json.writeValueAsString(java.util.Map.of(
                                    "error", csrf ? "Security token missing or invalid. Refresh the page and try again." : "You do not have permission to perform this action.",
                                    "code", csrf ? "CSRF_INVALID" : "ACCESS_DENIED")));
                        }))
                .formLogin(form -> form
                        .loginPage("/api/auth/login")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            var user = users.getProfile(authentication.getName()).orElseThrow();
                            response.setStatus(200);
                            response.setContentType("application/json");
                            response.getWriter().write(json.writeValueAsString(user));
                        })
                        .failureHandler((request, response, exception) -> {
                            metrics.increment("login.failures");
                            response.setStatus(401);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"error\":\"Invalid username or password.\"}");
                        }))
                .rememberMe(remember -> remember
                        .rememberMeServices(rememberedLogin)
                        .key(rememberedLogin.getKey()))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .deleteCookies("JSESSIONID", "VERDEX_REMEMBER_ME")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
        http.addFilterBefore(new CredentialVersionFilter(jdbc), org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.addFilterBefore(new com.misho.exchange.ratelimit.RateLimitFilter(limits, true, metrics),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(new com.misho.exchange.ratelimit.RateLimitFilter(limits, false, metrics),
                org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }
}
