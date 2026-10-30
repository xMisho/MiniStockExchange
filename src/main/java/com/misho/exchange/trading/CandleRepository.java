package com.misho.exchange.trading;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CandleRepository {
    private final JdbcTemplate jdbc;

    CandleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CandleResponse> find(String symbol, Instant from, Instant to, int limit) {
        StringBuilder filter = new StringBuilder("stock_symbol = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(symbol);
        if (from != null) {
            filter.append(" AND executed_at >= ?");
            parameters.add(Timestamp.from(ceilMicros(from)));
        }
        if (to != null) {
            filter.append(" AND executed_at < ?");
            parameters.add(Timestamp.from(ceilMicros(to)));
        }
        parameters.add(limit);
        String sql = """
                SELECT * FROM (
                    SELECT date_trunc('minute', executed_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS start_time,
                        (array_agg(execution_price ORDER BY executed_at ASC, id ASC))[1] AS open,
                        max(execution_price) AS high, min(execution_price) AS low,
                        (array_agg(execution_price ORDER BY executed_at DESC, id DESC))[1] AS close,
                        sum(quantity) AS volume, count(*) AS trade_count
                    FROM exchange.trades WHERE %s
                    GROUP BY start_time ORDER BY start_time DESC LIMIT ?
                ) candles ORDER BY start_time ASC
                """.formatted(filter);
        return List.copyOf(jdbc.query(sql, (row, index) -> new CandleResponse(
                row.getTimestamp("start_time").toInstant(), row.getBigDecimal("open"),
                row.getBigDecimal("high"), row.getBigDecimal("low"), row.getBigDecimal("close"),
                row.getLong("volume"), row.getLong("trade_count")), parameters.toArray()));
    }

    private Instant ceilMicros(Instant value) {
        int remainder = value.getNano() % 1000;
        return remainder == 0 ? value : value.plusNanos(1000 - remainder);
    }
}
