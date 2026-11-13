package campuslife.measurement;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.resource.DefaultClientResources;
import tools.jackson.databind.*;
import static campuslife.measurement.MeasurementFacts.*;

/** Fixed private endpoints, SELECT-only account/transactions, EVAL_RO and bounded HTTP GET. */
final class MeasurementFactSources implements AutoCloseable {
    static final ObjectMapper JSON=new ObjectMapper();static final String D="campuslife_deploy.";
    static final String URL="jdbc:mysql://mysql:3306/performance_schema?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=1800&socketTimeout=1800";
    static final List<String> QUEUES=List.of("campuslife.coupon.issue.v1","campuslife.coupon.issue.dead.v1","campuslife.notification.v1","campuslife.notification.dead.v1");
    static final class Missing extends RuntimeException {Missing(String code){super(code);}}
    static void need(boolean yes,String code){if(!yes)throw new Missing(code);}
    final Map<String,Object> permit;final Path input;final long campaign;final List<Expected> expected;
    final ExecutorService sqlWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().name("facts-sql").factory());
    final ExecutorService aborts=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("facts-abort").factory());
    final DefaultClientResources redisResources;
    final RedisClient ratingClient,couponClient;final StatefulRedisConnection<String,String> rating,coupon;
    final HttpClient http;
    final String namespace,vhost,basic,lua;volatile Connection currentSql;
    @SuppressWarnings("unchecked")
    MeasurementFactSources(Path input,List<Expected> expected,long campaign)throws Exception {
        this.input=input;this.expected=List.copyOf(expected);this.campaign=campaign;
        permit=JSON.readValue(Files.readString(input.resolve("permit.json")),Map.class);
        String id=Objects.toString(permit.get("instanceId"),"");
        need(id.matches("[a-f0-9]{32}")&&Objects.toString(permit.get("caseId"),"").matches("case-[a-f0-9]{12,32}")&&"test".equals(permit.get("kind")),"PERMIT_IDENTITY");
        namespace="deploy_"+id;vhost="campuslife_"+id;
        need(namespace.equals(permit.get("namespace"))&&vhost.equals(permit.get("vhost"))&&campaign>0&&expected.size()<=60,"NAMESPACE_IDENTITY");
        need(new HashSet<>(expected.stream().map(Expected::id).toList()).size()==expected.size(),"DUPLICATE_EXPECTED");
        basic="Basic "+Base64.getEncoder().encodeToString(("campuslife_ops:"+secret("RABBIT_OPS_PASSWORD")).getBytes(StandardCharsets.UTF_8));
        lua=Files.readString(Path.of("/tool/redis/common.lua"))+Files.readString(Path.of("/tool/observe.lua"));
        redisResources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
        RedisClient rc=null,cc=null;StatefulRedisConnection<String,String> r=null,c=null;
        try{rc=redis("rating-redis",secret("RATING_REDIS_OPS_PASSWORD"));r=rc.connect();cc=redis("coupon-redis",secret("COUPON_REDIS_OPS_PASSWORD"));c=cc.connect();}
        catch(Exception e){if(r!=null)r.close();if(c!=null)c.close();if(rc!=null)rc.shutdown();if(cc!=null)cc.shutdown();redisResources.shutdown();sqlWorker.shutdownNow();aborts.shutdownNow();http.shutdownNow();throw new Missing("REDIS_CONNECTION_UNAVAILABLE");}
        ratingClient=rc;couponClient=cc;rating=r;coupon=c;
    }
    String secret(String file)throws Exception{return Files.readString(input.resolve(file)).strip();}
    RedisClient redis(String host,String password){
        var uri=RedisURI.Builder.redis(host,6379).withAuthentication("redis_ops",password.toCharArray()).withTimeout(Duration.ofSeconds(2)).build();
        RedisClient c=RedisClient.create(redisResources,uri);c.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(32)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
            .socketOptions(io.lettuce.core.SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build()).build());return c;
    }
    Connection readOnlyConnection()throws Exception{
        Connection c=DriverManager.getConnection(URL,"campuslife_measure",secret("METRICS_DB_PASSWORD"));
        c.setNetworkTimeout(aborts,1800);c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
        try(Statement s=c.createStatement()){s.setQueryTimeout(1);s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");}return c;
    }
    static final class Sql {
        final Connection c;int queries;Sql(Connection c){this.c=c;}
        List<Map<String,Object>> rows(String sql,Object... args)throws Exception{
            queries++;try(PreparedStatement q=c.prepareStatement(sql)){q.setQueryTimeout(1);for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);
                try(ResultSet r=q.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){
                    need(out.size()<1001,"ROW_BOUND");Map<String,Object> m=new LinkedHashMap<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)m.put(r.getMetaData().getColumnLabel(i),r.getObject(i));out.add(m);
                }return out;}}
        }
        long count(String sql,Object... args)throws Exception{return ((Number)rows(sql,args).getFirst().values().iterator().next()).longValue();}
    }
    record SqlBundle(Map<String,Object> facts,Map<String,Object> metrics,int queries,long elapsed) {}
    SqlBundle sql() {
        Future<SqlBundle> future;
        try{future=sqlWorker.submit(this::sqlNow);}catch(RejectedExecutionException e){throw new Missing("SQL_OBSERVER_BUSY");}
        try{return future.get(2,TimeUnit.SECONDS);}
        catch(Exception e){future.cancel(true);Connection c=currentSql;if(c!=null)try{c.abort(aborts);}catch(SQLException ignored){}throw new Missing("SQL_SAMPLE_UNAVAILABLE");}
    }
    SqlBundle sqlNow()throws Exception{
        long at=System.nanoTime();try(Connection c=readOnlyConnection()){
            currentSql=c;Sql q=new Sql(c);Map<String,Object> identity=q.rows("SELECT @@server_uuid AS serverUuid,@@hostname AS serverHostname,DATABASE() AS observerCatalog").getFirst();
            need(Objects.equals(identity.get("serverUuid"),permit.get("serverUuid"))&&Objects.equals(identity.get("serverHostname"),permit.get("serverHostname"))
                 &&"performance_schema".equals(identity.get("observerCatalog")),"SQL_SERVER_IDENTITY");
            need(q.count("SELECT MAX(CAST(version AS UNSIGNED)) FROM "+D+"flyway_schema_history WHERE success=1")==24,"V24_REQUIRED");
            Map<String,Object> facts=new LinkedHashMap<>();facts.put("serverUuid",identity.get("serverUuid"));
            var gates=q.rows("SELECT c.total_quantity AS total,c.issued_count AS issued,g.mode,g.phase,g.generation,g.control_version AS controlVersion,g.redis_run_id AS run FROM "+D+"coupon_campaign c JOIN "+D+"coupon_dispatch_gate g ON g.campaign_id=c.id WHERE c.id=?",campaign);
            need(gates.size()==1,"CAMPAIGN_MISSING");facts.putAll(gates.getFirst());
            var claims=q.rows("SELECT r.id,r.student_id AS student,r.request_key AS requestKey,r.status,r.user_coupon_id AS coupon,r.reservation_id AS reservation,r.source_mode AS sourceMode,r.accepted_at AS accepted FROM "+D+"coupon_claim_request r WHERE r.campaign_id=? ORDER BY r.id LIMIT 121",campaign);
            need(claims.size()<=120,"CLAIM_BOUND");facts.put("claims",claims);
            facts.put("accepted",claims.stream().filter(r->r.get("accepted")!=null).count());facts.put("pending",claims.stream().filter(r->"PENDING".equals(r.get("status"))).count());
            facts.put("expectedAccepted",expected.size());
            facts.put("coupons",q.count("SELECT COUNT(*) FROM "+D+"user_coupon WHERE campaign_id=?",campaign));
            facts.put("duplicateStudents",q.count("SELECT COUNT(*) FROM (SELECT student_id FROM "+D+"user_coupon WHERE campaign_id=? GROUP BY student_id HAVING COUNT(*)>1) d",campaign));
            facts.put("badRelations",q.count("SELECT COUNT(*) FROM "+D+"coupon_claim_request r LEFT JOIN "+D+"user_coupon c ON c.id=r.user_coupon_id WHERE r.campaign_id=? AND ((r.status='SUCCEEDED' AND (c.id IS NULL OR c.student_id<>r.student_id OR c.campaign_id<>r.campaign_id)) OR (r.status<>'SUCCEEDED' AND c.id IS NOT NULL))",campaign));
            long missing=0;for(Expected e:expected)if(claims.stream().noneMatch(r->((Number)r.get("id")).longValue()==e.id()&&((Number)r.get("student")).longValue()==e.student()&&e.key().equals(r.get("requestKey"))&&"ASYNC".equals(r.get("sourceMode"))&&r.get("accepted")!=null))missing++;
            facts.put("missingExpected",missing);
            var outbox=q.rows("SELECT r.id,o.message_id AS messageId,o.status AS outboxStatus,o.payload_hash AS expectedHash,m.payload_hash AS receiptHash FROM "+D+"coupon_claim_request r LEFT JOIN "+D+"message_outbox o ON o.aggregate_id=r.id AND o.aggregate_type='COUPON_CLAIM' AND o.event_type='COUPON_ISSUE_REQUESTED' AND o.recipient_id=r.student_id AND o.event_seq=0 LEFT JOIN "+D+"message_consumption m ON m.message_id=o.message_id AND m.consumer_name='coupon-issue-v1' WHERE r.campaign_id=? AND r.source_mode='ASYNC' AND r.accepted_at IS NOT NULL ORDER BY r.id",campaign);
            facts.put("outboxOutstanding",outbox.stream().filter(r->!"SENT".equals(r.get("outboxStatus"))).count());
            facts.put("missingOutbox",outbox.stream().filter(r->r.get("messageId")==null).count());facts.put("outboxFacts",outbox);
            facts.put("missingReceipts",outbox.stream().filter(r->r.get("receiptHash")==null).count());
            facts.put("badReceipts",outbox.stream().filter(r->r.get("receiptHash")!=null&&!Objects.equals(r.get("expectedHash"),r.get("receiptHash"))).count());
            facts.put("outboxRows",outbox.size());facts.put("consumed",outbox.stream().filter(r->r.get("receiptHash")!=null).count());
            for(var e:Map.of("historyOrders","service_order","historyPayments","payment_record","historyReviews","order_review","historyEvents","order_event","refunds","refund_record","files","file_asset").entrySet())facts.put(e.getKey(),q.count("SELECT COUNT(*) FROM "+D+e.getValue()));
            Map<String,Object> metrics;
            try{metrics=performance(q,Objects.toString(identity.get("serverUuid")));}catch(Exception e){metrics=Map.of("available",false,"code","PERFORMANCE_SCHEMA_UNAVAILABLE");}
            c.commit();return new SqlBundle(facts,metrics,q.queries,System.nanoTime()-at);
        }finally{currentSql=null;}
    }
    static Map<String,Object> performance(Sql q,String server)throws Exception{
        Map<String,Object> out=new LinkedHashMap<>();out.put("available",true);out.put("serverUuid",server);
        Map<String,String> names=Map.of("UPTIME","uptime","PERFORMANCE_SCHEMA_DIGEST_LOST","digestLost","INNODB_ROW_LOCK_WAITS","rowLockWaits","INNODB_ROW_LOCK_TIME","rowLockTimeMs");
        for(var r:q.rows("SELECT UPPER(VARIABLE_NAME) AS name,VARIABLE_VALUE AS value FROM performance_schema.global_status WHERE UPPER(VARIABLE_NAME) IN ('UPTIME','PERFORMANCE_SCHEMA_DIGEST_LOST','INNODB_ROW_LOCK_WAITS','INNODB_ROW_LOCK_TIME')"))out.put(names.get(r.get("name")),Long.parseLong(r.get("value").toString()));
        need(out.keySet().containsAll(names.values()),"STATUS_COUNTER_MISSING");
        Map<String,Map<String,Object>> digests=new TreeMap<>();
        var rows=q.rows("SELECT DIGEST AS digest,COUNT_STAR AS count,SUM_TIMER_WAIT AS timerPs,SUM_LOCK_TIME AS lockPs,SUM_ROWS_EXAMINED AS rowsExamined,SUM_ROWS_SENT AS rowsSent,FIRST_SEEN AS firstSeen FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='campuslife_deploy' AND DIGEST IS NOT NULL LIMIT 1001");
        need(rows.size()<1001,"DIGEST_BOUND");
        for(var r:rows){
            String key=r.remove("digest").toString();r.put("firstSeen",r.get("firstSeen").toString());digests.put(key,r);
        }
        out.put("digests",digests);out.put("observerDefaultCatalog","performance_schema");return out;
    }
    static Map<String,String> info(String text){Map<String,String> m=new HashMap<>();for(String s:text.split("\\r?\\n")){int i=s.indexOf(':');if(i>0)m.put(s.substring(0,i),s.substring(i+1));}return m;}
    Map<String,Object> redis(Map<String,Object> sql,Map<String,Object> metrics)throws Exception{
        long at=System.nanoTime();var cs=coupon.sync();var rs=rating.sync();String run=info(cs.info("server")).get("run_id"),ratingRun=info(rs.info("server")).get("run_id");
        need(Objects.equals(run,permit.get("couponRun"))&&Objects.equals(ratingRun,permit.get("ratingRun"))&&!run.equals(ratingRun),"REDIS_SERVER_IDENTITY");
        long generation=n(sql,"generation");List<Map<String,String>> args=new ArrayList<>();
        @SuppressWarnings("unchecked") List<Map<String,Object>> claims=(List<Map<String,Object>>)sql.get("claims");
        for(var r:claims)if(r.get("accepted")!=null)args.add(Map.of("student",r.get("student").toString(),"reservation",r.get("reservation").toString()));
        List<?> values=cs.evalReadOnly(lua,ScriptOutputType.MULTI,new String[]{"coupon:"+namespace+":{"+campaign+"}:control","coupon:"+namespace+":{"+campaign+"}:g:"+generation},Long.toString(generation),run,JSON.writeValueAsString(args));
        need(values.size()==8+args.size()*2&&"HEALTHY".equals(values.getFirst()),"REDIS_EPOCH_UNAVAILABLE");
        Map<String,Object> result=new LinkedHashMap<>();result.put("phase",values.get(1));result.put("generation",Long.parseLong(values.get(2).toString()));result.put("run",values.get(3));result.put("ratingRun",ratingRun);
        for(int i=0;i<4;i++)result.put(List.of("total","free","held","spent").get(i),Long.parseLong(values.get(4+i).toString()));
        long bad=0;int index=8;
        for(var r:claims)if(r.get("accepted")!=null){
            String raw=values.get(index++).toString(),proof=values.get(index++).toString();
            if("PENDING".equals(r.get("status")))continue;
            if(raw.isEmpty()){bad++;continue;}JsonNode record=JSON.readTree(raw);
            String expectedState="SUCCEEDED".equals(r.get("status"))?"SPENT":"CLOSED";
            if(!expectedState.equals(record.path("state").asText())||!r.get("student").toString().equals(record.path("student").asText())
                 ||!r.get("reservation").equals(record.path("id").asText())||!r.get("requestKey").equals(record.path("key").asText())
                 ||!Long.toString(campaign).equals(record.path("campaign").asText())||!Long.toString(generation).equals(record.path("generation").asText())
                 ||("SUCCEEDED".equals(r.get("status"))&&!Objects.toString(r.get("coupon")).equals(proof)))bad++;
        }
        result.put("proofMismatches",bad);
        metrics.put("couponRedis",selected(info(cs.info("stats")),info(cs.info("memory"))));metrics.put("ratingRedis",selected(info(rs.info("stats")),info(rs.info("memory"))));
        need(run.equals(info(cs.info("server")).get("run_id"))&&ratingRun.equals(info(rs.info("server")).get("run_id")),"REDIS_CHANGED_DURING_SAMPLE");
        metrics.put("redisCommands",9);metrics.put("redisElapsedNanos",System.nanoTime()-at);return result;
    }
    static Map<String,Object> selected(Map<String,String> stats,Map<String,String> memory){
        Map<String,Object> out=new LinkedHashMap<>();for(String k:List.of("keyspace_hits","keyspace_misses","total_commands_processed","evicted_keys"))if(stats.containsKey(k))out.put(k,Long.parseLong(stats.get(k)));
        for(String k:List.of("used_memory","used_memory_peak"))if(memory.containsKey(k))out.put(k,Long.parseLong(memory.get(k)));
        out.put("available",out.size()==6);return out;
    }
    Map<String,Object> queues()throws Exception{
        long ready=0,unacked=0;List<Map<String,Object>> rows=new ArrayList<>();
        for(String queue:QUEUES){
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();String path="http://rabbitmq:15672/api/queues/"+vhost+"/"+queue;
            HttpRequest request=HttpRequest.newBuilder(URI.create(path)).header("Authorization",basic).timeout(Duration.ofSeconds(2)).GET().build();
            CompletableFuture<HttpResponse<byte[]>> future=http.sendAsync(request,ignored->new MeasurementHttpTransport.LimitedBody());
            try{
                var response=future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);need(response.statusCode()==200,"BROKER_HTTP_UNAVAILABLE");JsonNode r=JSON.readTree(response.body());
                need(queue.equals(r.path("name").asText())&&vhost.equals(r.path("vhost").asText())&&("rabbit@campuslife-"+permit.get("instanceId")).equals(r.path("node").asText())
                     &&"running".equals(r.path("state").asText())&&"quorum".equals(r.path("type").asText()),"QUEUE_IDENTITY");
                need(r.path("messages_ready").isIntegralNumber()&&r.path("messages_unacknowledged").isIntegralNumber(),"QUEUE_COUNTER_MISSING");
                long a=r.get("messages_ready").longValue(),b=r.get("messages_unacknowledged").longValue();need(a>=0&&b>=0,"QUEUE_COUNTER_INVALID");ready+=a;unacked+=b;rows.add(Map.of("queue",queue,"ready",a,"unacked",b));
            }finally{if(!future.isDone())future.cancel(true);}
        }
        return Map.of("ready",ready,"unacked",unacked,"queues",rows,"managementCountersAreSampled",true);
    }
    Snapshot collect(){
        long at=System.nanoTime();Map<String,Object> before=Map.of(),after=Map.of(),r=Map.of(),q=Map.of();Map<String,Object> metrics=new LinkedHashMap<>();
        try{
            SqlBundle a=sql();before=a.facts;r=redis(before,metrics);q=queues();SqlBundle b=sql();after=b.facts;
            metrics.put("sql",b.metrics);metrics.put("observerSelects",a.queries+b.queries);metrics.put("observerSqlElapsedNanos",a.elapsed+b.elapsed);
            return new Snapshot(at,System.nanoTime(),before,after,r,q,metrics,true,"");
        }catch(Exception error){return new Snapshot(at,System.nanoTime(),before,after,r,q,metrics,false,error instanceof Missing?error.getMessage():"COMPONENT_SAMPLE_UNAVAILABLE");}
    }
    @Override public void close()throws Exception{
        rating.close();coupon.close();ratingClient.shutdown(Duration.ZERO,Duration.ofSeconds(1));couponClient.shutdown(Duration.ZERO,Duration.ofSeconds(1));redisResources.shutdown(0,1,TimeUnit.SECONDS).get(2,TimeUnit.SECONDS);
        http.shutdownNow();need(http.awaitTermination(Duration.ofSeconds(2)),"HTTP_NOT_CLOSED");sqlWorker.shutdownNow();aborts.shutdownNow();need(sqlWorker.awaitTermination(2,TimeUnit.SECONDS)&&aborts.awaitTermination(2,TimeUnit.SECONDS),"SQL_NOT_CLOSED");
    }
}
