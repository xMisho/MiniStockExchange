package com.misho.exchange.trading;

import java.math.BigDecimal;

public record AmendOrderRequest(BigDecimal newPrice, Integer newRemainingQuantity) { }
