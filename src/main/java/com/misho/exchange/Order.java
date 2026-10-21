package com.misho.exchange;

import java.math.BigDecimal;

public class Order {

    private final long orderId;
    private final OrderSide side;
    private final OrderType orderType;
    private BigDecimal price;
    private final int originalQuantity;
    private int remainingQuantity;
    private int filledQuantity;
    private OrderStatus status;
    private final long timestamp;

    public Order(long orderId, OrderSide side, BigDecimal price, int quantity) {
        this(orderId, side, OrderType.LIMIT, price, quantity);
    }

    public Order(long orderId, OrderSide side, OrderType orderType,
                 BigDecimal price, int quantity) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("Order ID must be positive.");
        }
        if (side == null) {
            throw new IllegalArgumentException("Order side must not be null.");
        }
        if (orderType == null) {
            throw new IllegalArgumentException("Order type must not be null.");
        }
        if (orderType == OrderType.LIMIT) {
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Limit price must be positive.");
            }
        } else if (price != null) {
            throw new IllegalArgumentException("Market order must not have a limit price.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }

        this.orderId = orderId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.filledQuantity = 0;
        this.status = OrderStatus.OPEN;
        this.timestamp = System.currentTimeMillis();
    }

    public OrderType getOrderType() {
        return orderType;
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

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public boolean isCancelled() {
        return status == OrderStatus.CANCELLED;
    }

    public boolean isActive() {
        return status == OrderStatus.OPEN || status == OrderStatus.PARTIALLY_FILLED;
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

        if (!isActive()) {
            throw new IllegalStateException("Inactive order cannot be amended.");
        }

        this.price = newPrice;
        this.remainingQuantity = newRemainingQuantity;
    }

    void fill(int quantity) {
        if (!isActive()) {
            throw new IllegalStateException("Inactive order cannot be filled.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Fill quantity must be positive.");
        }
        if (quantity > remainingQuantity) {
            throw new IllegalArgumentException("Fill quantity cannot exceed remaining quantity.");
        }

        remainingQuantity -= quantity;
        filledQuantity += quantity;
        status = remainingQuantity == 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public boolean isFilled() {
        return status == OrderStatus.FILLED;
    }

    void cancel() {
        if (status == OrderStatus.FILLED) {
            throw new IllegalStateException("Filled order cannot be cancelled.");
        }
        if (status == OrderStatus.CANCELLED) {
            return;
        }
        status = OrderStatus.CANCELLED;
    }
}
