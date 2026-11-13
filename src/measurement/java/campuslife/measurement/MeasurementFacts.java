package campuslife.measurement;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Observational reconciliation, never a distributed atomic snapshot or an issuance writer. */
final class MeasurementFacts {
    record Expected(long id,long student,String key) {}
    record Snapshot(long started,long finished,Map<String,Object> sqlBefore,Map<String,Object> sqlAfter,
                    Map<String,Object> redis,Map<String,Object> queues,Map<String,Object> metrics,
                    boolean available,String missingCode) {}
    record Decision(String state,String code,boolean allowReplay,long actualSqlCoupons,long sqlAccepted) {}
    static long n(Map<String,Object> m,String k){Object v=m.get(k);if(!(v instanceof Number))throw new IllegalArgumentException("MISSING_COUNTER");return ((Number)v).longValue();}
    static Decision decision(Snapshot a,Snapshot b){
        if(!a.available||!b.available)return new Decision("UNAVAILABLE",!a.available?a.missingCode:b.missingCode,false,-1,-1);
        if(b.started-a.started<Duration.ofSeconds(5).toNanos())return new Decision("WAIT","STABILITY_WINDOW_TOO_SHORT",false,-1,-1);
        for(Snapshot s:List.of(a,b)){
            Map<String,Object> sql=s.sqlAfter,redis=s.redis;
            if(!Objects.equals(s.sqlBefore,sql))return new Decision("WAIT","SQL_CHANGED_DURING_SAMPLE",false,n(sql,"coupons"),n(sql,"accepted"));
            if(!Objects.equals(a.sqlAfter.get("serverUuid"),sql.get("serverUuid"))||!Objects.equals(a.redis.get("run"),redis.get("run"))
                 ||!Objects.equals(a.redis.get("ratingRun"),redis.get("ratingRun")))return new Decision("INVALID","IDENTITY_CHANGED",false,-1,-1);
            if(n(sql,"historyOrders")!=11000||n(sql,"historyPayments")!=11000||n(sql,"historyReviews")!=11000||n(sql,"historyEvents")!=55000
                 ||n(sql,"refunds")!=0||n(sql,"files")!=0)return new Decision("INVALID","HISTORY_CHANGED",false,n(sql,"coupons"),n(sql,"accepted"));
            if(n(sql,"issued")!=n(sql,"coupons")||n(sql,"issued")>n(sql,"total")||n(sql,"badRelations")!=0||n(sql,"badReceipts")!=0||n(sql,"missingOutbox")!=0
                 ||n(sql,"missingExpected")!=0||n(sql,"duplicateStudents")!=0)return new Decision("INVALID","SQL_FACT_MISMATCH",false,n(sql,"coupons"),n(sql,"accepted"));
            if(!"ASYNC".equals(sql.get("mode"))||!"OPEN".equals(sql.get("phase"))||!"OPEN".equals(redis.get("phase")))
                return new Decision("WAIT","GATE_NOT_OPEN",false,n(sql,"coupons"),n(sql,"accepted"));
            if(n(sql,"generation")!=n(redis,"generation")||!Objects.equals(sql.get("run"),redis.get("run")))return new Decision("INVALID","REDIS_EPOCH_MISMATCH",false,n(sql,"coupons"),n(sql,"accepted"));
            if(n(redis,"total")!=n(sql,"total")||n(redis,"free")+n(redis,"held")+n(redis,"spent")!=n(redis,"total"))
                return new Decision("INVALID","REDIS_CONSERVATION",false,n(sql,"coupons"),n(sql,"accepted"));
            if(n(sql,"pending")!=0||n(sql,"outboxOutstanding")!=0||n(sql,"missingReceipts")!=0||n(redis,"held")!=0
                 ||n(sql,"accepted")!=n(sql,"expectedAccepted")
                 ||n(redis,"spent")!=n(sql,"issued")||n(redis,"proofMismatches")!=0)return new Decision("WAIT","ASYNC_NOT_DRAINED",false,n(sql,"coupons"),n(sql,"accepted"));
            if(n(s.queues,"ready")!=0||n(s.queues,"unacked")!=0)return new Decision("WAIT","BROKER_NOT_EMPTY",false,n(sql,"coupons"),n(sql,"accepted"));
        }
        if(!a.sqlAfter.equals(b.sqlAfter)||!a.redis.equals(b.redis))return new Decision("WAIT","FACTS_CHANGED_BETWEEN_SAMPLES",false,n(b.sqlAfter,"coupons"),n(b.sqlAfter,"accepted"));
        return new Decision("OBSERVED_DRAINED","TWO_STABLE_OBSERVATIONS",true,n(b.sqlAfter,"coupons"),n(b.sqlAfter,"accepted"));
    }
    record MetricDelta(boolean available,String code,Map<String,Long> counters) {}
    @SuppressWarnings("unchecked")
    static MetricDelta delta(Map<String,Object> before,Map<String,Object> after){
        try{
            if(!Boolean.TRUE.equals(before.get("available"))||!Boolean.TRUE.equals(after.get("available")))return new MetricDelta(false,"OPTIONAL_METRIC_MISSING",Map.of());
            if(!Objects.equals(before.get("serverUuid"),after.get("serverUuid"))||n(after,"uptime")<n(before,"uptime"))return new MetricDelta(false,"METRIC_IDENTITY_OR_RESTART",Map.of());
            if(n(after,"digestLost")!=n(before,"digestLost"))return new MetricDelta(false,"DIGEST_LOSS",Map.of());
            Map<String,Map<String,Object>> old=(Map<String,Map<String,Object>>)before.get("digests"),now=(Map<String,Map<String,Object>>)after.get("digests");
            if(!now.keySet().containsAll(old.keySet()))return new MetricDelta(false,"DIGEST_DISAPPEARED",Map.of());
            Map<String,Long> totals=new LinkedHashMap<>();for(String k:List.of("count","timerPs","lockPs","rowsExamined","rowsSent"))totals.put(k,0L);
            for(var e:now.entrySet()){
                var previous=old.get(e.getKey());if(previous!=null&&!Objects.equals(previous.get("firstSeen"),e.getValue().get("firstSeen")))return new MetricDelta(false,"DIGEST_RESET",Map.of());
                for(String k:totals.keySet()){
                    long value=n(e.getValue(),k)-(previous==null?0:n(previous,k));if(value<0)return new MetricDelta(false,"COUNTER_RESET",Map.of());
                    totals.put(k,Math.addExact(totals.get(k),value));
                }
            }
            for(String k:List.of("rowLockWaits","rowLockTimeMs")){
                long d=n(after,k)-n(before,k);if(d<0)return new MetricDelta(false,"COUNTER_RESET",Map.of());totals.put(k,d);
            }
            return new MetricDelta(true,"OK",Map.copyOf(totals));
        }catch(Exception e){return new MetricDelta(false,"OPTIONAL_METRIC_INVALID",Map.of());}
    }
    /** Non-queuing sampler. Caller owns scheduling; starts no faster than every 5 seconds. */
    static final class Sampler {
        final Supplier<Snapshot> source;final AtomicBoolean active=new AtomicBoolean();volatile long lastStart=Long.MIN_VALUE;
        Sampler(Supplier<Snapshot> source){this.source=source;}
        Optional<Snapshot> sample(){
            if(!active.compareAndSet(false,true))return Optional.empty();
            try{long now=System.nanoTime();if(lastStart!=Long.MIN_VALUE&&now-lastStart<Duration.ofSeconds(5).toNanos())return Optional.empty();lastStart=now;return Optional.of(source.get());}
            finally{active.set(false);}
        }
    }
}
