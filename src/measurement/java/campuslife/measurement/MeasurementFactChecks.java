package campuslife.measurement;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import com.rabbitmq.client.*;
import io.lettuce.core.ScriptOutputType;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;
import static campuslife.measurement.MeasurementFacts.*;

/** Real private stores with synthetic persisted facts. Does not run HTTP admission/issuance consumers. */
public final class MeasurementFactChecks {
    static final ObjectMapper JSON=new ObjectMapper();static final Path OUTPUT=Path.of("/output");
    static final List<String> checks=new ArrayList<>();static final List<Map<String,Object>> views=new ArrayList<>();
    static final List<Expected> EXPECTED=List.of(new Expected(3000001,900001,"facts_1"),new Expected(3000002,900002,"facts_2"));
    static void check(String name,boolean v)throws Exception{if(!v)throw new AssertionError(name);checks.add(name);Files.writeString(OUTPUT.resolve("progress.json"),JSON.writeValueAsString(checks));}
    static void seedClaims(V24MeasurementFixture f)throws Exception{
        f.connection.setAutoCommit(false);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("{}".getBytes()));
        try{
            for(int i=1;i<=2;i++){
                f.jdbc.update("""
                    INSERT INTO user_coupon(id,student_id,campaign_id,shop_id,name_snapshot,threshold_amount,reduction_amount,use_start_at,use_end_at,source_campaign_version,claimed_at)
                    SELECT ?,?,id,shop_id,name,threshold_amount,reduction_amount,use_start_at,use_end_at,version,UTC_TIMESTAMP(3) FROM coupon_campaign WHERE id=2000001
                    """,4000000+i,900000+i);
                f.jdbc.update("""
                    INSERT INTO coupon_claim_request(id,student_id,campaign_id,request_key,user_coupon_id,completed_at,status,source_mode,created_at,accepted_at,deadline,reservation_id,accepted_generation,source_campaign_version)
                    VALUES(?,?,2000001,?,?,UTC_TIMESTAMP(3),'SUCCEEDED','ASYNC',UTC_TIMESTAMP(3)-INTERVAL 2 SECOND,UTC_TIMESTAMP(3)-INTERVAL 2 SECOND,UTC_TIMESTAMP(3)+INTERVAL 4 MINUTE,?,1,1)
                    """,3000000+i,900000+i,"facts_"+i,4000000+i,String.format("%032x",i));
                f.jdbc.update("""
                    INSERT INTO message_outbox(message_id,event_type,schema_version,aggregate_type,aggregate_id,event_seq,recipient_id,payload,payload_hash,occurred_at,created_at,status,attempts,last_attempt_at,published_at)
                    VALUES(?,'COUPON_ISSUE_REQUESTED',1,'COUPON_CLAIM',?,0,?,'{}',?,UTC_TIMESTAMP(3)-INTERVAL 2 SECOND,UTC_TIMESTAMP(3)-INTERVAL 2 SECOND,'SENT',1,UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,UTC_TIMESTAMP(3))
                    """,String.format("%032x",i),3000000+i,900000+i,hash);
                f.jdbc.update("INSERT INTO message_consumption(consumer_name,message_id,payload_hash,processed_at) VALUES('coupon-issue-v1',?,?,UTC_TIMESTAMP(3))",String.format("%032x",i),hash);
            }
            f.jdbc.update("UPDATE coupon_campaign SET issued_count=2 WHERE id=2000001");f.connection.commit();
        }catch(Exception e){f.connection.rollback();throw e;}finally{f.connection.setAutoCommit(true);}
    }
    static Map<String,Object> view(Snapshot s){
        Map<String,Object> sql=new LinkedHashMap<>(s.sqlAfter());sql.remove("claims");sql.remove("outboxFacts");
        return Map.of("startedNanos",s.started(),"finishedNanos",s.finished(),"available",s.available(),"missingCode",s.missingCode(),"sql",sql,"redis",s.redis(),"queues",s.queues(),"metrics",s.metrics());
    }
    static Snapshot next(Sampler sampler)throws Exception{
        long until=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until){var s=sampler.sample();if(s.isPresent()){views.add(view(s.get()));return s.get();}Thread.sleep(50);}
        throw new AssertionError("SAMPLER_NOT_DUE");
    }
    static Snapshot change(Snapshot s,String section,String key,Object value){
        Map<String,Object> sql=new LinkedHashMap<>(s.sqlAfter()),redis=new LinkedHashMap<>(s.redis()),q=new LinkedHashMap<>(s.queues());
        (section.equals("sql")?sql:section.equals("redis")?redis:q).put(key,value);
        return new Snapshot(s.started(),s.finished(),sql,sql,redis,q,s.metrics(),s.available(),s.missingCode());
    }
    static void pureChecks(Snapshot a,Snapshot b)throws Exception{
        check("unavailable-critical-cannot-authorize",!decision(new Snapshot(a.started(),a.finished(),Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),false,"MISSING"),b).allowReplay());
        check("too-short-stability-window-refused",!decision(a,a).allowReplay());
        check("wrong-generation-refused",!decision(a,change(b,"redis","generation",999L)).allowReplay());
        check("nonconserved-inventory-refused",decision(a,change(b,"redis","free",999L)).state().equals("INVALID"));
        check("sql-issued-coupon-mismatch-invalid",decision(a,change(b,"sql","issued",3L)).state().equals("INVALID"));
        check("historical-facts-change-invalid",decision(a,change(b,"sql","historyOrders",10999L)).state().equals("INVALID"));
        check("accepted-result-set-must-be-reconciled",!decision(a,change(b,"sql","expectedAccepted",3L)).allowReplay());
        check("redis-proof-mismatch-does-not-drain",!decision(a,change(b,"redis","proofMismatches",1L)).allowReplay());
        check("pending-and-outstanding-not-drained",!decision(a,change(b,"sql","pending",1L)).allowReplay()&&!decision(a,change(b,"sql","outboxOutstanding",1L)).allowReplay());
        var m=Map.<String,Object>of("available",false);check("optional-missing-never-becomes-zero-delta",!delta(m,m).available());
        var metrics=new LinkedHashMap<String,Object>();metrics.put("available",true);metrics.put("serverUuid","test");metrics.put("uptime",10L);metrics.put("digestLost",0L);metrics.put("rowLockWaits",3L);metrics.put("rowLockTimeMs",4L);
        var digest=new LinkedHashMap<String,Object>();for(String k:List.of("count","timerPs","lockPs","rowsExamined","rowsSent"))digest.put(k,10L);digest.put("firstSeen","x");metrics.put("digests",Map.of("d",digest));
        var now=new LinkedHashMap<>(metrics);var lowered=new LinkedHashMap<>(digest);lowered.put("count",5L);now.put("digests",Map.of("d",lowered));
        check("counter-reset-not-performance-benefit",!delta(metrics,now).available());now.put("digests",Map.of());check("digest-disappearance-is-missing",!delta(metrics,now).available());
        now.put("digests",Map.of("d",digest));now.put("digestLost",1L);check("digest-loss-invalidates-delta",!delta(metrics,now).available());
        now=new LinkedHashMap<>(metrics);var reset=new LinkedHashMap<>(digest);reset.put("firstSeen","new");now.put("digests",Map.of("d",reset));check("same-digest-reset-is-detected",!delta(metrics,now).available());
        var sampler=new Sampler(()->b);check("sampler-five-second-rate-limit",sampler.sample().isPresent()&&sampler.sample().isEmpty());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);var blocked=new Sampler(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return b;});
        Thread t=Thread.ofPlatform().start(blocked::sample);entered.await(1,TimeUnit.SECONDS);check("sampler-no-overlap-or-queue",blocked.sample().isEmpty());release.countDown();t.join(1000);check("sampler-worker-finished",!t.isAlive());
    }
    static Channel topology(com.rabbitmq.client.Connection connection)throws Exception{
        Channel c=connection.createChannel();c.exchangeDeclare("campuslife.events.v1","direct",true);c.exchangeDeclare("campuslife.dead.v1","direct",true);
        for(String q:MeasurementFactSources.QUEUES){
            Map<String,Object> args=new HashMap<>();args.put("x-queue-type","quorum");
            if(!q.contains(".dead.")){args.put("x-delivery-limit",5);args.put("x-dead-letter-exchange","campuslife.dead.v1");args.put("x-dead-letter-routing-key",q.contains("coupon")?"coupon.issue.dead":"notification.dead");args.put("x-overflow","reject-publish");args.put("x-dead-letter-strategy","at-least-once");}
            c.queueDeclare(q,true,false,false,args);
        }return c;
    }
    @SuppressWarnings("unchecked")
    static void run(Path input)throws Exception{
        long started=System.nanoTime();Map<String,Object> permit=JSON.readValue(Files.readString(input.resolve("permit.json")),Map.class);
        try(var writer=new V24MeasurementFixture(input.resolve("permit.json"))){
            writer.identity(writer.permit);Flyway.configure().dataSource(V24MeasurementFixture.URL,"campuslife",Files.readString(input.resolve("DB_PASSWORD")).strip()).locations("filesystem:/tool/migrations").load().migrate();
            writer.seed(false);seedClaims(writer);
            Files.writeString(OUTPUT.resolve("ready-for-resume.json"),"{\"campaign\":2000001,\"controlVersion\":0,\"syntheticSqlCoupons\":2}");
            long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();while(!Files.exists(OUTPUT.resolve("resume-done.json"))&&System.nanoTime()<end)Thread.sleep(100);
            check("one-original-resume-completed",Files.exists(OUTPUT.resolve("resume-done.json")));
            ConnectionFactory factory=new ConnectionFactory();factory.setHost("rabbitmq");factory.setVirtualHost(permit.get("vhost").toString());factory.setUsername("campuslife_ops");factory.setPassword(Files.readString(input.resolve("RABBIT_OPS_PASSWORD")).strip());factory.setConnectionTimeout(2000);factory.setHandshakeTimeout(2000);factory.setChannelRpcTimeout(2000);factory.setShutdownTimeout(2000);factory.setAutomaticRecoveryEnabled(false);
            try(var broker=factory.newConnection();var channel=topology(broker);var source=new MeasurementFactSources(input,EXPECTED,2000001)){
                var sampler=new Sampler(source::collect);Snapshot a=null;
                for(int i=0;i<6;i++){Snapshot candidate=next(sampler);if(candidate.available()){a=candidate;break;}}
                check("required-counter-fields-ready-within-preparation",a!=null);Snapshot b=next(sampler);
                check("real-private-stores-sampled",a.available()&&b.available());
                Decision positive=decision(a,b);check("mysql-two-coupons-redis-two-spent-observed-drained",positive.allowReplay()&&positive.actualSqlCoupons()==2&&positive.sqlAccepted()==2);
                check("queues-all-four-required-and-zero",((List<?>)b.queues().get("queues")).size()==4&&n(b.queues(),"ready")==0&&n(b.queues(),"unacked")==0);
                Map<String,Object> sqlMetric=(Map<String,Object>)b.metrics().get("sql");check("real-performance-schema-and-lock-counters",Boolean.TRUE.equals(sqlMetric.get("available"))&&sqlMetric.containsKey("rowLockTimeMs"));
                check("real-counter-delta-available",delta((Map<String,Object>)a.metrics().get("sql"),sqlMetric).available());
                check("observer-catalog-separates-observation-statements","performance_schema".equals(sqlMetric.get("observerDefaultCatalog")));
                check("read-query-count-and-cost-recorded",n(b.metrics(),"observerSelects")>0&&n(b.metrics(),"observerSqlElapsedNanos")>0&&n(b.metrics(),"redisCommands")==9);
                try(var ro=source.readOnlyConnection()){
                    boolean denied=false;try(var s=ro.createStatement()){s.executeUpdate("UPDATE campuslife_deploy.account SET version=version WHERE id=900001");}catch(SQLException expected){denied=true;}ro.rollback();check("actual-observer-sql-write-refused",denied);
                    long first=new MeasurementFactSources.Sql(ro).count("SELECT revision FROM campuslife_deploy.shop_rating_revision WHERE shop_id=1000006");writer.jdbc.update("UPDATE shop_rating_revision SET revision=revision+1 WHERE shop_id=1000006");
                    long repeat=new MeasurementFactSources.Sql(ro).count("SELECT revision FROM campuslife_deploy.shop_rating_revision WHERE shop_id=1000006");ro.commit();check("real-repeatable-read-snapshot",first==repeat);writer.jdbc.update("UPDATE shop_rating_revision SET revision=? WHERE shop_id=1000006",first);
                }
                boolean evalDenied=false;String key="coupon:"+permit.get("namespace")+":observer-write-probe";
                try{source.coupon.sync().evalReadOnly("return redis.call('SET',KEYS[1],'x')",ScriptOutputType.VALUE,new String[]{key});}catch(Exception expected){evalDenied=true;}
                check("redis-eval-ro-rejects-write",evalDenied&&source.coupon.sync().get(key)==null);
                pureChecks(a,b);
                writer.jdbc.update("UPDATE coupon_campaign SET issued_count=3 WHERE id=2000001");Snapshot mismatch=next(sampler);check("real-sql-inconsistent-issuance-refused",decision(a,mismatch).state().equals("INVALID"));writer.jdbc.update("UPDATE coupon_campaign SET issued_count=2 WHERE id=2000001");
                writer.jdbc.update("UPDATE message_consumption SET payload_hash=REPEAT('b',64) WHERE consumer_name='coupon-issue-v1' AND message_id=?",String.format("%032x",1));Snapshot badReceipt=next(sampler);check("real-wrong-consumption-hash-refused",decision(a,badReceipt).state().equals("INVALID"));
                writer.jdbc.update("UPDATE message_consumption m JOIN message_outbox o ON o.message_id=m.message_id SET m.payload_hash=o.payload_hash WHERE m.consumer_name='coupon-issue-v1'");
                channel.confirmSelect();channel.basicPublish("",MeasurementFactSources.QUEUES.getFirst(),null,"controlled-count-probe".getBytes());channel.waitForConfirmsOrDie(2000);
                Snapshot ready=null;for(int i=0;i<6;i++){ready=next(sampler);if(ready.available()&&n(ready.queues(),"ready")>0)break;}
                check("real-broker-ready-backlog-blocks-drain",ready!=null&&ready.available()&&n(ready.queues(),"ready")>0&&!decision(a,ready).allowReplay());
                GetResponse message=channel.basicGet(MeasurementFactSources.QUEUES.getFirst(),false);check("controlled-message-held-unacked",message!=null);
                Snapshot unacked=null;for(int i=0;i<6;i++){unacked=next(sampler);if(unacked.available()&&n(unacked.queues(),"unacked")>0)break;}
                check("real-unacked-backlog-blocks-drain",unacked!=null&&unacked.available()&&n(unacked.queues(),"unacked")>0&&!decision(a,unacked).allowReplay());channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
                channel.queueDelete(MeasurementFactSources.QUEUES.getLast());Snapshot missing=next(sampler);check("real-missing-queue-is-unavailable-not-zero",!missing.available()&&!decision(a,missing).allowReplay());
                channel.queueDeclare(MeasurementFactSources.QUEUES.getLast(),true,false,false,Map.of("x-queue-type","quorum"));
                Snapshot finalA=null,finalB=null;for(int i=0;i<7;i++){Snapshot s=next(sampler);if(finalA!=null&&decision(finalA,s).allowReplay()){finalB=s;break;}finalA=s;}
                check("restored-stores-require-two-stable-samples",finalB!=null&&decision(finalA,finalB).allowReplay());
                check("no-order-funds-or-file-mutation",writer.count("SELECT COUNT(*) FROM service_order")==11000&&writer.count("SELECT COUNT(*) FROM payment_record")==11000&&writer.count("SELECT COUNT(*) FROM refund_record")==0&&writer.count("SELECT COUNT(*) FROM file_asset")==0);
                Files.writeString(OUTPUT.resolve("samples.json"),JSON.writeValueAsString(views));
            }
        }
        Files.writeString(OUTPUT.resolve("checks.json"),JSON.writeValueAsString(Map.of("task","M06.2-B.2-C.2","passed",true,"checks",checks,"elapsedSeconds",(System.nanoTime()-started)/1e9,"syntheticSqlIssuance",true,"businessHttpRequests",0,"samples",views.size())));
    }
    public static void main(String[] args){try{run(Path.of(args[0]));}catch(Throwable error){
        String code=error instanceof AssertionError?error.getMessage():error instanceof MeasurementFactSources.Missing?error.getMessage():"FACT_CHECKS_FAILED";
        try{Files.writeString(OUTPUT.resolve("failure.json"),JSON.writeValueAsString(Map.of("failureType",error.getClass().getSimpleName(),"code",code,"checks",checks)));Files.writeString(OUTPUT.resolve("samples.json"),JSON.writeValueAsString(views));}catch(Exception ignored){}
        System.out.println("{\"passed\":false,\"code\":\""+code+"\"}");System.exit(1);
    }}
}
