package com.misho.exchange.marketdata;

import java.math.BigDecimal;

public record PriceLevelResponse(BigDecimal price, long quantity) { }
