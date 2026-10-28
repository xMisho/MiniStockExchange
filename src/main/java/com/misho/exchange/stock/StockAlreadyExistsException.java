package com.misho.exchange.stock;

public class StockAlreadyExistsException extends IllegalArgumentException {

    public StockAlreadyExistsException() {
        super("Stock symbol is already registered.");
    }
}
