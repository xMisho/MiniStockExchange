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

    @Autowired
    private com.misho.exchange.user.DatabaseUserDetailsService details;
    @Autowired
    private RememberMeTokenRepository rememberedTokens;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;

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
    void unconfiguredEmailDoesNotPretendToSendRecovery() throws Exception {
        var browser=client();
        var response=browser.postJson("/api/auth/forgot-password", "{\"email\":\"nobody@example.test\"}");
        assertEquals(503,response.statusCode());
        assertTrue(response.body().contains("Email delivery is not configured"));
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
    void emailLoginNormalizesInputAndKeepsCanonicalUsernameAndRememberedSession() throws Exception {
        String username=account(true);
        var browser=client();
        var login=browser.login("  "+username.toUpperCase(Locale.ROOT)+"@EXAMPLE.TEST  ",PASSWORD,true);
        assertEquals(200,login.statusCode());
        assertEquals(username,json.readTree(login.body()).get("username").asText());
        assertEquals("ADMIN",json.readTree(login.body()).get("role").asText());
        assertEquals(username,jdbc.queryForObject("SELECT username FROM exchange.persistent_logins WHERE username=?",String.class,username));
        assertEquals(200,browser.get("/api/auth/me").statusCode());
        browser.forgetSessionCookie();
        assertEquals(username,json.readTree(browser.get("/api/auth/me").body()).get("username").asText());
        assertEquals(204,browser.logout().statusCode());
        assertEquals(200,client().login(username,PASSWORD).statusCode());
    }

    @Test
    void unknownEmailAndWrongPasswordUseTheSameFailureResponse() throws Exception {
        String username=account(false);
        var browser=client();
        var wrong=browser.login(username+"@example.test","WrongPassword123!");
        var missing=browser.login("missing_"+username+"@example.test",PASSWORD);
        assertEquals(401,wrong.statusCode());assertEquals(401,missing.statusCode());assertEquals(wrong.body(),missing.body());
        assertEquals(401,browser.get("/api/auth/me").statusCode());
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

    @Test
    void rememberedLoginIsOptInAndPersistsAcrossANewSession() throws Exception {
        String username = account(true);
        var ordinary = client();
        assertEquals(200, ordinary.login(username, PASSWORD).statusCode());
        assertEquals(null, ordinary.cookie("VERDEX_REMEMBER_ME"));
        var browser = client();
        var response = browser.login(username, PASSWORD, true);
        assertEquals(200, response.statusCode());
        String cookie = browser.cookie("VERDEX_REMEMBER_ME");
        assertNotNull(cookie);
        String headers = String.join(";", response.headers().allValues("Set-Cookie")).toLowerCase(Locale.ROOT);
        assertTrue(headers.contains("max-age=1209600"));
        assertTrue(headers.contains("httponly"));
        assertTrue(headers.contains("samesite=lax"));
        String token = jdbc.queryForObject("SELECT token FROM exchange.persistent_logins WHERE username = ?", String.class, username);
        browser.forgetSessionCookie();
        assertEquals(200, browser.get("/api/stocks").statusCode());
        assertEquals(token, jdbc.queryForObject("SELECT token FROM exchange.persistent_logins WHERE username = ?", String.class, username));
        var restored = browser.get("/api/auth/me");
        assertEquals(200, restored.statusCode());
        assertEquals("ADMIN", json.readTree(restored.body()).get("role").asText());
        assertNotEquals(token, jdbc.queryForObject("SELECT token FROM exchange.persistent_logins WHERE username = ?", String.class, username));
        assertNotNull(browser.sessionId());
        assertEquals(403, browser.postWithToken("/api/stocks", stockBody(), "application/json", null).statusCode());
        browser.refreshCsrf();
        assertEquals(201, browser.postJson("/api/stocks", stockBody()).statusCode());
        String rotated = browser.cookie("VERDEX_REMEMBER_ME");
        assertEquals(204, browser.logout().statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM exchange.persistent_logins WHERE username = ?", Integer.class, username));
        assertEquals(401, replayRememberedCookie(rotated).statusCode());
    }

    @Test
    void expiredTamperedAndInvalidCredentialsCannotRestoreRememberedLogin() throws Exception {
        String username = account(false);
        var browser = client();
        assertEquals(401, browser.login(username, "WrongPassword123!", true).statusCode());
        assertEquals(null, browser.cookie("VERDEX_REMEMBER_ME"));
        assertEquals(200, browser.login(username, PASSWORD, true).statusCode());
        String cookie = browser.cookie("VERDEX_REMEMBER_ME");
        assertEquals(401, replayRememberedCookie(cookie + "tampered").statusCode());
        assertEquals(200, browser.login(username, PASSWORD, true).statusCode());
        jdbc.update("UPDATE exchange.persistent_logins SET last_used = CURRENT_TIMESTAMP - INTERVAL '15 days' WHERE username = ?", username);
        browser.forgetSessionCookie();
        assertEquals(401, browser.get("/api/auth/me").statusCode());
    }

    @Test
    void storedRememberedLoginWorksWithAFreshServiceInstance() throws Exception {
        String username = account(false);
        var browser = client();
        assertEquals(200, browser.login(username, PASSWORD, true).statusCode());
        var freshService = new RememberedLoginService(details, rememberedTokens, new org.springframework.mock.env.MockEnvironment(), jdbc, transactions);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/auth/me");
        request.setServletPath("/api/auth/me");
        request.setCookies(new jakarta.servlet.http.Cookie("VERDEX_REMEMBER_ME", browser.cookie("VERDEX_REMEMBER_ME")));
        var authentication = freshService.autoLogin(request, new org.springframework.mock.web.MockHttpServletResponse());
        assertNotNull(authentication);
        assertEquals(username, authentication.getName());
        assertEquals(200, browser.login(username, PASSWORD, false).statusCode());
        assertEquals(null, browser.cookie("VERDEX_REMEMBER_ME"));
    }

    @Test
    void accessResponsesDistinguishLoginPermissionAndCsrfFailures() throws Exception {
        var anonymous = client();
        var required = json.readTree(anonymous.get("/api/auth/me").body());
        assertEquals("AUTHENTICATION_REQUIRED", required.get("code").asText());
        var browser = client();
        assertEquals(200, browser.login(account(false), PASSWORD).statusCode());
        var denied = browser.postJson("/api/stocks", stockBody());
        assertEquals(403, denied.statusCode());
        assertEquals("ACCESS_DENIED", json.readTree(denied.body()).get("code").asText());
        assertFalse(json.readTree(denied.body()).get("error").asText().contains("CSRF"));
        var csrf = browser.postWithToken("/api/stocks", stockBody(), "application/json", null);
        assertEquals(403, csrf.statusCode());
        assertEquals("CSRF_INVALID", json.readTree(csrf.body()).get("code").asText());
        assertTrue(json.readTree(csrf.body()).get("error").asText().contains("Security token"));
    }

    private HttpResponse<String> replayRememberedCookie(String cookie) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("Cookie", "VERDEX_REMEMBER_ME=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> replaySession(String sessionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .timeout(Duration.ofSeconds(5)).header("Cookie", "JSESSIONID=" + sessionId).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
