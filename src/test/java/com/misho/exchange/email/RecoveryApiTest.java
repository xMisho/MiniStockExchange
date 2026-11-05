package com.misho.exchange.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.*;
import com.misho.exchange.user.*;
import com.misho.exchange.support.ApiTestClient;
import com.misho.exchange.externalmarket.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "verdex.email.allowed-recipient=", "verdex.email.enabled=true", "verdex.email.workers-enabled=false", "verdex.email.api-key=fixture-key",
    "verdex.email.from=Verdex <test@example.test>", "verdex.email.public-url=http://localhost:5173",
    "verdex.email.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class RecoveryApiTest {
    static final String PASSWORD="RecoveryFixturePassword123!", NEW="NewRecoveryFixturePassword456!";
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EmailOutbox outbox;
    @Autowired EmailWorker worker;
    @Autowired AccountRecovery recovery;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean EmailTransport transport;
    @MockitoBean ExternalMarketService market;
    @LocalServerPort int port;
    String username,email;long id;
    @BeforeEach void setup(){
        username="mail_"+UUID.randomUUID().toString().replace("-", "").substring(0,14);email=username+"@example.test";
        id=users.registerUser(new RegisterUserRequest(username,email,PASSWORD)).id();
        when(transport.send(anyString(),anyString())).thenReturn("provider-fixture-id");
    }
    @AfterEach void cleanup(){jdbc.update("DELETE FROM exchange.persistent_logins WHERE username=?",username);jdbc.update("DELETE FROM exchange.users WHERE id=?",id);}
    ApiTestClient client(){return new ApiTestClient(port,json);}
    String body(Object value){return json.writeValueAsString(value);}
    String token(String kind){
        String cipher=jdbc.queryForObject("SELECT payload FROM exchange.email_outbox WHERE user_id=? AND kind=? ORDER BY created_at DESC LIMIT 1",String.class,id,kind);
        assertNotNull(cipher);String plain=outbox.decrypt(cipher);
        var matcher=java.util.regex.Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(plain);assertTrue(matcher.find());return matcher.group(1);
    }
    UUID job(){return jdbc.queryForObject("SELECT id FROM exchange.email_outbox WHERE user_id=? ORDER BY created_at DESC LIMIT 1",UUID.class,id);}
    void forgot()throws Exception{assertEquals(202,client().postJson("/api/auth/forgot-password",body(Map.of("email",email))).statusCode());}
    @Test void genericResponsesCooldownAndEncryptedStorage()throws Exception{
        var browser=client();var known=browser.postJson("/api/auth/forgot-password",body(Map.of("email"," "+email.toUpperCase()+" ")));
        var unknown=browser.postJson("/api/auth/forgot-password",body(Map.of("email","missing_"+email)));
        assertEquals(202,known.statusCode());assertEquals(known.body(),unknown.body());
        forgot();assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM exchange.email_tokens WHERE user_id=?",Integer.class,id));
        String token=token("RESET");String stored=jdbc.queryForObject("SELECT token_hash FROM exchange.email_tokens WHERE user_id=?",String.class,id);
        assertEquals(64,stored.length());assertFalse(stored.contains(token));assertFalse(known.body().contains(token));
        assertFalse(jdbc.queryForObject("SELECT payload FROM exchange.email_outbox WHERE user_id=?",String.class,id).contains(token));
        verifyNoInteractions(transport);
    }
    @Test void resetIsSingleUseRevokesSessionRememberedLoginAndChangesPassword()throws Exception{
        var browser=client();assertEquals(200,browser.login(username,PASSWORD,true).statusCode());forgot();String token=token("RESET");
        assertEquals(204,client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password",NEW))).statusCode());
        assertEquals(401,browser.get("/api/auth/me").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM exchange.persistent_logins WHERE username=?",Integer.class,username));
        browser.forgetSessionCookie();assertEquals(401,browser.get("/api/auth/me").statusCode());
        assertEquals(401,client().login(username,PASSWORD).statusCode());assertEquals(200,client().login(username,NEW).statusCode());
        assertEquals(400,client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password",PASSWORD))).statusCode());
    }
    @Test void expiredWrongPurposeAndInvalidPasswordCannotConsumeAValidReset()throws Exception{
        forgot();String token=token("RESET");
        assertEquals(400,client().postJson("/api/auth/verify-email",body(Map.of("token",token))).statusCode());
        assertEquals(400,client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password","short"))).statusCode());
        assertNull(jdbc.queryForObject("SELECT used_at FROM exchange.email_tokens WHERE user_id=?",java.sql.Timestamp.class,id));
        jdbc.update("UPDATE exchange.email_tokens SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE user_id=?",id);
        assertEquals(400,client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password",NEW))).statusCode());
        assertEquals(200,client().login(username,PASSWORD).statusCode());
    }
    @Test void competingResetsCanOnlyConsumeOnce()throws Exception{
        forgot();String token=token("RESET");var pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password",NEW))).statusCode());
            var b=pool.submit(()->client().postJson("/api/auth/reset-password",body(Map.of("token",token,"password",PASSWORD))).statusCode());
            var results=new ArrayList<>(List.of(a.get(),b.get()));Collections.sort(results);assertEquals(List.of(204,400),results);
        }finally{pool.shutdownNow();}
    }
    @Test void rollbackDoesNotLeaveATokenOrEmailToSend(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{recovery.forgot(email);throw new IllegalStateException("fixture rollback");}));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM exchange.email_tokens WHERE user_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM exchange.email_outbox WHERE user_id=?",Integer.class,id));verifyNoInteractions(transport);
    }
    @Test void csrfAndPreferenceOwnershipAreEnforced()throws Exception{
        var browser=client();assertEquals(403,browser.postWithToken("/api/auth/forgot-password",body(Map.of("email",email)),"application/json",null).statusCode());
        assertEquals(401,browser.get("/api/auth/email-preferences").statusCode());assertEquals(200,browser.login(username,PASSWORD).statusCode());
        assertEquals(400,browser.postJson("/api/auth/email-preferences","{\"alertEmails\":true}").statusCode());
        assertEquals(202,browser.postJson("/api/auth/email-verification","{}").statusCode());String token=token("VERIFY");
        assertEquals(204,client().postJson("/api/auth/verify-email",body(Map.of("token",token))).statusCode());
        assertEquals(204,browser.postJson("/api/auth/email-preferences","{\"alertEmails\":true}").statusCode());
        assertTrue(json.readTree(browser.get("/api/auth/email-preferences").body()).get("alertEmails").asBoolean());
        assertEquals(400,client().postJson("/api/auth/verify-email",body(Map.of("token",token))).statusCode());
    }
    @Test void retriesReuseProviderKeyAndClearSensitivePayloadAfterAcceptance()throws Exception{
        forgot();UUID job=job();when(transport.send(anyString(),anyString())).thenThrow(new IllegalStateException("offline")).thenReturn("accepted-id");
        assertTrue(worker.deliverOne(job));assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM exchange.email_outbox WHERE id=?",String.class,job));
        jdbc.update("UPDATE exchange.email_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",job);assertTrue(worker.deliverOne(job));
        verify(transport,times(2)).send(eq("verdex-email/"+job),anyString());
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM exchange.email_outbox WHERE id=?",String.class,job));
        assertNull(jdbc.queryForObject("SELECT payload FROM exchange.email_outbox WHERE id=?",String.class,job));assertFalse(worker.deliverOne(job));
    }
    @Test void permanentProviderRejectionsStopAndTransientLimitsHonorRetryAfter()throws Exception{
        forgot();UUID job=job();when(transport.send(anyString(),anyString())).thenThrow(new EmailDeliveryException("EMAIL_PROVIDER_RATE_LIMITED",true,600));
        worker.deliverOne(job);assertTrue(jdbc.queryForObject("SELECT next_attempt_at>=CURRENT_TIMESTAMP+INTERVAL '590 seconds' FROM exchange.email_outbox WHERE id=?",Boolean.class,job));
        jdbc.update("UPDATE exchange.email_outbox SET next_attempt_at=CURRENT_TIMESTAMP WHERE id=?",job);
        doThrow(new EmailDeliveryException("EMAIL_PROVIDER_REJECTED",false,0)).when(transport).send(anyString(),anyString());worker.deliverOne(job);
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM exchange.email_outbox WHERE id=?",String.class,job));
        assertNull(jdbc.queryForObject("SELECT payload FROM exchange.email_outbox WHERE id=?",String.class,job));
    }
    @Test void expiredDeliveryDoesNotSendAndActiveLeasePreventsConcurrentSend()throws Exception{
        forgot();UUID job=job();jdbc.update("UPDATE exchange.email_outbox SET status='SENDING',lease_until=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE id=?",job);
        assertFalse(worker.deliverOne(job));verifyNoInteractions(transport);
        jdbc.update("UPDATE exchange.email_outbox SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",job);
        assertFalse(worker.deliverOne(job));assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM exchange.email_outbox WHERE id=?",String.class,job));verifyNoInteractions(transport);
    }
    MarketEnvelope<MarketQuote> quote(String price,String time){return new MarketEnvelope<>("Alpaca","IEX","USD","Single exchange",Instant.parse(time),false,null,
        new MarketQuote("AAPL",new BigDecimal(price),Instant.parse(time),null,null,null,null,null,null,null,null,null,null,null,null,null));}
    @Test void alertCrossingQueuesExactlyOnceOnlyForVerifiedOptedInUser()throws Exception{
        var browser=client();assertEquals(200,browser.login(username,PASSWORD).statusCode());
        jdbc.update("UPDATE exchange.users SET email_verified=TRUE,alert_emails=TRUE WHERE id=?",id);
        when(market.instrument("AAPL")).thenReturn(new AlpacaMarketClient.Instrument("AAPL","Apple Inc.","USD"));
        when(market.quote("AAPL")).thenReturn(quote("90","2026-11-01T12:00:00Z"));
        assertEquals(204,browser.postJson("/api/alerts","{\"symbol\":\"AAPL\",\"direction\":\"ABOVE\",\"target\":100}").statusCode());
        when(market.quote("AAPL")).thenReturn(quote("110","2026-11-01T12:01:00Z"));assertEquals(200,browser.get("/api/alerts").statusCode());assertEquals(200,browser.get("/api/alerts").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM exchange.email_outbox WHERE user_id=? AND kind='ALERT'",Integer.class,id));
        assertEquals(204,browser.postJson("/api/auth/email-preferences","{\"alertEmails\":false}").statusCode());
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT status FROM exchange.email_outbox WHERE user_id=?",String.class,id));
        assertFalse(worker.deliverOne(job()));verifyNoInteractions(transport);
    }
}
