package com.campuslife.order.service;

import com.campuslife.order.mapper.PrintOrderMapper;
import java.time.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("mysql")
public class PrintExpiryJob {
    private static final Logger log=LoggerFactory.getLogger(PrintExpiryJob.class);
    private final PrintOrderMapper prints;private final PrintOrderTransactions transactions;private final Clock clock;private final boolean enabled;
    public PrintExpiryJob(PrintOrderMapper prints,PrintOrderTransactions transactions,Clock clock,@Value("${campuslife.print.expiry-enabled:true}") boolean enabled){this.prints=prints;this.transactions=transactions;this.clock=clock;this.enabled=enabled;}
    @Scheduled(initialDelay=0,fixedDelay=30000)
    public void scheduled(){if(enabled){scan();}}
    public int scan(){
        int n=0;var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        // Separate quotas ensure disabled refunds or failed paid orders cannot starve unpaid closure.
        var candidates=java.util.stream.Stream.concat(prints.due(now).stream(),prints.acceptanceDue(now).stream()).toList();
        for(long id:candidates){try{if(transactions.expire(id)){n++;}}catch(RuntimeException ex){log.warn("Print expiry deferred for order {}; next scan will retry",id,ex);}}
        return n;
    }
}
