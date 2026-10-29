package com.misho.exchange.trading;

class UnknownMarketException extends IllegalArgumentException {

    UnknownMarketException() {
        super("Stock is not registered.");
    }
}
