package com.misho.exchange.email;

public interface EmailTransport {
    String send(String idempotencyKey, String jsonBody);
}
