package com.misho.exchange.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record RegisterUserRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9_]{3,50}",
                message = "Username must contain 3 to 50 letters, digits, or underscores.") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank @Size(min = 15, max = 128,
                message = "Password must contain 15 to 128 characters.") String password) {

    public RegisterUserRequest {
        username = username == null ? null : username.strip().toLowerCase(Locale.ROOT);
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "RegisterUserRequest[credentials redacted]";
    }
}
