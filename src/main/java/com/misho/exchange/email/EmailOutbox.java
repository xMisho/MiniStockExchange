package com.misho.exchange.email;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Enqueued inside the caller's transaction; raw recovery links are encrypted at rest. */
@Service
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final EmailConfiguration config;
    private final ObjectMapper json;
    public EmailOutbox(JdbcTemplate jdbc, EmailConfiguration config, ObjectMapper json) { this.jdbc=jdbc; this.config=config; this.json=json; }
    public void enqueue(long user, String recipient, String kind, String dedupe, String subject, String text, Instant expires) {
        if (!config.accepts(recipient)) return;
        String body = json.writeValueAsString(Map.of("from", config.from, "to", java.util.List.of(recipient), "subject", subject, "text", text));
        jdbc.update("INSERT INTO exchange.email_outbox(id,user_id,dedupe_key,kind,payload,expires_at) VALUES (?,?,?,?,?,?) ON CONFLICT(dedupe_key) DO NOTHING",
                UUID.randomUUID(), user, dedupe, kind, encrypt(body), Timestamp.from(expires));
    }
    String encrypt(String text) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(config.encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Could not protect queued email."); }
    }
    String decrypt(String encoded) {
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(config.encryptionKey, "AES"), new GCMParameterSpec(128, value, 0, 12));
            return new String(cipher.doFinal(value, 12, value.length-12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Queued email could not be decrypted."); }
    }
}
