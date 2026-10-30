package com.misho.exchange.trading;

import java.math.BigDecimal;

public record PriceLevelResponse(BigDecimal price, long quantity) { }
