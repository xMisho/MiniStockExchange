package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderStatus;
import com.misho.exchange.OrderType;
import java.math.BigDecimal;
import java.time.Instant;

public record PersistedOrder(Long id, Long userId, String symbol, OrderSide side, OrderType type,
                             BigDecimal limitPrice, int originalQuantity, int remainingQuantity,
                             int filledQuantity, OrderStatus status, long prioritySequence,
                             Instant createdAt, Instant updatedAt, Long version) {

    static PersistedOrder from(OrderEntity row) {
        return new PersistedOrder(row.getId(), row.getUser().getId(), row.getStock().getSymbol(),
                row.getSide(), row.getOrderType(), row.getLimitPrice(), row.getOriginalQuantity(),
                row.getRemainingQuantity(), row.getFilledQuantity(), row.getStatus(), row.getPrioritySequence(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion());
    }
}
