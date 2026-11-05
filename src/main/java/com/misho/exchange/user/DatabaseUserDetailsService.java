package com.misho.exchange.user;

import java.util.Locale;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository repository;

    public DatabaseUserDetailsService(UserRepository repository) {
        this.repository = repository;
    }

    public static final class VersionedUser extends User {
        private static final long serialVersionUID = 1L;
        private final long version;
        VersionedUser(UserEntity user) {
            super(user.getUsername(), user.getPasswordHash(), java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
            version=user.getAuthVersion();
        }
        public long version() { return version; }
    }
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        String normalized = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        // Usernames cannot contain '@', so the two login identifiers are unambiguous.
        // Always return the canonical username as the principal (sessions, ownership, remember-me).
        UserEntity user = (normalized.contains("@") ? repository.findByEmail(normalized) : repository.findByUsername(normalized))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
        return new VersionedUser(user);

    }
}
