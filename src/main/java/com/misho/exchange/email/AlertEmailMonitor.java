package com.misho.exchange.email;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.misho.exchange.workspace.WorkspaceService;

/** Bounded round-robin work; no inference about crossings while the process is asleep. */
@Component
public class AlertEmailMonitor {
    private final EmailConfiguration config;
    private final JdbcTemplate jdbc;
    private final WorkspaceService workspace;
    private long cursor;
    public AlertEmailMonitor(EmailConfiguration config,JdbcTemplate jdbc,WorkspaceService workspace){this.config=config;this.jdbc=jdbc;this.workspace=workspace;}
    @Scheduled(fixedDelayString="${verdex.email.alert-delay:60000}")
    public void tick(){
        if(!config.enabled || !config.workersEnabled)return;
        try {
            var users=jdbc.queryForList("SELECT id FROM exchange.users u WHERE id>? AND email_verified AND alert_emails AND EXISTS(SELECT 1 FROM exchange.price_alerts a WHERE a.user_id=u.id AND triggered_at IS NULL) ORDER BY id LIMIT 20",Long.class,cursor);
            if(users.isEmpty()){cursor=0;return;}
            for(long user:users){cursor=user;try{workspace.checkAlerts(user);}catch(RuntimeException ignored){}}
        }catch(RuntimeException ignored){}
    }
}
