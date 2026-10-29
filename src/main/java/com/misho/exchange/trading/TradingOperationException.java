package com.misho.exchange.trading;

public class TradingOperationException extends RuntimeException {

    TradingOperationException(RuntimeException cause) {
        super("Trading operation failed; inspect persisted history before retrying.", cause);
    }
}
