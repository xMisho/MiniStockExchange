package com.misho.exchange.stock;

import java.math.BigDecimal;

public record StockResponse(String symbol, String companyName, BigDecimal referencePrice) {

    static StockResponse from(StockEntity stock) {
        return new StockResponse(stock.getSymbol(), stock.getCompanyName(), stock.getReferencePrice());
    }
}
