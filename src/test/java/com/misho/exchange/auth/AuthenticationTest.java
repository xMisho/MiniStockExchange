package com.misho.exchange.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.user.RegisterUserRequest;
import com.misho.exchange.user.UserService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationTest {

    private static final String PASSWORD = "AuthenticationTestPassword123!";

    @Autowired
    private UserService users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    private int port;

    private final List<String> usernames = new ArrayList<>();
    private final List<String> stockSymbols = new ArrayList<>();

    private String username() {
        String username = "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        usernames.add(username);
        return username;
    }

    private String account(boolean admin) {
        String username = username();
        users.registerUser(new RegisterUserRequest(username, username + "@example.test", PASSWORD));
        if (admin) {
            jdbc.update("UPDATE exchange.users SET role = 'ADMIN' WHERE username = ?", username);
        }
        return username;
    }

    private ApiTestClient client() {
        return new ApiTestClient(port, json);
    }

    private String stockBody() {
        String symbol = "AUTH" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toUpperCase(Locale.ROOT);
        stockSymbols.add(symbol);
        return json.writeValueAsString(Map.of("symbol", symbol,
                "companyName", "Authentication Test Company", "referencePrice", 100));
    }

    @AfterEach
    void cleanOnlyThisTestsData() {
        for (String symbol : stockSymbols) {
            jdbc.update("DELETE FROM exchange.stocks WHERE symbol = ?", symbol);
        }
        for (String username : usernames) {
            jdbc.update("DELETE FROM exchange.users WHERE username = ?", username);
        }
    }

    @Test
    void anonymousReadersCanViewMarketMetadataButNotCurrentUser() throws Exception {
        ApiTestClient anonymous = client();
        assertEquals(200, anonymous.get("/api/health").statusCode());
        assertEquals(200, anonymous.get("/api/stocks").statusCode());
        HttpResponse<String> me = anonymous.get("/api/auth/me");
        assertEquals(401, me.statusCode());
        assertTrue(me.headers().firstValue("Location").isEmpty());
        assertEquals("Authentication required.", json.readTree(me.body()).get("error").asText());
    }

    @Test
    void publicRegistrationCannotGrantAdminRole() throws Exception {
        String username = username();
        String body = json.writeValueAsString(Map.of("username", username,
                "email", username + "@example.test", "password", PASSWORD, "role", "ADMIN"));
        ApiTestClient browser = client();
        HttpResponse<String> registered = browser.postJson("/api/users", body);
        assertEquals(201, registered.statusCode());
        assertEquals("USER", json.readTree(registered.body()).get("role").asText());
        assertEquals("USER", jdbc.queryForObject(
                "SELECT role FROM exchange.users WHERE username = ?", String.class, username));
        assertEquals(401, browser.get("/api/auth/me").statusCode());
        assertEquals(200, browser.login(username, PASSWORD).statusCode());
        assertEquals(403, browser.postJson("/api/stocks", stockBody()).statusCode());
    }

    @Test
    void validLoginPersistsSessionAndReturnsOnlySafeProfile() throws Exception {
        String username = account(false);
        ApiTestClient browser = client();
        HttpResponse<String> login = browser.login(" " + username.toUpperCase(Locale.ROOT) + " ", PASSWORD);
        assertEquals(200, login.statusCode());
        var profile = json.readTree(login.body());
        assertEquals(username, profile.get("username").asText());
        assertEquals("USER", profile.get("role").asText());
        assertEquals(5, profile.size());
        assertFalse(profile.has("password"));
        assertFalse(profile.has("passwordHash"));
        assertFalse(login.body().contains(PASSWORD));
        assertFalse(login.body().contains("pbkdf2"));
        HttpResponse<String> me = browser.get("/api/auth/me");
        assertEquals(200, me.statusCode());
        assertEquals(profile, json.readTree(me.body()));
        String cookie = String.join(";", login.headers().allValues("Set-Cookie")).toLowerCase(Locale.ROOT);
        assertTrue(cookie.contains("httponly"));
        assertTrue(cookie.contains("samesite=lax"));
        assertEquals(401, client().get("/api/auth/me").statusCode());
    }

    @Test
    void wrongAndUnknownCredentialsGiveSame401AndNoAuthenticatedSession() throws Exception {
        String username = account(false);
        ApiTestClient browser = client();
        HttpResponse<String> wrong = browser.login(username, "WrongPassword123!");
        HttpResponse<String> missing = browser.login(username(), PASSWORD);
        assertEquals(401, wrong.statusCode());
        assertEquals(401, missing.statusCode());
        assertEquals(wrong.body(), missing.body());
        assertEquals("Invalid username or password.", json.readTree(wrong.body()).get("error").asText());
        assertEquals(401, browser.get("/api/auth/me").statusCode());
    }

    @Test
    void loginRotatesSessionIdAndRejectsTheOldAnonymousSession() throws Exception {
        String username = account(false);
        ApiTestClient browser = client();
        browser.refreshCsrf();
        String oldSession = browser.sessionId();
        assertNotNull(oldSession);
        assertEquals(200, browser.login(username, PASSWORD).statusCode());
        assertNotEquals(oldSession, browser.sessionId());
        assertEquals(401, replaySession(oldSession).statusCode());
        assertEquals(200, browser.get("/api/auth/me").statusCode());
    }

    @Test
    void anonymousCannotRegisterStockEvenWithValidCsrf() throws Exception {
        ApiTestClient anonymous = client();
        String body = stockBody();
        assertEquals(401, anonymous.postJson("/api/stocks", body).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exchange.stocks WHERE symbol = ?",
                Integer.class, stockSymbols.get(0)));
    }

    @Test
    void regularUserCannotRegisterStock() throws Exception {
        ApiTestClient browser = client();
        assertEquals(200, browser.login(account(false), PASSWORD).statusCode());
        assertEquals(403, browser.postJson("/api/stocks", stockBody()).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exchange.stocks WHERE symbol = ?",
                Integer.class, stockSymbols.get(0)));
    }

    @Test
    void adminCanRegisterStock() throws Exception {
        ApiTestClient browser = client();
        HttpResponse<String> login = browser.login(account(true), PASSWORD);
        assertEquals(200, login.statusCode());
        assertEquals("ADMIN", json.readTree(login.body()).get("role").asText());
        String body = stockBody();
        assertEquals(201, browser.postJson("/api/stocks", body).statusCode());
        assertEquals(409, browser.postJson("/api/stocks", body).statusCode());
        assertEquals(200, client().get("/api/stocks/" + stockSymbols.get(0)).statusCode());
    }

    @Test
    void unsafeRequestsRequireCsrfEvenForPublicRegistrationAndLogin() throws Exception {
        ApiTestClient browser = client();
        String username = username();
        String body = json.writeValueAsString(Map.of("username", username,
                "email", username + "@example.test", "password", PASSWORD));
        assertEquals(403, browser.postWithToken("/api/users", body, "application/json", null).statusCode());
        assertEquals(403, browser.postWithToken("/api/auth/login", "username=test&password=test",
                "application/x-www-form-urlencoded", null).statusCode());
        browser.refreshCsrf();
        assertEquals(403, browser.postWithToken("/api/users", body, "application/json", "invalid-token").statusCode());
        assertEquals(201, browser.postJson("/api/users", body).statusCode());
    }

    @Test
    void adminWritesRejectMissingAndPreLoginCsrfTokens() throws Exception {
        ApiTestClient browser = client();
        browser.refreshCsrf();
        String anonymousToken = browser.csrfToken();
        assertEquals(200, browser.login(account(true), PASSWORD).statusCode());
        String body = stockBody();
        assertEquals(403, browser.postWithToken("/api/stocks", body, "application/json", null).statusCode());
        assertEquals(403, browser.postWithToken("/api/stocks", body, "application/json", anonymousToken).statusCode());
        assertEquals(201, browser.postJson("/api/stocks", body).statusCode());
    }

    @Test
    void logoutInvalidatesSessionAndItsCsrfToken() throws Exception {
        ApiTestClient browser = client();
        assertEquals(200, browser.login(account(true), PASSWORD).statusCode());
        String oldSession = browser.sessionId();
        String oldToken = browser.csrfToken();
        assertEquals(204, browser.logout().statusCode());
        assertEquals(401, browser.get("/api/auth/me").statusCode());
        assertEquals(401, replaySession(oldSession).statusCode());
        String body = stockBody();
        assertEquals(403, browser.postWithToken("/api/stocks", body, "application/json", oldToken).statusCode());
        assertEquals(401, browser.postJson("/api/stocks", body).statusCode());
    }

    @Test
    void getLogoutAndPostWithoutCsrfDoNotLogTheUserOut() throws Exception {
        ApiTestClient browser = client();
        assertEquals(200, browser.login(account(false), PASSWORD).statusCode());
        assertEquals(403, browser.get("/api/auth/logout").statusCode());
        assertEquals(403, browser.postWithToken("/api/auth/logout", "", "application/json", null).statusCode());
        assertEquals(200, browser.get("/api/auth/me").statusCode());
        assertEquals(204, browser.logout().statusCode());
    }

    private HttpResponse<String> replaySession(String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .timeout(Duration.ofSeconds(5)).header("Cookie", "JSESSIONID=" + sessionId).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
