package com.misho.exchange.auth;

import com.misho.exchange.user.UserResponse;
import com.misho.exchange.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService users;
    private final com.misho.exchange.email.EmailConfiguration email;

    public AuthController(UserService users, com.misho.exchange.email.EmailConfiguration email) {
        this.users = users;
        this.email = email;
    }

    public record CsrfResponse(String token, String headerName, String parameterName) { }

    public record Capabilities(boolean passwordRecovery) { }

    @GetMapping("/capabilities")
    public Capabilities capabilities() { return new Capabilities(email.enabled); }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName(), token.getParameterName());
    }

    @GetMapping("/me")
    public UserResponse currentUser(Authentication authentication) {
        return users.getProfile(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
