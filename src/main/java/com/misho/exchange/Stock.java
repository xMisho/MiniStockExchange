package com.misho.exchange;

import java.math.BigDecimal;

public class Stock {

    private final String symbol;
    private final String companyName;
    private final BigDecimal referencePrice;

    public Stock(
        String symbol,
        String companyName,
        BigDecimal referencePrice) {

    if (symbol == null || symbol.isBlank()) {
        throw new IllegalArgumentException("Stock symbol must not be blank.");
    }

    if (companyName == null || companyName.isBlank()) {
        throw new IllegalArgumentException("Company name must not be blank.");
    }

    if (referencePrice == null ||
            referencePrice.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Reference price must be positive.");
    }

   this.symbol = symbol.trim().toUpperCase();
    this.companyName = companyName;
    this.referencePrice = referencePrice;
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




}
