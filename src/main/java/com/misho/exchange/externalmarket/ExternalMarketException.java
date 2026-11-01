package com.misho.exchange.externalmarket;

final class ExternalMarketException extends RuntimeException {
    private final String code;
    ExternalMarketException(String code, String message) { super(message); this.code = code; }
    String code() { return code; }
}
