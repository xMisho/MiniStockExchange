package com.misho.exchange.stock;

import java.math.BigDecimal;

public record CreateStockRequest(String symbol, String companyName, BigDecimal referencePrice) { }
