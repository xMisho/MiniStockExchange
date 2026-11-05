package com.misho.exchange.email;

import java.net.URI;
import java.util.Base64;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EmailConfiguration {
    public final boolean enabled, workersEnabled;
    public final String apiKey, from, publicUrl, allowedRecipient;
    public final byte[] encryptionKey;
    public EmailConfiguration(Environment env) {
        workersEnabled = env.getProperty("verdex.email.workers-enabled", Boolean.class, true);
        enabled = env.getProperty("verdex.email.enabled", Boolean.class, false);
        apiKey = env.getProperty("verdex.email.api-key", "");
        from = env.getProperty("verdex.email.from", "");
        publicUrl = env.getProperty("verdex.email.public-url", "http://localhost:5173").replaceAll("/+$", "");
        allowedRecipient = env.getProperty("verdex.email.allowed-recipient", "").strip().toLowerCase(java.util.Locale.ROOT);
        byte[] key = new byte[0];
        if (enabled) {
            if (apiKey.isBlank() || from.isBlank() || from.contains("\n") || from.contains("\r")) throw new IllegalStateException("Configure the email API key and sender before enabling delivery.");
            URI url = URI.create(publicUrl);
            if (url.getHost() == null || url.getRawQuery() != null || url.getRawFragment() != null || url.getUserInfo() != null
                    || !("https".equals(url.getScheme()) || ("http".equals(url.getScheme()) && java.util.Set.of("localhost", "127.0.0.1").contains(url.getHost()))))
                throw new IllegalStateException("Email public URL must be HTTPS (localhost HTTP is allowed for development).");
            try { key = Base64.getDecoder().decode(env.getProperty("verdex.email.encryption-key", "")); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Email encryption key must be base64."); }
            if (key.length != 32) throw new IllegalStateException("Email encryption key must contain 32 random bytes encoded as base64.");
        }
        encryptionKey = key;
    }
    public boolean accepts(String recipient) { return enabled && (allowedRecipient.isEmpty() || allowedRecipient.equalsIgnoreCase(recipient)); }
}
