package com.campuslife.order.service;
import com.campuslife.order.mapper.OrderMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.campuslife.common.exception.BusinessException;
import java.util.stream.Stream;

@Component
@Profile("mysql")
public class BookingExpiryJob {
    private static final Logger log=LoggerFactory.getLogger(BookingExpiryJob.class);
    private final OrderMapper orders;private final BookingOrderTransactions transactions;private final Clock clock;private final boolean enabled;
    public BookingExpiryJob(OrderMapper orders,BookingOrderTransactions transactions,Clock clock,@Value("${campuslife.booking.expiry-enabled:true}") boolean enabled) {
        this.orders=orders;this.transactions=transactions;this.clock=clock;this.enabled=enabled;
    }
    @Scheduled(initialDelay=0,fixedDelay=30000)
    public void scheduled() { if (enabled) { scan(); } }
    // Also callable by integration tests to verify repeated scans and recovery with a controlled Clock.
    public int scan() {
        int closed=0;
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        for (long id: Stream.concat(orders.due(now).stream(),orders.acceptanceDue(now).stream()).toList()) {
            try { if (transactions.expire(id)) { closed++; } }
            catch (BusinessException ex) { log.warn("Booking expiry deferred for order {}: {}; next scan will retry",id,ex.getMessage()); }
            catch (RuntimeException ex) { log.error("Booking expiry failed for order {}; next scan will retry",id,ex); }
        }
        return closed;
    }
}
