package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long userId, String symbol, OrderSide side, OrderType type,
                            BigDecimal limitPrice, int originalQuantity, int remainingQuantity,
                            int filledQuantity, OrderStatus status, Instant createdAt, Instant updatedAt) {
    static OrderResponse from(PersistedOrder order) {
        return new OrderResponse(order.id(), order.userId(), order.symbol(), order.side(), order.type(),
                order.limitPrice(), order.originalQuantity(), order.remainingQuantity(), order.filledQuantity(),
                order.status(), order.createdAt(), order.updatedAt());
    }
}
