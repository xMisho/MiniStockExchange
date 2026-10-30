package com.misho.exchange.trading;

import java.math.BigDecimal;
import java.time.Instant;

public record TradeResponse(Long tradeId, String symbol, BigDecimal executionPrice,
                            int quantity, Instant executedAt) {
    static TradeResponse from(TradeEntity trade) {
        return new TradeResponse(trade.getId(), trade.getStock().getSymbol(), trade.getExecutionPrice(),
                trade.getQuantity(), trade.getExecutedAt());
    }
}
