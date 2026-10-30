package com.misho.exchange.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

public record CandleResponse(Instant startTime, BigDecimal open, BigDecimal high,
                             BigDecimal low, BigDecimal close, long volume, long tradeCount) { }
