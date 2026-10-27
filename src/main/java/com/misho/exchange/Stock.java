package com.misho.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public class Stock {

    private final String symbol;
    private final String companyName;
    private final BigDecimal referencePrice;
    private volatile BigDecimal lastTradedPrice;

    public Stock(String symbol, String companyName, BigDecimal referencePrice) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must not be blank.");
        }
        if (companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("Company name must not be blank.");
        }
        if (referencePrice == null || referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Reference price must be positive.");
        }

        this.symbol = symbol.trim().toUpperCase(Locale.ROOT);
        this.companyName = companyName;
        this.referencePrice = referencePrice;
        this.lastTradedPrice = null;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getCompanyName() {
        return companyName;
    }

    public BigDecimal getReferencePrice() {
        return referencePrice;
    }

    public BigDecimal getLastTradedPrice() {
        return lastTradedPrice;
    }

    public BigDecimal getPriceChange() {
        BigDecimal lastPrice = lastTradedPrice;
        return lastPrice == null ? null : lastPrice.subtract(referencePrice);
    }

    public BigDecimal getPriceChangePercent() {
        BigDecimal lastPrice = lastTradedPrice;
        if (lastPrice == null) {
            return null;
        }
        return lastPrice.subtract(referencePrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(referencePrice, 4, RoundingMode.HALF_UP);
    }

    void updateLastTradedPrice(BigDecimal executionPrice) {
        if (executionPrice == null || executionPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Execution price must be positive.");
        }

        this.lastTradedPrice = executionPrice;
    }
}
