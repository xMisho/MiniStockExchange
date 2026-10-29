package com.misho.exchange.trading;

class OrderNotFoundException extends IllegalArgumentException {
    OrderNotFoundException() {
        super("Order not found.");
    }
}
