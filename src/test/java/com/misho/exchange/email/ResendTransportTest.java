package com.misho.exchange.email;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class ResendTransportTest {
    @Test void sendsHttpContractAndRejectsProviderErrorsWithoutLeakingBodies()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var auth=new AtomicReference<String>();var key=new AtomicReference<String>();var body=new AtomicReference<String>();
        server.createContext("/emails",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] response="{\"id\":\"test-provider-id\"}".getBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try{
            var config=new EmailConfiguration(new MockEnvironment().withProperty("verdex.email.api-key","fixture-secret"));
            var transport=new ResendTransport(config,new ObjectMapper(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
            assertEquals("test-provider-id",transport.send("stable-id","{\"from\":\"test@example.test\",\"to\":[\"delivered@resend.dev\"],\"subject\":\"Fixture\",\"text\":\"fixture\"}"));
            assertEquals("Bearer fixture-secret",auth.get());assertEquals("stable-id",key.get());assertEquals("fixture",new ObjectMapper().readTree(body.get()).get("text").asText());
            server.removeContext("/emails");server.createContext("/emails",exchange->{byte[] response="private provider diagnostic".getBytes();exchange.getResponseHeaders().set("Retry-After","120");exchange.sendResponseHeaders(429,response.length);exchange.getResponseBody().write(response);exchange.close();});
            var error=assertThrows(EmailDeliveryException.class,()->transport.send("stable-id","{}"));assertEquals("EMAIL_PROVIDER_RATE_LIMITED",error.getMessage());assertTrue(error.retryable);assertEquals(120,error.retryAfterSeconds);
            server.removeContext("/emails");server.createContext("/emails",exchange->{byte[] response="{\"name\":\"validation_error\"}".getBytes();exchange.sendResponseHeaders(403,response.length);exchange.getResponseBody().write(response);exchange.close();});
            assertFalse(assertThrows(EmailDeliveryException.class,()->transport.send("stable-id","{}")).retryable);
        }finally{server.stop(0);}
    }
    @Test void enablingEmailRequiresProtectedConfigurationAndATrustedLinkOrigin(){
        var env=new MockEnvironment().withProperty("verdex.email.enabled","true");
        assertThrows(IllegalStateException.class,()->new EmailConfiguration(env));
        env.withProperty("verdex.email.api-key","fixture").withProperty("verdex.email.from","test@example.test").withProperty("verdex.email.encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            .withProperty("verdex.email.public-url","https://example.test/#/bad");
        assertThrows(IllegalStateException.class,()->new EmailConfiguration(env));
        env.withProperty("verdex.email.public-url","https://example.test");assertTrue(new EmailConfiguration(env).enabled);
        env.withProperty("verdex.email.allowed-recipient","only@example.test");var config=new EmailConfiguration(env);assertFalse(config.accepts("other@example.test"));assertTrue(config.accepts("only@example.test"));
    }
}
