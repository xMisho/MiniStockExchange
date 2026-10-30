package com.misho.exchange.trading;

public class UnknownMarketException extends IllegalArgumentException {

    UnknownMarketException() {
        super("Stock is not registered.");
    }
}
