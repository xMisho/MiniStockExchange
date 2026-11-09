package com.misho.exchange.paper;

import com.misho.exchange.externalmarket.*;
import com.misho.exchange.user.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PaperTradingTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired UserService users;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @MockitoBean ExternalMarketService market;
    PaperTradingService service;
    long user,other;
    Instant now;
    class MutableClock extends Clock {
        public java.time.ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(java.time.ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @BeforeEach void setup(){
        now=Instant.now();service=new PaperTradingService(jdbc,market,manager,json,new MutableClock());
        user=create();other=create();quotes("99","100",now);
    }
    long create(){String name="paper_"+UUID.randomUUID().toString().replace("-","").substring(0,12);return users.registerUser(new RegisterUserRequest(name,name+"@example.test","PaperTestPassword123!")).id();}
    MarketEnvelope<MarketQuote> quote(String bid,String ask,Instant time){
        return new MarketEnvelope<>("Alpaca","IEX","USD","test",now,false,null,new MarketQuote("AAPL",new BigDecimal("100"),time,bid==null?null:new BigDecimal(bid),ask==null?null:new BigDecimal(ask),time,null,null,null,null,null,null,null,null,null,null));
    }
    void quotes(String bid,String ask,Instant time){
        when(market.quote("AAPL")).thenReturn(quote(bid,ask,time));
        when(market.session()).thenReturn(new MarketEnvelope<>("Alpaca","IEX","USD","test",now,false,null,new AlpacaMarketClient.Session("REGULAR",now,now.plusSeconds(86400),now.plusSeconds(3600),now.minusSeconds(3600),now.plusSeconds(3600),"America/New_York")));
    }
    String key(){return UUID.randomUUID().toString();}
    PaperTradingService.Order buy(int qty){return service.submit(user,"AAPL","BUY","MARKET",qty,null,key());}
    PaperTradingService.Order limit(String side,int qty,String price,String key){return service.submit(user,"AAPL",side,"LIMIT",qty,new BigDecimal(price),key);}
    void eq(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    @AfterEach void cleanup(){reset(jdbc);for(long id:List.of(user,other)){
        jdbc.update("DELETE FROM exchange.external_paper_fills WHERE order_id IN (SELECT id FROM exchange.external_paper_orders WHERE user_id=?)",id);
        jdbc.update("DELETE FROM exchange.external_paper_orders WHERE user_id=?",id);
        jdbc.update("DELETE FROM exchange.external_paper_positions WHERE user_id=?",id);
        jdbc.update("DELETE FROM exchange.external_paper_accounts WHERE user_id=?",id);
        jdbc.update("DELETE FROM exchange.users WHERE id=?",id);
    }}
    @Test void statusFilterFindsOpenOrdersBeyondFirstPageAndKeepsOwnersSeparate(){
        var open=limit("BUY",1,"1",key());
        for(int i=0;i<26;i++){var o=limit("BUY",1,"1",key());service.cancel(user,o.id());}
        assertEquals(25,service.orders(user,0).content().size());
        var filtered=service.orders(user,0,"OPEN");
        assertEquals(1,filtered.totalElements());assertEquals(open.id(),filtered.content().get(0).id());
        assertEquals(26,service.orders(user,0,"CANCELLED").totalElements());
        assertEquals(0,service.orders(other,0,"OPEN").totalElements());
        assertThrows(IllegalArgumentException.class,()->service.orders(user,0,"BOGUS"));
        service.cancel(user,open.id());assertEquals(0,service.orders(user,0,"OPEN").totalElements());
    }
    @Test void fillsUseAskAndBidAndExternalMarksWithoutTouchingSandbox(){
        var o=buy(10);eq("100",o.executionPrice());assertEquals("FILLED",o.status());
        eq("99000",service.portfolio(user).cash());assertEquals(10,service.portfolio(user).positions().get(0).quantity());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM exchange.orders WHERE user_id=?",Integer.class,user));
        quotes("110","111",now);var sold=service.submit(user,"AAPL","SELL","MARKET",4,null,key());eq("110",sold.executionPrice());
        eq("99440",service.portfolio(user).cash());eq("40",service.portfolio(user).realizedPnl());
        assertEquals(6,service.portfolio(user).positions().get(0).quantity());
    }
    @Test void oldMissingCrossedFutureQuotesAndClosedSessionsNeverFill(){
        for(var q:List.of(quote("99","100",now.minusSeconds(31)),quote(null,"100",now),quote("101","100",now),quote("99","100",now.plusSeconds(3)))){
            when(market.quote("AAPL")).thenReturn(q);assertThrows(PaperTradingService.Conflict.class,()->buy(1));
        }
        quotes("99","100",now);
        when(market.session()).thenReturn(new MarketEnvelope<>("Alpaca","IEX","USD","test",now,false,null,new AlpacaMarketClient.Session("CLOSED",now,now.plusSeconds(100),now.plusSeconds(500),null,null,"America/New_York")));
        assertThrows(PaperTradingService.Conflict.class,()->buy(1));
        assertEquals(0,service.orders(user,0).totalElements());
    }
    @Test void limitsReserveWaitForNewQuotesAndCancelReleases(){
        var o=limit("BUY",10,"90",key());eq("900",service.portfolio(user).reservedCash());
        service.processPending();assertEquals("OPEN",service.order(user,o.id()).status());
        now=now.plusSeconds(3);quotes("89","90",now);service.processPending(); // cursor resets before next pass
        service.processPending();assertEquals("FILLED",service.order(user,o.id()).status());
        eq("99100",service.portfolio(user).cash());eq("0",service.portfolio(user).reservedCash());
        var sell=limit("SELL",10,"110",key());assertEquals(0,service.portfolio(user).positions().get(0).availableShares());
        assertThrows(PaperTradingService.Conflict.class,()->service.submit(user,"AAPL","SELL","MARKET",1,null,key()));
        service.cancel(user,sell.id());assertEquals(10,service.portfolio(user).positions().get(0).availableShares());
    }
    @Test void duplicateKeysReplaySubmissionSnapshotAfterAmendCancelAndProviderFailure(){
        String key=key();var initial=limit("BUY",2,"90",key);
        service.amend(user,initial.id(),new BigDecimal("80"),3);service.cancel(user,initial.id());
        when(market.quote(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        assertEquals(initial,limit("BUY",2,"90.0000",key));assertEquals(1,service.orders(user,0).totalElements());
        assertThrows(PaperTradingService.Conflict.class,()->limit("BUY",3,"90",key));
    }
    @Test void concurrentRetriesAndWorkersCannotDuplicateFills() throws Exception {
        var pool=Executors.newFixedThreadPool(4);String key=key();
        try{var tasks=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++)tasks.add(()->service.submit(user,"AAPL","BUY","MARKET",1,null,key).id());
            var ids=new HashSet<Long>();for(var result:pool.invokeAll(tasks))ids.add(result.get());assertEquals(1,ids.size());
            eq("99900",service.portfolio(user).cash());
            var limit=limit("BUY",2,"101",key());now=now.plusSeconds(3);quotes("99","100",now);
            var second=new PaperTradingService(jdbc,market,manager,json,new MutableClock());
            pool.invokeAll(List.of(Executors.callable(service::processPending),Executors.callable(second::processPending)));
            assertEquals("FILLED",service.order(user,limit.id()).status());eq("99700",service.portfolio(user).cash());
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM exchange.external_paper_fills f JOIN exchange.external_paper_orders o ON o.id=f.order_id WHERE o.user_id=?",Integer.class,user));
        }finally{pool.shutdownNow();}
    }
    @Test void ownershipCashAndAmendmentsAreEnforced(){
        assertThrows(PaperTradingService.Conflict.class,()->buy(1001));
        assertThrows(PaperTradingService.Conflict.class,()->service.submit(user,"AAPL","SELL","MARKET",1,null,key()));
        var o=limit("BUY",900,"100",key());assertThrows(PaperTradingService.Conflict.class,()->service.amend(user,o.id(),new BigDecimal("200"),900));
        eq("90000",service.portfolio(user).reservedCash());
        assertThrows(NoSuchElementException.class,()->service.cancel(other,o.id()));
        service.amend(user,o.id(),new BigDecimal("100"),2);eq("200",service.portfolio(user).reservedCash());
        var restarted=new PaperTradingService(jdbc,market,manager,json,new MutableClock());assertEquals("OPEN",restarted.order(user,o.id()).status());
        restarted.cancel(user,o.id());eq("0",restarted.portfolio(user).reservedCash());
    }
    @Test void unavailableMarksPreserveCashAndShowUnknownEquity(){
        buy(1);when(market.quote("AAPL")).thenThrow(new IllegalStateException("provider unavailable"));
        var p=service.portfolio(user);eq("99900",p.cash());assertNull(p.equity());assertNull(p.positions().get(0).markPrice());
    }
    @Test void doesNotUseQuotesFromBeforeLimitSubmission(){
        var o=limit("BUY",1,"101",key());service.processPending();assertEquals("OPEN",service.order(user,o.id()).status());
        now=now.plusSeconds(3);quotes("99","100",now);service.processPending();service.processPending();assertEquals("FILLED",service.order(user,o.id()).status());
    }
    @Test void persistenceFailureRollsBackTheEntireLedgerAndSameKeyCanBeRetried(){
        String key=key();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test write failure"))
            .when(jdbc).update(startsWith("INSERT INTO exchange.external_paper_fills"),any(Object[].class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->service.submit(user,"AAPL","BUY","MARKET",1,null,key));
        reset(jdbc);
        eq("100000",service.portfolio(user).cash());assertTrue(service.portfolio(user).positions().isEmpty());assertEquals(0,service.orders(user,0).totalElements());
        assertEquals("FILLED",service.submit(user,"AAPL","BUY","MARKET",1,null,key).status());eq("99900",service.portfolio(user).cash());
    }
    @Test void httpContractRequiresLoginCsrfAndIdempotencyAndProtectsOwnership() throws Exception {
        var client=new com.misho.exchange.support.ApiTestClient(port,json);
        assertEquals(10,json.readTree(client.get("/api/paper/instruments").body()).size());
        assertEquals(401,client.get("/api/paper/orders").statusCode());
        String name=jdbc.queryForObject("SELECT username FROM exchange.users WHERE id=?",String.class,user);
        assertEquals(200,client.login(name,"PaperTestPassword123!").statusCode());
        String body="{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"type\":\"MARKET\",\"quantity\":1}";
        String key=key();
        var http=java.net.http.HttpClient.newHttpClient();
        var noCsrf=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/paper/orders"))
            .header("Cookie","JSESSIONID="+client.sessionId()).header("Idempotency-Key",key).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403,http.send(noCsrf,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var request=java.net.http.HttpRequest.newBuilder(noCsrf, (h,v)->true).header("X-CSRF-TOKEN",client.csrfToken()).build();
        var first=http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(201,first.statusCode(),first.body());
        assertEquals(first.body(),http.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        long id=json.readTree(first.body()).path("id").asLong();
        var stranger=new com.misho.exchange.support.ApiTestClient(port,json);
        String otherName=jdbc.queryForObject("SELECT username FROM exchange.users WHERE id=?",String.class,other);
        assertEquals(200,stranger.login(otherName,"PaperTestPassword123!").statusCode());
        assertEquals(404,stranger.get("/api/paper/orders/"+id).statusCode());
        assertEquals(404,stranger.changeJson("DELETE","/api/paper/orders/"+id,"",true).statusCode());
        assertEquals(400,client.get("/api/paper/orders?page=-1").statusCode());
        assertEquals(0,json.readTree(client.get("/api/paper/orders?status=OPEN").body()).path("totalElements").asInt());
        assertEquals(1,json.readTree(client.get("/api/paper/orders?status=FILLED").body()).path("totalElements").asInt());
        assertEquals(400,client.get("/api/paper/orders?status=INVALID").statusCode());
    }

}
