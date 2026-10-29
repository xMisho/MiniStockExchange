package com.misho.exchange.trading;

import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import java.math.BigDecimal;

public record SubmitOrderRequest(OrderSide side, OrderType type, BigDecimal limitPrice, Integer quantity) { }
