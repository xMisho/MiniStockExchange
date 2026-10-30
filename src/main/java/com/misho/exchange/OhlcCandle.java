package com.misho.exchange;

import java.math.BigDecimal;

public final class OhlcCandle {

    private final long minuteStartTime;
    private final BigDecimal open;
    private final BigDecimal high;
    private final BigDecimal low;
    private final BigDecimal close;

    OhlcCandle(long minuteStartTime, BigDecimal open, BigDecimal high,
               BigDecimal low, BigDecimal close) {
        this.minuteStartTime = minuteStartTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
    }

    public long getMinuteStartTime() {
        return minuteStartTime;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public BigDecimal getClose() {
        return close;
    }
}
