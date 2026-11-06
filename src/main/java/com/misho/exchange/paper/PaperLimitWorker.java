package com.misho.exchange.paper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="verdex.paper.worker-enabled",havingValue="true",matchIfMissing=true)
public class PaperLimitWorker {
    private final PaperTradingService paper;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PaperLimitWorker.class);
    public PaperLimitWorker(PaperTradingService paper){this.paper=paper;}
    @Scheduled(fixedDelay=15000,initialDelay=15000) public void check(){
        try{paper.processPending();}catch(RuntimeException failure){LOG.warn("Paper limit check deferred: {}",failure.getClass().getSimpleName());}
    }
}
