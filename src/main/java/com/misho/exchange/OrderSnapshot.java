package com.misho.exchange;

import java.math.BigDecimal;

public record OrderSnapshot(long id, OrderSide side, OrderType type, BigDecimal price,
                            int originalQuantity, int remainingQuantity, int filledQuantity,
                            OrderStatus status, long timestamp) {

    static OrderSnapshot from(Order order) {
        return new OrderSnapshot(order.getOrderId(), order.getSide(), order.getOrderType(),
                order.getPrice(), order.getOriginalQuantity(), order.getRemainingQuantity(),
                order.getFilledQuantity(), order.getStatus(), order.getTimestamp());
    }
}
