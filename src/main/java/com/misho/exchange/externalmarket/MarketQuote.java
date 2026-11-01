package com.misho.exchange.externalmarket;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketQuote(String symbol, BigDecimal price, Instant priceAsOf,
        BigDecimal bid, BigDecimal ask, Instant quoteAsOf, BigDecimal previousClose,
        BigDecimal change, BigDecimal changePercent, BigDecimal sessionOpen,
        BigDecimal sessionHigh, BigDecimal sessionLow, BigDecimal sessionVolume,
        Long sessionTradeCount, BigDecimal sessionVwap, LocalDate sessionDate) { }
