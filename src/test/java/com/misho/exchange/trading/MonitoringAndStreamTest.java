package com.misho.exchange.trading;

import static org.junit.jupiter.api.Assertions.*;
import com.misho.exchange.OrderSide;
import com.misho.exchange.OrderType;
import com.misho.exchange.stock.*;
import com.misho.exchange.user.*;
import com.misho.exchange.support.ApiTestClient;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitoringAndStreamTest {
    @Autowired TradingService trading;
    @Autowired UserService users;
    @Autowired StockRepository stocks;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry metrics;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @Test void metricsAreAdminOnlyAndStreamsSendCommittedPublicHints() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,12);
        String symbol="SSE"+suffix.toUpperCase(), username="metric_"+suffix, password="MonitoringPassword123!";
        stocks.saveAndFlush(new StockEntity(symbol,"SSE fixture",BigDecimal.valueOf(100)));
        long user=users.registerUser(new RegisterUserRequest(username,username+"@example.test",password)).id();
        var client=new ApiTestClient(port,json);
        ExecutorService reader=Executors.newSingleThreadExecutor();
        try {
            assertEquals(200,client.get("/api/live").statusCode());
            assertEquals(200,client.get("/api/ready").statusCode());
            assertEquals(401,client.get("/actuator/metrics").statusCode());
            assertEquals(401,client.login(username,"wrong").statusCode());
            assertTrue(metrics.find("verdex.login.failures").counter().count() > 0);
            assertEquals(200,client.login(username,password).statusCode());
            assertEquals(403,client.get("/actuator/prometheus").statusCode());
            client.logout();jdbc.update("UPDATE exchange.users SET role='ADMIN' WHERE id=?",user);
            assertEquals(200,client.login(username,password).statusCode());
            assertEquals(200,client.get("/actuator/metrics").statusCode());
            assertEquals(200,client.get("/actuator/prometheus").statusCode());
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/events"))
                .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());
            try(var lines=new BufferedReader(new InputStreamReader(response.body()))) {
                assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
                var received=reader.submit(()->{ String line; boolean connected=false;
                    while((line=lines.readLine())!=null) { if(line.contains("event:connected")) connected=true;
                        if(line.contains(symbol)) return connected && !line.contains("userId") && !line.contains("orderId"); }
                    return false;
                });
                trading.submitOrder(user,symbol,OrderSide.BUY,OrderType.LIMIT,BigDecimal.TEN,1,UUID.randomUUID().toString());
                assertTrue(received.get(10,TimeUnit.SECONDS));
            }
            assertNotNull(metrics.find("http.server.requests").timer());
            assertTrue(metrics.find("verdex.trading.duration").tag("operation","matching").timer().count()>0);
        } finally {
            reader.shutdownNow();
            jdbc.update("DELETE FROM exchange.orders WHERE stock_symbol=?",symbol);
            jdbc.update("DELETE FROM exchange.stocks WHERE symbol=?",symbol);
            jdbc.update("DELETE FROM exchange.users WHERE id=?",user);
        }
    }
}
