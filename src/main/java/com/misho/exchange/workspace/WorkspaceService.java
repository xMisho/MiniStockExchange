package com.misho.exchange.workspace;

import com.misho.exchange.externalmarket.AlpacaMarketClient;
import com.misho.exchange.externalmarket.ExternalMarketService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class WorkspaceService {
    public record Alert(long id, String symbol, String direction, BigDecimal target, Instant triggeredAt,
                        BigDecimal triggeredPrice, Instant lastObservedAt) { }
    public record AlertList(List<Alert> alerts, String warning, List<Long> newlyTriggeredIds) { }
    private final JdbcTemplate jdbc;
    private final ExternalMarketService market;
    private final TransactionTemplate tx;
    public WorkspaceService(JdbcTemplate jdbc, ExternalMarketService market, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.market = market; this.tx = new TransactionTemplate(manager);
    }
    private void lock(long user) { jdbc.queryForObject("SELECT id FROM exchange.users WHERE id = ? FOR UPDATE", Long.class, user); }
    public List<AlpacaMarketClient.Instrument> watchlist(long user) {
        return jdbc.query("SELECT symbol, company_name FROM exchange.watchlist WHERE user_id = ? ORDER BY created_at, symbol",
                (row, index) -> new AlpacaMarketClient.Instrument(row.getString(1), row.getString(2), "USD"), user);
    }
    public void saveWatch(long user, String symbol) {
        var item = market.instrument(symbol);
        tx.executeWithoutResult(status -> {
            lock(user);
            if (jdbc.queryForObject("SELECT count(*) FROM exchange.watchlist WHERE user_id = ? AND symbol = ?", Integer.class, user, item.symbol()) > 0) return;
            if (jdbc.queryForObject("SELECT count(*) FROM exchange.watchlist WHERE user_id = ?", Integer.class, user) >= 20) throw new IllegalArgumentException("Watchlists support up to 20 stocks.");
            jdbc.update("INSERT INTO exchange.watchlist(user_id, symbol, company_name) VALUES (?, ?, ?)", user, item.symbol(), item.companyName().substring(0, Math.min(255, item.companyName().length())));
        });
    }
    public void removeWatch(long user, String symbol) { jdbc.update("DELETE FROM exchange.watchlist WHERE user_id = ? AND symbol = ?", user, symbol.toUpperCase(java.util.Locale.ROOT)); }
    public void createAlert(long user, String symbol, String direction, BigDecimal target) {
        var item = market.instrument(symbol);
        if (direction == null || !List.of("ABOVE", "BELOW").contains(direction) || target == null || target.signum() <= 0 || target.precision() - target.scale() > 15 || target.stripTrailingZeros().scale() > 4) throw new IllegalArgumentException("Use ABOVE/BELOW and a positive price with at most four decimal places.");
        var initial = market.quote(item.symbol());
        if (initial.stale() || initial.data().price() == null || initial.data().priceAsOf() == null) throw new IllegalArgumentException("A fresh provider response is needed to arm an alert.");
        tx.executeWithoutResult(status -> {
            lock(user);
            if (jdbc.queryForObject("SELECT count(*) FROM exchange.price_alerts WHERE user_id = ?", Integer.class, user) >= 20) throw new IllegalArgumentException("Keep at most 20 alerts; delete old alerts to create more.");
            jdbc.update("INSERT INTO exchange.price_alerts(user_id,symbol,direction,target,previous_price,last_observed_at) VALUES (?,?,?,?,?,?)",
                    user, item.symbol(), direction, target, initial.data().price(), Timestamp.from(initial.data().priceAsOf()));
        });
    }
    public void removeAlert(long user, long id) {
        if (jdbc.update("DELETE FROM exchange.price_alerts WHERE user_id = ? AND id = ?", user, id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found.");
    }
    private List<Alert> alerts(long user) {
        return jdbc.query("SELECT id,symbol,direction,target,triggered_at,triggered_price,last_observed_at FROM exchange.price_alerts WHERE user_id = ? ORDER BY id DESC",
                (row, index) -> new Alert(row.getLong(1), row.getString(2), row.getString(3), row.getBigDecimal(4), instant(row.getTimestamp(5)), row.getBigDecimal(6), instant(row.getTimestamp(7))), user);
    }
    public AlertList checkAlerts(long user) {
        String warning = null;
        var newlyTriggered = new java.util.ArrayList<Long>();
        var active = alerts(user).stream().filter(item -> item.triggeredAt() == null).toList();
        for (String symbol : active.stream().map(Alert::symbol).distinct().toList()) {
            try {
                var response = market.quote(symbol);
                var quote = response.data();
                if (response.stale() || quote.price() == null || quote.priceAsOf() == null) { warning = "Some quotes are stale or unavailable; affected alerts were not evaluated."; continue; }
                var hits = tx.execute(status -> {
                    var committedHits = new java.util.ArrayList<Long>();
                    lock(user);
                    jdbc.query("SELECT id,direction,target,previous_price,last_observed_at FROM exchange.price_alerts WHERE user_id = ? AND symbol = ? AND triggered_at IS NULL FOR UPDATE", row -> {
                        Instant observed = instant(row.getTimestamp(5));
                        if (observed != null && !quote.priceAsOf().isAfter(observed)) return;
                        BigDecimal previous = row.getBigDecimal(4), target = row.getBigDecimal(3), price = quote.price();
                        boolean crossed = previous != null && (row.getString(2).equals("ABOVE") ? previous.compareTo(target) < 0 && price.compareTo(target) >= 0 : previous.compareTo(target) > 0 && price.compareTo(target) <= 0);
                        if (crossed) committedHits.add(row.getLong(1));
                        jdbc.update("UPDATE exchange.price_alerts SET previous_price=?, last_observed_at=?, triggered_at=?, triggered_price=? WHERE id=? AND user_id=?",
                                price, Timestamp.from(quote.priceAsOf()), crossed ? Timestamp.from(quote.priceAsOf()) : null, crossed ? price : null, row.getLong(1), user);
                    }, user, symbol);
                    return List.copyOf(committedHits);
                });
                newlyTriggered.addAll(java.util.Objects.requireNonNull(hits));
            } catch (RuntimeException exception) { warning = "Some alerts could not refresh. No missing price crossings are inferred."; }
        }
        return new AlertList(alerts(user), warning, List.copyOf(newlyTriggered));
    }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
