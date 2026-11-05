package com.misho.exchange.email;

/** Provider failures expose only a safe code, never a provider response or message body. */
public final class EmailDeliveryException extends RuntimeException {
    public final boolean retryable;
    public final long retryAfterSeconds;
    public EmailDeliveryException(String code,boolean retryable,long retryAfterSeconds){super(code);this.retryable=retryable;this.retryAfterSeconds=retryAfterSeconds;}
}
