package com.misho.exchange.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Repository;

@Repository
public class RememberMeTokenRepository implements PersistentTokenRepository {
    private final JdbcTemplate jdbc;
    public RememberMeTokenRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public void createNewToken(PersistentRememberMeToken token) {
        jdbc.update("DELETE FROM exchange.persistent_logins WHERE last_used < ?",
                Timestamp.from(Instant.now().minus(14, ChronoUnit.DAYS)));
        jdbc.update("INSERT INTO exchange.persistent_logins (username, series, token, last_used) VALUES (?, ?, ?, ?)",
                token.getUsername(), token.getSeries(), token.getTokenValue(), new Timestamp(token.getDate().getTime()));
    }
    @Override
    public void updateToken(String series, String tokenValue, Date lastUsed) {
        if (jdbc.update("UPDATE exchange.persistent_logins SET token = ?, last_used = ? WHERE series = ?",
                tokenValue, new Timestamp(lastUsed.getTime()), series) != 1) {
            throw new IllegalStateException("Remembered login is no longer available.");
        }
    }
    @Override
    public PersistentRememberMeToken getTokenForSeries(String series) {
        var tokens = jdbc.query("SELECT username, series, token, last_used FROM exchange.persistent_logins WHERE series = ?",
                (row, index) -> new PersistentRememberMeToken(row.getString("username"), row.getString("series"),
                        row.getString("token"), row.getTimestamp("last_used")), series);
        return tokens.isEmpty() ? null : tokens.get(0);
    }
    @Override
    public void removeUserTokens(String username) {
        jdbc.update("DELETE FROM exchange.persistent_logins WHERE username = ?", username);
    }
}
