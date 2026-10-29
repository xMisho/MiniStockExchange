package com.misho.exchange.support;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

public class ApiTestClient {

    private final int port;
    private final ObjectMapper json;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private String csrfToken;
    private String csrfHeader;

    public ApiTestClient(int port, ObjectMapper json) {
        this.port = port;
        this.json = json;
    }

    public synchronized void refreshCsrf() throws Exception {
        HttpResponse<String> response = get("/api/auth/csrf");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("CSRF endpoint returned " + response.statusCode());
        }
        var token = json.readTree(response.body());
        csrfToken = token.get("token").asText();
        csrfHeader = token.get("headerName").asText();
    }

    private synchronized void ensureCsrf() throws Exception {
        if (csrfToken == null) {
            refreshCsrf();
        }
    }

    public HttpResponse<String> get(String path) throws Exception {
        return client.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> postJson(String path, String body) throws Exception {
        ensureCsrf();
        return postWithToken(path, body, "application/json", csrfToken);
    }

    public HttpResponse<String> postWithToken(String path, String body, String contentType, String token)
            throws Exception {
        var request = builder(path).header("Content-Type", contentType);
        if (token != null) {
            request.header(csrfHeader == null ? "X-CSRF-TOKEN" : csrfHeader, token);
        }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> changeJson(String method, String path, String body, boolean withCsrf)
            throws Exception {
        ensureCsrf();
        var request = builder(path).header("Content-Type", "application/json");
        if (withCsrf) { request.header(csrfHeader, csrfToken); }
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> login(String username, String password) throws Exception {
        ensureCsrf();
        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpResponse<String> response = postWithToken("/api/auth/login", body,
                "application/x-www-form-urlencoded", csrfToken);
        if (response.statusCode() == 200) {
            refreshCsrf();
        }
        return response;
    }

    public HttpResponse<String> logout() throws Exception {
        ensureCsrf();
        HttpResponse<String> response = postWithToken("/api/auth/logout", "", "application/json", csrfToken);
        csrfToken = null;
        return response;
    }

    public String sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .map(cookie -> cookie.getValue()).findFirst().orElse(null);
    }

    public String csrfToken() {
        return csrfToken;
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }
}
