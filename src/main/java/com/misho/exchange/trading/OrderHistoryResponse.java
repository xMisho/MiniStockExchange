package com.misho.exchange.trading;

import java.util.List;
import org.springframework.data.domain.Page;

public record OrderHistoryResponse(List<OrderResponse> content, int page, int size,
                                   long totalElements, int totalPages) {
    public OrderHistoryResponse {
        content = List.copyOf(content);
    }

    static OrderHistoryResponse from(Page<PersistedOrder> orders) {
        return new OrderHistoryResponse(orders.getContent().stream().map(OrderResponse::from).toList(),
                orders.getNumber(), orders.getSize(), orders.getTotalElements(), orders.getTotalPages());
    }
}
