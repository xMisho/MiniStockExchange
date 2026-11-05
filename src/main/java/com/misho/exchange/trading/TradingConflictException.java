package com.misho.exchange.trading;

public class TradingConflictException extends IllegalArgumentException {
    private final String code;
    public TradingConflictException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
}
