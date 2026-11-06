package com.misho.exchange.paper;

import com.misho.exchange.externalmarket.*;
import java.math.*;
import java.time.*;
import java.sql.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Database-only execution ledger: account row locks serialize all reservations and fills. */
@Service
public class PaperTradingService {
    public record Order(long id, String symbol, String side, String type, int originalQuantity, int quantity,
            BigDecimal limitPrice, String status, Instant createdAt, Instant updatedAt,
            BigDecimal executionPrice, Instant quoteAt, Instant executedAt) { }
    public record Page(List<Order> content, int page, int size, long totalElements, long totalPages) { }
    public record Position(String symbol, long quantity, long reservedShares, long availableShares,
            BigDecimal averagePrice, BigDecimal markPrice, Instant markAsOf, BigDecimal marketValue,
            BigDecimal unrealizedPnl, BigDecimal realizedPnl) { }
    public record Portfolio(BigDecimal cash, BigDecimal reservedCash, BigDecimal availableCash,
            BigDecimal equity, BigDecimal realizedPnl, BigDecimal unrealizedPnl, List<Position> positions) { }
    public static class Conflict extends RuntimeException { public Conflict(String message) { super(message); } }
    private final JdbcTemplate jdbc;
    private final ExternalMarketService market;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    @Autowired public PaperTradingService(JdbcTemplate jdbc, ExternalMarketService market, PlatformTransactionManager manager, ObjectMapper json) {
        this(jdbc, market, manager, json, Clock.systemUTC());
    }
    PaperTradingService(JdbcTemplate jdbc, ExternalMarketService market, PlatformTransactionManager manager, ObjectMapper json, Clock clock) {
        this.jdbc=jdbc; this.market=market; this.tx=new TransactionTemplate(manager); this.json=json; this.clock=clock;
    }
    private void lock(long user) {
        jdbc.update("INSERT INTO exchange.external_paper_accounts(user_id) VALUES (?) ON CONFLICT DO NOTHING",user);
        jdbc.queryForObject("SELECT cash FROM exchange.external_paper_accounts WHERE user_id=? FOR UPDATE",BigDecimal.class,user);
    }
    private String symbol(String s) {
        String result=s==null?"":s.trim().toUpperCase(Locale.ROOT);
        if(!ExternalMarketService.INSTRUMENTS.containsKey(result)) throw new IllegalArgumentException("Choose one of the ten supported paper-trading stocks.");
        return result;
    }
    private BigDecimal price(BigDecimal p) {
        if(p==null || p.signum()<=0 || p.precision()-p.scale()>15) throw new IllegalArgumentException("A positive limit price is required.");
        try { return p.setScale(4,RoundingMode.UNNECESSARY); } catch(ArithmeticException e) { throw new IllegalArgumentException("Price supports at most four decimal places."); }
    }
    private void quantity(int q) { if(q<1 || q>1000000) throw new IllegalArgumentException("Quantity must be 1–1,000,000 whole shares."); }
    private static final String SELECT="""
        SELECT o.*, f.price AS execution_price, f.quote_at, f.executed_at
        FROM exchange.external_paper_orders o LEFT JOIN exchange.external_paper_fills f ON f.order_id=o.id
        """;
    private Order row(ResultSet r, int ignored) throws SQLException {
        return new Order(r.getLong("id"),r.getString("symbol"),r.getString("side"),r.getString("order_type"),r.getInt("original_quantity"),r.getInt("quantity"),r.getBigDecimal("limit_price"),r.getString("status"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getBigDecimal("execution_price"),instant(r,"quote_at"),instant(r,"executed_at"));
    }
    private Instant instant(ResultSet r,String name) throws SQLException { var t=r.getTimestamp(name);return t==null?null:t.toInstant(); }
    public Order order(long user,long id) {
        var list=jdbc.query(SELECT+" WHERE o.user_id=? AND o.id=?",this::row,user,id);
        if(list.isEmpty()) throw new NoSuchElementException("Paper order not found.");return list.get(0);
    }
    public Order submit(long user,String symbol,String side,String type,int quantity,BigDecimal limit,String key) {
        String canonical=symbol(symbol); quantity(quantity);
        if(!Set.of("BUY","SELL").contains(side==null?"":side) || !Set.of("MARKET","LIMIT").contains(type==null?"":type)) throw new IllegalArgumentException("Choose BUY/SELL and MARKET/LIMIT.");
        BigDecimal cap="LIMIT".equals(type)?price(limit):null;
        if("MARKET".equals(type) && limit!=null) throw new IllegalArgumentException("Market orders must not include a limit price.");
        if(key==null || !key.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException("An Idempotency-Key of 16–128 letters, digits, underscores or hyphens is required.");
        String fingerprint=canonical+":"+side+":"+type+":"+quantity+":"+cap;
        return tx.execute(status -> {
            lock(user);
            var existing=jdbc.queryForList("SELECT request_fingerprint,submission_response FROM exchange.external_paper_orders WHERE user_id=? AND request_key=?",user,key);
            if(!existing.isEmpty()) {
                if(!fingerprint.equals(existing.get(0).get("request_fingerprint"))) throw new Conflict("This request key already belongs to a different order.");
                return json.readValue((String)existing.get(0).get("submission_response"),Order.class);
            }
            if(jdbc.queryForObject("SELECT count(*) FROM exchange.external_paper_orders WHERE user_id=? AND status='OPEN'",Integer.class,user)>=100) throw new Conflict("Cancel an open order before adding more (100 maximum).");
            MarketQuote quote=null; BigDecimal reserve=cap;
            if("MARKET".equals(type)) { quote=executable(canonical); reserve=executionPrice(quote,side); }
            reserve(user,canonical,side,quantity,reserve,null);
            Long id=jdbc.queryForObject("INSERT INTO exchange.external_paper_orders(user_id,symbol,side,order_type,original_quantity,quantity,limit_price,status,request_key,request_fingerprint) VALUES (?,?,?,?,?,?,?,'OPEN',?,?) RETURNING id",Long.class,user,canonical,side,type,quantity,quantity,cap,key,fingerprint);
            if(quote!=null) fill(user,order(user,id),quote);
            // Limits are eligible only on a subsequent quote, never backfilled from earlier prices.
            Order result=order(user,id);
            jdbc.update("UPDATE exchange.external_paper_orders SET submission_response=? WHERE id=?",json.writeValueAsString(result),id);
            return result;
        });
    }
    private BigDecimal cash(long user) { return jdbc.queryForObject("SELECT cash FROM exchange.external_paper_accounts WHERE user_id=?",BigDecimal.class,user); }
    private BigDecimal reservedCash(long user,Long except) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(limit_price*quantity),0) FROM exchange.external_paper_orders WHERE user_id=? AND status='OPEN' AND side='BUY' AND id<>?",BigDecimal.class,user,except==null?-1:except);
    }
    private long reservedShares(long user,String symbol,Long except) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM exchange.external_paper_orders WHERE user_id=? AND symbol=? AND status='OPEN' AND side='SELL' AND id<>?",Long.class,user,symbol,except==null?-1:except);
    }
    private long shares(long user,String symbol) {
        var list=jdbc.queryForList("SELECT quantity FROM exchange.external_paper_positions WHERE user_id=? AND symbol=?",Long.class,user,symbol);return list.isEmpty()?0:list.get(0);
    }
    private void reserve(long user,String symbol,String side,int quantity,BigDecimal price,Long except) {
        if("BUY".equals(side) && cash(user).subtract(reservedCash(user,except)).compareTo(price.multiply(BigDecimal.valueOf(quantity)))<0) throw new Conflict("Insufficient available paper cash.");
        if("SELL".equals(side) && shares(user,symbol)-reservedShares(user,symbol,except)<quantity) throw new Conflict("Insufficient available paper shares. Buy shares first; short selling is disabled.");
    }
    private boolean fresh(Instant t,long seconds) { return t!=null && !t.isBefore(clock.instant().minusSeconds(seconds)) && !t.isAfter(clock.instant().plusSeconds(2)); }
    private void regularSession() {
        var session=market.session();var s=session.data();Instant now=clock.instant();
        if(session.stale() || !fresh(s.asOf(),90) || !"REGULAR".equals(s.status()) || s.regularOpen()==null || s.regularClose()==null || now.isBefore(s.regularOpen()) || !now.isBefore(s.regularClose())) throw new Conflict("Market orders execute only during the regular US session. Limit orders can wait for the next session.");
    }
    private MarketQuote executable(String symbol) {
        regularSession();
        var response=market.quote(symbol);var q=response.data();
        if(response.stale() || !fresh(q.quoteAsOf(),30) || !symbol.equals(q.symbol()) || q.bid()==null || q.ask()==null || q.bid().signum()<=0 || q.ask().signum()<=0 || q.bid().compareTo(q.ask())>0) throw new Conflict("A fresh, valid IEX bid/ask quote is unavailable. No fill was created; try again when quotes refresh.");
        return q;
    }
    private BigDecimal executionPrice(MarketQuote quote,String side) {
        return ("BUY".equals(side)?quote.ask():quote.bid()).setScale(4,"BUY".equals(side)?RoundingMode.CEILING:RoundingMode.FLOOR);
    }
    private void fill(long user,Order o,MarketQuote q) {
        regularSession();
        if(!fresh(q.quoteAsOf(),30))throw new Conflict("The quote expired before execution; no fill was created.");
        BigDecimal p=executionPrice(q,o.side()); reserve(user,o.symbol(),o.side(),o.quantity(),p,o.id());
        var rows=jdbc.queryForList("SELECT quantity,average_price,realized_pnl FROM exchange.external_paper_positions WHERE user_id=? AND symbol=?",user,o.symbol());
        long held=rows.isEmpty()?0:((Number)rows.get(0).get("quantity")).longValue();
        BigDecimal average=rows.isEmpty()?BigDecimal.ZERO:(BigDecimal)rows.get(0).get("average_price");
        BigDecimal realized=rows.isEmpty()?BigDecimal.ZERO:(BigDecimal)rows.get(0).get("realized_pnl");
        boolean buy="BUY".equals(o.side());long next=held+(buy?o.quantity():-o.quantity());
        BigDecimal value=p.multiply(BigDecimal.valueOf(o.quantity()));
        if(buy) average=average.multiply(BigDecimal.valueOf(held)).add(value).divide(BigDecimal.valueOf(next),12,RoundingMode.HALF_UP);
        else {realized=realized.add(p.subtract(average).multiply(BigDecimal.valueOf(o.quantity())));if(next==0)average=BigDecimal.ZERO;}
        jdbc.update("UPDATE exchange.external_paper_accounts SET cash=cash+? WHERE user_id=?",buy?value.negate():value,user);
        jdbc.update("INSERT INTO exchange.external_paper_positions(user_id,symbol,quantity,average_price,realized_pnl) VALUES (?,?,?,?,?) ON CONFLICT(user_id,symbol) DO UPDATE SET quantity=EXCLUDED.quantity,average_price=EXCLUDED.average_price,realized_pnl=EXCLUDED.realized_pnl",user,o.symbol(),next,average,realized);
        jdbc.update("INSERT INTO exchange.external_paper_fills(order_id,price,quantity,quote_at) VALUES (?,?,?,?)",o.id(),p,o.quantity(),Timestamp.from(q.quoteAsOf()));
        jdbc.update("UPDATE exchange.external_paper_orders SET status='FILLED',updated_at=clock_timestamp() WHERE id=?",o.id());
    }
    public void amend(long user,long id,BigDecimal limit,int quantity) {
        quantity(quantity);BigDecimal p=price(limit);
        tx.executeWithoutResult(s->{lock(user);Order o=order(user,id);if(!"OPEN".equals(o.status()) || !"LIMIT".equals(o.type()))throw new Conflict("Only an open paper limit order can be amended.");reserve(user,o.symbol(),o.side(),quantity,p,id);jdbc.update("UPDATE exchange.external_paper_orders SET limit_price=?,quantity=?,updated_at=clock_timestamp() WHERE id=?",p,quantity,id);});
    }
    public void cancel(long user,long id) {
        tx.executeWithoutResult(s->{lock(user);Order o=order(user,id);if(!"OPEN".equals(o.status()))throw new Conflict("This paper order is already terminal.");jdbc.update("UPDATE exchange.external_paper_orders SET status='CANCELLED',updated_at=clock_timestamp() WHERE id=?",id);});
    }
    public Page orders(long user,int page) {
        if(page<0 || page>1000000)throw new IllegalArgumentException("Invalid page.");
        return tx.execute(s->{lock(user);long total=jdbc.queryForObject("SELECT count(*) FROM exchange.external_paper_orders WHERE user_id=?",Long.class,user);return new Page(jdbc.query(SELECT+" WHERE o.user_id=? ORDER BY o.id DESC LIMIT 25 OFFSET ?",this::row,user,page*25),page,25,total,(total+24)/25);});
    }
    public List<Order> fills(long user,String symbol) { return jdbc.query(SELECT+" WHERE o.user_id=? AND o.symbol=? AND o.status='FILLED' ORDER BY f.executed_at DESC,o.id DESC LIMIT 100",this::row,user,symbol(symbol)); }
    public Portfolio portfolio(long user) {
        // Snapshot the ledger before network reads; provider failures never change balances.
        var ledger=tx.execute(s->{lock(user);return new Ledger(cash(user),reservedCash(user,null),jdbc.queryForList("SELECT * FROM exchange.external_paper_positions WHERE user_id=? ORDER BY symbol",user),jdbc.queryForList("SELECT symbol,SUM(quantity) AS reserved FROM exchange.external_paper_orders WHERE user_id=? AND status='OPEN' AND side='SELL' GROUP BY symbol",user));});
        var positions=new ArrayList<Position>();BigDecimal value=BigDecimal.ZERO,unrealized=BigDecimal.ZERO,realized=BigDecimal.ZERO;boolean missing=false;
        for(var r:ledger.positions()) {
            String symbol=(String)r.get("symbol");long qty=((Number)r.get("quantity")).longValue();BigDecimal avg=(BigDecimal)r.get("average_price"),pnl=(BigDecimal)r.get("realized_pnl");realized=realized.add(pnl);
            long reserved=ledger.reserved().stream().filter(x->symbol.equals(x.get("symbol"))).mapToLong(x->((Number)x.get("reserved")).longValue()).sum();
            BigDecimal mark=null,mv=null,upnl=null;Instant at=null;
            if(qty>0)try {var response=market.quote(symbol);var q=response.data();if(!response.stale() && q.price()!=null && q.price().signum()>0 && q.priceAsOf()!=null){mark=q.price();at=q.priceAsOf();}}catch(RuntimeException unavailable){ /* Preserve ledger; expose unknown valuation. */ }
            if(qty==0){mv=BigDecimal.ZERO;upnl=BigDecimal.ZERO;}
            else if(mark!=null){mv=mark.multiply(BigDecimal.valueOf(qty));upnl=mark.subtract(avg).multiply(BigDecimal.valueOf(qty));value=value.add(mv);unrealized=unrealized.add(upnl);}else missing=true;
            positions.add(new Position(symbol,qty,reserved,qty-reserved,avg,mark,at,mv,upnl,pnl));
        }
        return new Portfolio(ledger.cash(),ledger.cashReserved(),ledger.cash().subtract(ledger.cashReserved()),missing?null:ledger.cash().add(value),realized,missing?null:unrealized,List.copyOf(positions));
    }
    private record Ledger(BigDecimal cash,BigDecimal cashReserved,List<Map<String,Object>> positions,List<Map<String,Object>> reserved) { }
    private long cursor;
    public synchronized void processPending() {
        var ids=jdbc.queryForList("SELECT id,user_id,symbol FROM exchange.external_paper_orders WHERE status='OPEN' AND id>? ORDER BY id LIMIT 200",cursor);
        if(ids.isEmpty()){cursor=0;return;}
        Map<String,MarketQuote> quotes=new HashMap<>();Set<String> unavailable=new HashSet<>();
        for(var item:ids) {
            long id=((Number)item.get("id")).longValue(),user=((Number)item.get("user_id")).longValue();cursor=id;String symbol=(String)item.get("symbol");
            try {
                if(unavailable.contains(symbol))continue;
                MarketQuote q=quotes.get(symbol);if(q==null){q=executable(symbol);quotes.put(symbol,q);}final MarketQuote quote=q;
                tx.executeWithoutResult(s->{lock(user);Order o=order(user,id);if(!"OPEN".equals(o.status()) || !fresh(quote.quoteAsOf(),30) || quote.quoteAsOf().isBefore(o.updatedAt()))return;
                    BigDecimal p=executionPrice(quote,o.side());if("BUY".equals(o.side())?p.compareTo(o.limitPrice())<=0:p.compareTo(o.limitPrice())>=0)fill(user,o,quote);
                });
            } catch(Conflict | ExternalMarketException noQuote){unavailable.add(symbol);}
        }
    }
}
