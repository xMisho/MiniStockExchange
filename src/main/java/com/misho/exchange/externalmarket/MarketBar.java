package com.misho.exchange.externalmarket;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketBar(Instant startTime, BigDecimal open, BigDecimal high,
        BigDecimal low, BigDecimal close, BigDecimal volume, Long tradeCount) { }
