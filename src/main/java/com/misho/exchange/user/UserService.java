package com.misho.exchange.user;

import jakarta.validation.Validator;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder, Validator validator) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public Optional<UserResponse> getProfile(String username) {
        return repository.findByUsername(username).map(UserResponse::from);
    }

    @Transactional
    public UserResponse registerUser(RegisterUserRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Registration details are required.");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            throw new IllegalArgumentException(violation.getPropertyPath() + ": " + violation.getMessage());
        }
        if (repository.existsByUsernameOrEmail(request.username(), request.email())) {
            throw new UserAlreadyExistsException();
        }
        UserEntity user = new UserEntity(request, passwordEncoder.encode(request.password()));
        try {
            repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getMostSpecificCause() instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                throw new UserAlreadyExistsException();
            }
            throw exception;
        }
        return UserResponse.from(user);
    }
}
