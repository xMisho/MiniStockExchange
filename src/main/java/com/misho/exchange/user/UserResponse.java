package com.misho.exchange.user;

import java.time.Instant;

public record UserResponse(Long id, String username, String email, Instant createdAt, UserRole role) {

    static UserResponse from(UserEntity user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getCreatedAt(), user.getRole());
    }
}
