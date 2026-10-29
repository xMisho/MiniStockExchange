package com.misho.exchange;

import java.math.BigDecimal;

public final class Trade {

    private final long buyOrderId;
    private final long sellOrderId;
    private final BigDecimal executionPrice;
    private final int quantity;
    private final long timestamp;

    public Trade(long buyOrderId, long sellOrderId, BigDecimal executionPrice, int quantity) {
        this(buyOrderId, sellOrderId, executionPrice, quantity, System.currentTimeMillis());
    }

    public Trade(long buyOrderId, long sellOrderId, BigDecimal executionPrice, int quantity, long timestamp) {
        if (buyOrderId <= 0 || sellOrderId <= 0) {
            throw new IllegalArgumentException("Order IDs must be positive.");
        }
        if (buyOrderId == sellOrderId) {
            throw new IllegalArgumentException("Buy and sell order IDs must be different.");
        }
        if (executionPrice == null || executionPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Execution price must be positive.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive.");
        }

        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.executionPrice = executionPrice;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    public long getBuyOrderId() {
        return buyOrderId;
    }

    public long getSellOrderId() {
        return sellOrderId;
    }

    public BigDecimal getExecutionPrice() {
        return executionPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
