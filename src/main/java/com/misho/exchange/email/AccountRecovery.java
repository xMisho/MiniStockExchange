package com.misho.exchange.email;

import java.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AccountRecovery {
    private final JdbcTemplate jdbc;
    private final EmailConfiguration config;
    private final EmailOutbox outbox;
    private final PasswordEncoder passwords;
    public AccountRecovery(JdbcTemplate jdbc, EmailConfiguration config, EmailOutbox outbox, PasswordEncoder passwords) {
        this.jdbc=jdbc;this.config=config;this.outbox=outbox;this.passwords=passwords;
    }
    public record Preferences(boolean configured, boolean verified, boolean alertEmails) { }
    public Preferences preferences(String username) {
        return jdbc.queryForObject("SELECT email_verified,alert_emails FROM exchange.users WHERE username=?",(r,i)->new Preferences(config.enabled,r.getBoolean(1),r.getBoolean(2)),username);
    }
    @Transactional public void preferences(String username, boolean enabled) {
        if (enabled && (!config.enabled || !preferences(username).verified())) throw new IllegalArgumentException("Verify your email before enabling price-alert emails.");
        jdbc.update("UPDATE exchange.users SET alert_emails=? WHERE username=?",enabled,username);
        if (!enabled) jdbc.update("UPDATE exchange.email_outbox SET status='CANCELLED',payload=NULL WHERE user_id=(SELECT id FROM exchange.users WHERE username=?) AND kind='ALERT' AND status='PENDING'",username);
    }
    @Transactional public void forgot(String email) {
        requireConfigured();
        issue(email.strip().toLowerCase(Locale.ROOT),"RESET");
    }
    @Transactional public void verify(String username) {
        requireConfigured();
        String email=jdbc.queryForObject("SELECT email FROM exchange.users WHERE username=?",String.class,username);
        issue(email,"VERIFY");
    }
    private void requireConfigured() {
        if (!config.enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Email delivery is not configured yet.");
    }
    private void issue(String email, String purpose) {
        // Account lookup and queue insertion happen in one transaction; public responses never disclose existence.
        var ids=jdbc.queryForList("SELECT id FROM exchange.users WHERE email=? FOR UPDATE",Long.class,email);
        if (ids.isEmpty() || !config.accepts(email)) return;
        long user=ids.get(0);
        int recent=jdbc.queryForObject("SELECT count(*) FROM exchange.email_tokens WHERE user_id=? AND purpose=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 hour'",Integer.class,user,purpose);
        int cooldown=jdbc.queryForObject("SELECT count(*) FROM exchange.email_tokens WHERE user_id=? AND purpose=? AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 minute'",Integer.class,user,purpose);
        if (recent>=5 || cooldown>0) return;
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hash=hash(token);
        Instant expires=Instant.now().plusSeconds(purpose.equals("RESET")?1800:86400);
        jdbc.update("UPDATE exchange.email_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND purpose=? AND used_at IS NULL",user,purpose);
        jdbc.update("UPDATE exchange.email_outbox SET status='CANCELLED',payload=NULL WHERE user_id=? AND kind=? AND status='PENDING'",user,purpose);
        jdbc.update("INSERT INTO exchange.email_tokens(token_hash,user_id,purpose,expires_at) VALUES (?,?,?,?)",hash,user,purpose,Timestamp.from(expires));
        String link=config.publicUrl+"/#/"+(purpose.equals("RESET")?"reset-password":"verify-email")+"?token="+token;
        String subject=purpose.equals("RESET")?"Reset your Verdex password":"Verify your Verdex email";
        outbox.enqueue(user,email,purpose,purpose+":"+hash,subject,
                subject+"\n\n"+link+"\n\nThis link expires in "+(purpose.equals("RESET")?"30 minutes":"24 hours")+" and works once. If you did not request this, ignore this message. No change happens until you confirm in Verdex.",expires);
    }
    @Transactional public void reset(String token,String password) {
        if (password==null || password.isBlank() || password.length()<15 || password.length()>128) throw new IllegalArgumentException("Password must contain 15 to 128 characters.");
        long user=consume(token,"RESET");
        jdbc.update("UPDATE exchange.users SET password_hash=?,auth_version=auth_version+1 WHERE id=?",passwords.encode(password),user);
        jdbc.update("DELETE FROM exchange.persistent_logins WHERE username=(SELECT username FROM exchange.users WHERE id=?)",user);
        jdbc.update("UPDATE exchange.email_tokens SET used_at=CURRENT_TIMESTAMP WHERE user_id=? AND purpose='RESET' AND used_at IS NULL",user);
    }
    @Transactional public void confirmEmail(String token) { long user=consume(token,"VERIFY");jdbc.update("UPDATE exchange.users SET email_verified=TRUE WHERE id=?",user); }
    private long consume(String token,String purpose) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String digest=hash(token);
        // Lock user first, matching issuance/preferences and serializing competing reset requests.
        var ids=jdbc.queryForList("SELECT user_id FROM exchange.email_tokens WHERE token_hash=? AND purpose=?",Long.class,digest,purpose);
        if (ids.isEmpty()) throw invalid();
        long user=ids.get(0);
        jdbc.queryForObject("SELECT id FROM exchange.users WHERE id=? FOR UPDATE",Long.class,user);
        if (jdbc.update("UPDATE exchange.email_tokens SET used_at=CURRENT_TIMESTAMP WHERE token_hash=? AND purpose=? AND used_at IS NULL AND expires_at>CURRENT_TIMESTAMP",digest,purpose)!=1) throw invalid();
        jdbc.update("UPDATE exchange.email_outbox SET status='CANCELLED',payload=NULL WHERE user_id=? AND kind=? AND status='PENDING'",user,purpose);
        return user;
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("This link is invalid, expired, or already used. Request a new one."); }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
