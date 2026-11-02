package com.misho.exchange.externalmarket;

public final class ExternalMarketException extends RuntimeException {
    private final String code;
    ExternalMarketException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
