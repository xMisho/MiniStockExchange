package com.misho.exchange.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.core.net.RequestOptions;
import com.resend.services.emails.model.CreateEmailOptions;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ResendTransport implements EmailTransport {
    private final ObjectMapper json;
    private final Resend resend;
    private final ThreadLocal<Long> retryAfter = ThreadLocal.withInitial(()->0L);
    @org.springframework.beans.factory.annotation.Autowired
    public ResendTransport(EmailConfiguration config,ObjectMapper json){this(config,json,URI.create("https://api.resend.com"));}
    ResendTransport(EmailConfiguration config,ObjectMapper json,URI baseUrl){
        this.json=json;
        var http=new okhttp3.OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5)).callTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).addInterceptor(chain->{
                    var response=chain.proceed(chain.request());retryAfter.set(parseRetryAfter(response.header("Retry-After")));return response;
                }).build();
        resend=Resend.builder().apiKey(config.apiKey.isBlank()?"re_disabled":config.apiKey).httpClient(new com.resend.core.net.impl.HttpClient(http,baseUrl.toString())).build();
    }
    @Override public String send(String idempotencyKey,String body){
        retryAfter.set(0L);
        try{
            var data=json.readTree(body);
            var recipients=new java.util.ArrayList<String>();data.path("to").forEach(value->recipients.add(value.asText()));
            var params=CreateEmailOptions.builder().from(data.path("from").asText()).to(recipients).subject(data.path("subject").asText()).text(data.path("text").asText()).build();
            String id=resend.emails().send(params,RequestOptions.builder().setIdempotencyKey(idempotencyKey).timeout(Duration.ofSeconds(10)).build()).getId();
            if(id==null || id.isBlank() || id.length()>128)throw new EmailDeliveryException("EMAIL_PROVIDER_RESPONSE_INVALID",true,0);
            return id;
        }catch(ResendException e){
            Integer status=e.getStatusCode();
            boolean retry=status==null || status>=500 || status==429 || (status==409 && "concurrent_idempotent_requests".equals(e.getErrorName()));
            throw new EmailDeliveryException(status==429?"EMAIL_PROVIDER_RATE_LIMITED":"EMAIL_PROVIDER_REJECTED",retry,retryAfter.get());
        }finally{retryAfter.remove();}
    }
    private long parseRetryAfter(String value){
        if(value==null)return 0;
        try{return Math.max(0,Long.parseLong(value.strip()));}
        catch(NumberFormatException e){try{return Math.max(0,Duration.between(Instant.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());}catch(RuntimeException ignored){return 0;}}
    }
}
