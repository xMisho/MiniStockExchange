package com.misho.exchange.trading;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MarketWriter {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();

    public MarketWriter(JdbcTemplate jdbc, @Value("${verdex.deployment-fencing:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    void claim() {
        if (!enabled) { return; }
        requireTransaction();
        jdbc.update("""
                INSERT INTO exchange.market_writer (singleton, instance_id) VALUES (TRUE, ?)
                ON CONFLICT (singleton) DO UPDATE SET instance_id = EXCLUDED.instance_id,
                claimed_at = CURRENT_TIMESTAMP
                """, instanceId);
    }

    void requireOwner() {
        if (!enabled) { return; }
        requireTransaction();
        if (!isOwner()) {
            throw new IllegalStateException("This market instance has been superseded by a deployment.");
        }
    }

    public boolean isOwner() {
        if (!enabled) { return true; }
        try {
            var rows = jdbc.queryForList("SELECT instance_id FROM exchange.market_writer WHERE singleton = TRUE", String.class);
            return rows.size() == 1 && instanceId.equals(rows.get(0));
        } catch (DataAccessException unavailable) { return false; }
    }

    boolean enabled() { return enabled; }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Market ownership requires the trading transaction boundary.");
        }
    }
}
