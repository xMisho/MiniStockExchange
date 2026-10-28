package com.misho.exchange.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Entity
@Table(name = "stocks", schema = "exchange")
public class StockEntity {

    @Id
    @Column(name = "symbol", length = 20, nullable = false, updatable = false)
    private String symbol;

    @Column(name = "company_name", length = 255, nullable = false)
    private String companyName;

    @Column(name = "reference_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal referencePrice;

    protected StockEntity() { }

    public StockEntity(String symbol, String companyName, BigDecimal referencePrice) {
        this.symbol = normalizeSymbol(symbol);
        if (companyName == null || companyName.isBlank() || companyName.strip().length() > 255) {
            throw new IllegalArgumentException("Company name must contain 1 to 255 characters.");
        }
        if (referencePrice == null || referencePrice.signum() <= 0) {
            throw new IllegalArgumentException("Reference price must be positive.");
        }
        BigDecimal price;
        try {
            price = referencePrice.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Reference price must have at most four decimal places.");
        }
        if (price.precision() > 19) {
            throw new IllegalArgumentException("Reference price exceeds NUMERIC(19,4).");
        }
        this.companyName = companyName.strip();
        this.referencePrice = price;
    }

    static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must contain 1 to 20 characters.");
        }
        String normalized = symbol.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 20) {
            throw new IllegalArgumentException("Stock symbol must contain 1 to 20 characters.");
        }
        return normalized;
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
