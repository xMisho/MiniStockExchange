package com.misho.exchange;

import java.math.BigDecimal;

public class Order {

    private final long orderId;
    private final OrderSide side;
    private BigDecimal price;
    private final int originalQuantity;
    private int remainingQuantity;
    private final long timestamp;

    public Order(long orderId, OrderSide side, BigDecimal price, int quantity) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive.");
        }
        if (side == null) {
            throw new IllegalArgumentException("Side must not be null.");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }

        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.timestamp = System.currentTimeMillis();
    }

    public long getOrderId() {
        return orderId;
    }

    public OrderSide getSide() {
        return side;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getOriginalQuantity() {
        return originalQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public long getTimestamp() {
        return timestamp;
    }

    void amend(BigDecimal newPrice, int newRemainingQuantity) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be positive.");
        }

        if (newRemainingQuantity <= 0) {
            throw new IllegalArgumentException("Remaining quantity must be positive.");
        }

        if (isFilled()) {
            throw new IllegalStateException("Filled order cannot be amended.");
        }

        this.price = newPrice;
        this.remainingQuantity = newRemainingQuantity;
    }

    public void fill(int quantity) {
        if (quantity <= 0 || quantity > remainingQuantity) {
            throw new IllegalArgumentException("Fill quantity must be positive and not exceed remaining quantity.");
        }
        remainingQuantity -= quantity;
    }

    public boolean isFilled() {
        return remainingQuantity == 0;
    }

}
