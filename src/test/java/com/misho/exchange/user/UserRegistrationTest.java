package com.misho.exchange.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.misho.exchange.support.ApiTestClient;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserRegistrationTest {

    private static final String PASSWORD = "RegistrationPassword123!";

    @Autowired
    private UserService service;

    @Autowired
    private UserRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    private int port;

    private final List<String> testUsernames = new ArrayList<>();

    private String username() {
        String username = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        testUsernames.add(username);
        return username;
    }

    @AfterEach
    void removeOnlyThisTestsUsers() {
        for (String username : testUsernames) {
            jdbc.update("DELETE FROM exchange.users WHERE username = ?", username);
        }
    }

    @Test
    void registersNormalizedUserAndReturnsOnlySafeMetadata() throws Exception {
        String username = username();
        Instant before = Instant.now().minusSeconds(1);
        HttpResponse<String> response = post(body(" " + username.toUpperCase(Locale.ROOT) + " ",
                " " + username.toUpperCase(Locale.ROOT) + "@EXAMPLE.TEST ", PASSWORD));
        assertEquals(201, response.statusCode());
        var result = json.readTree(response.body());
        long id = result.get("id").asLong();
        assertTrue(id > 0);
        assertEquals(username, result.get("username").asText());
        assertEquals(username + "@example.test", result.get("email").asText());
        assertEquals(5, result.size());
        assertEquals("USER", result.get("role").asText());
        assertFalse(result.has("password"));
        assertFalse(result.has("passwordHash"));
        assertFalse(response.body().contains(PASSWORD));
        Instant createdAt = Instant.parse(result.get("createdAt").asText());
        assertFalse(createdAt.isBefore(before));
        assertFalse(createdAt.isAfter(Instant.now()));

        UserEntity saved = repository.findById(id).orElseThrow();
        assertEquals(createdAt, saved.getCreatedAt());
        String hash = saved.getPasswordHash();
        assertTrue(hash.startsWith("{pbkdf2-sha256}"));
        assertNotEquals(PASSWORD, hash);
        assertTrue(passwordEncoder.matches(PASSWORD, hash));
        assertFalse(passwordEncoder.matches("A different password!", hash));
        assertEquals(hash, jdbc.queryForObject(
                "SELECT password_hash FROM exchange.users WHERE id = ?", String.class, id));
        assertFalse(json.writeValueAsString(saved).contains(hash));
    }

    @Test
    void equalPasswordsHaveDifferentRandomSalts() {
        String first = username();
        String second = username();
        UserResponse a = service.registerUser(new RegisterUserRequest(first, first + "@example.test", PASSWORD));
        UserResponse b = service.registerUser(new RegisterUserRequest(second, second + "@example.test", PASSWORD));
        String firstHash = repository.findById(a.id()).orElseThrow().getPasswordHash();
        String secondHash = repository.findById(b.id()).orElseThrow().getPasswordHash();
        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordEncoder.matches(PASSWORD, firstHash));
        assertTrue(passwordEncoder.matches(PASSWORD, secondHash));
    }

    @Test
    void preservesPasswordWhitespaceInsteadOfSilentlyChangingThePassword() {
        String username = username();
        String password = "  A password with spaces  ";
        UserResponse saved = service.registerUser(new RegisterUserRequest(
                username, username + "@example.test", password));
        String hash = repository.findById(saved.id()).orElseThrow().getPasswordHash();
        assertTrue(passwordEncoder.matches(password, hash));
        assertFalse(passwordEncoder.matches(password.strip(), hash));
    }

    @Test
    void rejectsInvalidAndMalformedRegistrationsWithoutSavingThem() throws Exception {
        String username = username();
        String email = username + "@example.test";
        List<String> invalid = List.of(
                "{}", "null", "[]", "{broken", "{\"password\":\"" + PASSWORD + "\",\"username\":{}}",
                body(null, email, PASSWORD), body("ab", email, PASSWORD),
                body("x".repeat(51), email, PASSWORD), body("bad name", email, PASSWORD),
                body(username, null, PASSWORD), body(username, " ", PASSWORD),
                body(username, "not-an-email", PASSWORD),
                body(username, "x".repeat(250) + "@example.test", PASSWORD),
                body(username, email, null), body(username, email, "short"),
                body(username, email, "x".repeat(14)), body(username, email, "x".repeat(129)),
                body(username, email, " ".repeat(20)));
        for (String request : invalid) {
            HttpResponse<String> response = post(request);
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains(PASSWORD));
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM exchange.users WHERE username = ?", Integer.class, username));
        assertThrows(IllegalArgumentException.class, () -> service.registerUser(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerUser(new RegisterUserRequest(username, email, "short")));
    }

    @Test
    void duplicateUsernameOrEmailReturns409AndLeavesOriginalUnchanged() throws Exception {
        String username = username();
        String other = username();
        String email = username + "@example.test";
        assertEquals(201, post(body(username, email, PASSWORD)).statusCode());
        String originalHash = jdbc.queryForObject(
                "SELECT password_hash FROM exchange.users WHERE username = ?", String.class, username);
        assertEquals(409, post(body(username.toUpperCase(Locale.ROOT), other + "@example.test", PASSWORD)).statusCode());
        HttpResponse<String> duplicate = post(body(other, email.toUpperCase(Locale.ROOT), PASSWORD));
        assertEquals(409, duplicate.statusCode());
        assertEquals("Username or email is already registered.", json.readTree(duplicate.body()).get("error").asText());
        assertEquals(email, jdbc.queryForObject(
                "SELECT email FROM exchange.users WHERE username = ?", String.class, username));
        assertEquals(originalHash, jdbc.queryForObject(
                "SELECT password_hash FROM exchange.users WHERE username = ?", String.class, username));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM exchange.users WHERE username = ?", Integer.class, other));
    }

    @Test
    void concurrentUsernameDuplicatesCreateExactlyOneUser() throws Exception {
        String username = username();
        assertOneRegistrationWins(body(username, username + "@example.test", PASSWORD),
                body(username.toUpperCase(Locale.ROOT), username + "2@example.test", PASSWORD));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM exchange.users WHERE username = ?", Integer.class, username));
    }

    @Test
    void concurrentEmailDuplicatesCreateExactlyOneUser() throws Exception {
        String first = username();
        String second = username();
        String email = first + "@example.test";
        assertOneRegistrationWins(body(first, email, PASSWORD),
                body(second, email.toUpperCase(Locale.ROOT), PASSWORD));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM exchange.users WHERE email = ?", Integer.class, email));
    }

    @Test
    void requestToStringAndSerializationDoNotExposePassword() throws Exception {
        String username = username();
        RegisterUserRequest request = new RegisterUserRequest(username, username + "@example.test", PASSWORD);
        assertFalse(request.toString().contains(PASSWORD));
        assertFalse(json.writeValueAsString(request).contains(PASSWORD));
        assertNotNull(request.password());
    }

    private void assertOneRegistrationWins(String firstBody, String secondBody) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> { start.await(); return post(firstBody).statusCode(); });
            var second = workers.submit(() -> { start.await(); return post(secondBody).statusCode(); });
            start.countDown();
            List<Integer> statuses = new ArrayList<>(List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
        } finally {
            workers.shutdownNow();
        }
    }

    private String body(String username, String email, String password) {
        var values = new LinkedHashMap<String, String>();
        values.put("username", username);
        values.put("email", email);
        values.put("password", password);
        return json.writeValueAsString(values);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return new ApiTestClient(port, json).postJson("/api/users", body);
    }
}
