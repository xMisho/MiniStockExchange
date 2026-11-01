package com.misho.exchange.externalmarket;

import java.time.Instant;

public record MarketEnvelope<T>(String provider, String feed, String currency, String coverage,
        Instant fetchedAt, boolean stale, String warning, T data) { }
