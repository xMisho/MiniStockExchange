package com.misho.exchange.email;

import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
public class EmailWorker {
    private record Job(UUID id, String payload, int attempts) { }
    private final JdbcTemplate jdbc;
    private final EmailConfiguration config;
    private final EmailOutbox outbox;
    private final EmailTransport transport;
    private final TransactionTemplate tx;
    public EmailWorker(JdbcTemplate jdbc, EmailConfiguration config, EmailOutbox outbox, EmailTransport transport, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.config=config; this.outbox=outbox; this.transport=transport; tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${verdex.email.worker-delay:10000}")
    public void tick() {
        if (!config.enabled || !config.workersEnabled) return;
        try { for (int i=0;i<10;i++) if (!deliverOne()) break; }
        catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Email queue processing unavailable; pending messages retained."); }
    }
    public boolean deliverOne() { return deliverOne(null); }
    boolean deliverOne(UUID onlyId) {
        if (!config.enabled) return false;
        Job job = tx.execute(status -> {
            jdbc.update("DELETE FROM exchange.email_tokens WHERE expires_at < CURRENT_TIMESTAMP-INTERVAL '1 day'");
            jdbc.update("DELETE FROM exchange.email_outbox WHERE status IN ('SENT','FAILED','CANCELLED') AND created_at < CURRENT_TIMESTAMP-INTERVAL '30 days'");
            jdbc.update("UPDATE exchange.email_outbox SET status='FAILED',payload=NULL,failure_code='EXPIRED' WHERE status IN ('PENDING','SENDING') AND expires_at <= CURRENT_TIMESTAMP");
            jdbc.update("UPDATE exchange.email_outbox e SET status='CANCELLED',payload=NULL WHERE kind='ALERT' AND status='PENDING' AND NOT EXISTS (SELECT 1 FROM exchange.users u WHERE u.id=e.user_id AND u.email_verified AND u.alert_emails)");
            jdbc.update("UPDATE exchange.email_outbox SET status='FAILED',payload=NULL,failure_code='ATTEMPTS_EXHAUSTED' WHERE status='SENDING' AND attempts>=8 AND lease_until<CURRENT_TIMESTAMP");
            var rows = jdbc.query("SELECT id,payload,attempts FROM exchange.email_outbox WHERE expires_at > CURRENT_TIMESTAMP AND attempts < 8 AND ((status='PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP) OR (status='SENDING' AND lease_until < CURRENT_TIMESTAMP)) AND (?::uuid IS NULL OR id=?) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                    (row,index)->new Job(row.getObject(1,UUID.class),row.getString(2),row.getInt(3)), onlyId, onlyId);
            if (rows.isEmpty()) return null;
            var item=rows.get(0);
            jdbc.update("UPDATE exchange.email_outbox SET status='SENDING',attempts=attempts+1,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds' WHERE id=?",item.id());
            return item;
        });
        if (job == null) return false;
        try {
            String provider = transport.send("verdex-email/"+job.id(), outbox.decrypt(job.payload()));
            jdbc.update("UPDATE exchange.email_outbox SET status='SENT',payload=NULL,provider_id=?,failure_code=NULL WHERE id=? AND status='SENDING'",provider,job.id());
        } catch (RuntimeException e) {
            boolean permanent=e instanceof EmailDeliveryException delivery && !delivery.retryable;
            boolean failed=permanent || job.attempts()+1>=8;
            long providerDelay=e instanceof EmailDeliveryException delivery?delivery.retryAfterSeconds:0;
            long delay=Math.max(Math.min(900,30L << job.attempts()),Math.min(86400,providerDelay));
            String code=e instanceof EmailDeliveryException?e.getMessage():"DELIVERY_FAILED";
            jdbc.update("UPDATE exchange.email_outbox SET status=?,payload=CASE WHEN ? THEN NULL ELSE payload END,next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),failure_code=? WHERE id=? AND status='SENDING'",
                    failed?"FAILED":"PENDING",failed,delay,code,job.id());
        }
        return true;
    }
}
