package com.misho.exchange.marketdata;

import java.util.List;
import org.springframework.data.domain.Page;

public record TradeHistoryResponse(List<TradeResponse> content, int page, int size,
                                   long totalElements, int totalPages) {
    public TradeHistoryResponse {
        content = List.copyOf(content);
    }

    static TradeHistoryResponse from(Page<TradeResponse> trades) {
        return new TradeHistoryResponse(trades.getContent(), trades.getNumber(), trades.getSize(),
                trades.getTotalElements(), trades.getTotalPages());
    }
}
