package campuslife.measurement;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static campuslife.measurement.MeasurementScenario.*;
import static campuslife.measurement.MeasurementDriver.*;

/** Opt-in controlled endpoints and fault responses; never starts the application or reaches ECS. */
public final class MeasurementDriverChecks {
    static final List<String> checks=new ArrayList<>();
    static final List<Map<String,Object>> runs=new ArrayList<>();
    static MeasurementScenario fixture;static JsonNode fixtureJson;static Path outputRoot;
    static void check(String label,boolean good){if(!good)throw new AssertionError(label);checks.add(label);}
    static void reject(String label,Runnable action){try{action.run();throw new AssertionError(label);}catch(Invalid expected){checks.add(label);}}
    static void rejectCode(String label,String code,Runnable action){try{action.run();throw new AssertionError(label);}catch(Invalid expected){check(label,expected.getMessage().equals(code));}}
    static Map<Long,String> sessions(){Map<Long,String> m=new HashMap<>();for(long id:fixture.students.keySet())m.put(id,"controlled_session_"+id);return m;}
    static ObjectNode object(){return JSON.createObjectNode();}
    static JsonNode success(JsonNode d){ObjectNode o=object();o.put("code","OK");o.set("data",d);return o;}
    static Reply reply(int status,JsonNode d,Map<String,String> headers){return new Reply(status,headers,JSON.writeValueAsBytes(success(d)));}
    static ObjectNode shop(long id,boolean detail){
        long n=id-1000000;boolean haircut=n%2==0;ObjectNode d=object();d.put("id",Long.toString(id));
        d.put("name",(n==997?"稀有":"校园")+(haircut?"理发":"打印")+"模拟店_"+String.format(Locale.ROOT,"%05d",n));
        ObjectNode category=object();category.put("id",haircut?"1":"2");category.put("code",haircut?"HAIRCUT":"PRINT");d.set("category",category);
        d.put("businessEnabled",n%3!=0);d.put("openNow",n%3!=0&&MeasurementScenario.open(Instant.now()));
        JsonNode rating=null;for(JsonNode r:fixtureJson.withArray("ratings"))if(r.get("shop_id").asLong()==id){rating=r;break;}
        if(rating==null){d.putNull("rating");d.put("reviewCount",0);}else{d.set("rating",rating.get("rating_average"));d.set("reviewCount",rating.get("review_count"));}
        if(detail){d.put("campusCode","XINGHAI_MAIN");var hours=d.putArray("businessHours");for(int day=1;day<=7;day++)hours.add(object().put("weekday",day).put("startTime","08:00").put("endTime","22:00"));}
        return d;
    }
    static ObjectNode read(String path,long actor){
        if(path.matches("/api/v1/shops/[0-9]+"))return shop(Long.parseLong(path.substring(path.lastIndexOf('/')+1)),true);
        ObjectNode d=object();d.put("size",20);var items=d.putArray("items");
        if(path.startsWith("/api/v1/orders")){
            Student student=fixture.students.get(actor);d.put("page",1);d.put("total",student.total());d.put("totalPages",(student.total()+19)/20);
            for(long id:student.orders()){
                long shop=id/10000,n=id%10000;Instant start=Instant.parse("2025-01-01T01:00:00Z").plusSeconds((n/20)*86400+(n%20)*1800);
                ObjectNode o=object().put("id",Long.toString(id)).put("orderNo","BL"+shop+"_"+n).put("shopId",Long.toString(shop));
                o.put("orderType","BOOKING").put("status","COMPLETED").put("paymentStatus","PAID").put("payableAmount",3000).put("currency","CNY");
                o.put("serviceName","模拟理发").put("shopName","校园理发模拟店_"+String.format(Locale.ROOT,"%05d",shop-1000000));
                o.put("createdAt",start.minusSeconds(10800).toString()).put("bookingStartAt",start.toString()).put("bookingEndAt",start.plusSeconds(1800).toString()).putNull("printingProgress");items.add(o);
            }
        }else{
            List<Long> ids;long total;int page;
            if(path.contains("categoryId")){ids=fixture.visible.stream().filter(n->n%2==0).limit(20).toList();total=300;page=1;}
            else if(path.contains("keyword")){ids=List.of(1000997L);total=1;page=1;}
            else{ids=fixture.visible.subList(380,400);total=600;page=20;}
            d.put("page",page).put("total",total).put("totalPages",(total+19)/20);for(long id:ids)items.add(shop(id,false));
        }
        return d;
    }
    static final class Claim {
        final String id,campaign;final Instant accepted=Instant.now();int polls;ObjectNode terminal;
        Claim(long actor,String campaign){id=Long.toString(actor+3000000);this.campaign=campaign;}
        synchronized ObjectNode pending(){return base("PENDING").putNull("completedAt").putNull("coupon").putNull("failureCode");}
        ObjectNode base(String status){return object().put("id",id).put("campaignId",campaign).put("status",status).put("acceptedAt",accepted.toString()).put("deadline",accepted.plusSeconds(300).toString());}
        synchronized ObjectNode done(boolean failed){
            if(terminal!=null)return terminal.deepCopy();
            Instant at=accepted.plusNanos(1_000_000);terminal=base(failed?"FAILED":"SUCCEEDED").put("completedAt",at.toString());
            if(failed)return terminal.putNull("coupon").put("failureCode","PROCESSING_EXPIRED").deepCopy();
            terminal.putNull("failureCode");terminal.set("coupon",object().put("id",id).put("campaignId",campaign).put("shopId","1000006")
                .put("name","虚构测量满减").put("thresholdAmount",3000).put("reductionAmount",500).put("sourceCampaignVersion","1").put("state","AVAILABLE")
                .put("claimedAt",at.toString()).put("useStartAt",accepted.minusSeconds(120).toString()).put("useEndAt",accepted.plusSeconds(10800).toString()));
            return terminal.deepCopy();
        }
    }
    static final class Endpoint implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newFixedThreadPool(12);
        final Map<String,Claim> keys=new ConcurrentHashMap<>(),claims=new ConcurrentHashMap<>();final AtomicInteger posts=new AtomicInteger(),redirectHits=new AtomicInteger();
        final Set<Integer> clientPorts=ConcurrentHashMap.newKeySet();
        String mode="normal";
        Endpoint()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);server.setExecutor(executor);server.createContext("/",this::handle);server.start();
        }
        URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
        void send(HttpExchange e,int status,JsonNode body,Map<String,String> headers)throws Exception{
            for(var h:headers.entrySet())e.getResponseHeaders().set(h.getKey(),h.getValue());
            byte[] bytes=JSON.writeValueAsBytes(body);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
        }
        void handle(HttpExchange e){
            try{
                clientPorts.add(e.getRemoteAddress().getPort());
                String path=e.getRequestURI().toString(),token=e.getRequestHeaders().getFirst("Authorization");long actor=token==null?0:Long.parseLong(token.substring(token.lastIndexOf('_')+1));
                if(path.contains("/test/redirect")){e.getResponseHeaders().set("Location",uri()+"/api/v1/test/target");e.sendResponseHeaders(307,-1);return;}
                if(path.contains("/test/target")){redirectHits.incrementAndGet();send(e,200,success(object()),Map.of());return;}
                if(path.contains("/test/large")){byte[] bytes=new byte[MeasurementHttpTransport.MAX_BODY+1];e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);return;}
                if(path.contains("/test/slowbody")){e.sendResponseHeaders(200,2);e.getResponseBody().write('{');e.getResponseBody().flush();Thread.sleep(350);e.getResponseBody().write('}');return;}
                if(mode.equals("slow")){Thread.sleep(350);}
                if(mode.equals("http503")){send(e,503,object().put("code","controlled_DO_NOT_EXPORT"),Map.of());return;}
                if(e.getRequestMethod().equals("POST")){
                    posts.incrementAndGet();JsonNode request=JSON.readTree(e.getRequestBody().readAllBytes());String campaign=request.get("campaignId").asText(),key=e.getRequestHeaders().getFirst("Idempotency-Key");
                    Claim claim;boolean replay;
                    synchronized(keys){claim=keys.get(key);replay=claim!=null;if(!replay&&keys.size()<20){claim=new Claim(actor,campaign);keys.put(key,claim);claims.put(claim.id,claim);}}
                    if(claim==null){send(e,409,object().put("code","COUPON_SOLD_OUT"),Map.of());return;}
                    ObjectNode d=replay?claim.done(false):claim.pending();
                    if(replay&&mode.equals("changedReplay"))d.withObject("coupon").put("id","99999999");
                    if(mode.equals("duplicateId")){d.put("id","1");claims.putIfAbsent("1",claim);}
                    send(e,mode.equals("sync201")?201:replay?200:202,success(d),Map.of("Location","/api/v1/coupon-claims/"+d.get("id").asText(),"Idempotency-Replayed",Boolean.toString(replay)));return;
                }
                if(path.startsWith("/api/v1/coupon-claims/")){
                    Claim c=claims.get(path.substring(path.lastIndexOf('/')+1));
                    if(mode.equals("poll500")){send(e,500,object().put("code","controlled_DO_NOT_EXPORT"),Map.of());return;}
                    ObjectNode d; synchronized(c){c.polls++;d=mode.equals("pending")||c.polls==1?c.pending():c.done(mode.equals("terminalFailed"));}
                    if(mode.equals("duplicateId"))d.put("id","1");
                    send(e,200,success(d),Map.of());return;
                }
                ObjectNode d=read(path,actor);
                if(mode.equals("wrongReadId"))d.put("id","999999");
                send(e,200,success(d),Map.of());
            }catch(Exception ignored){/* Expected cancellation/reset of controlled fault responses. No payload logs. */}
            finally{e.close();}
        }
        @Override public void close(){server.stop(0);executor.shutdownNow();try{executor.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    static final class Counted implements Transport {
        final Transport inner;final AtomicInteger front=new AtomicInteger(),poll=new AtomicInteger(),maxFront=new AtomicInteger(),maxPoll=new AtomicInteger();
        Counted(Transport t){inner=t;}
        @Override public Reply exchange(Call call,long deadline)throws TransportFailure{
            boolean polling=call.method().equals("GET")&&call.path().startsWith("/api/v1/coupon-claims/");AtomicInteger active=polling?poll:front,max=polling?maxPoll:maxFront;
            int value=active.incrementAndGet();max.accumulateAndGet(value,Math::max);
            try{return inner.exchange(call,deadline);}finally{active.decrementAndGet();}
        }
    }
    static Policy fast(){return Policy.controlled(Duration.ofSeconds(2),Duration.ofSeconds(50),Duration.ofSeconds(4));}
    static Task detail(){return fixture.firstAccess().getFirst();}
    static Task claim(int i){return fixture.plan(0,42).stream().filter(t->t.kind()==Kind.CLAIM).sorted(Comparator.comparingLong(Task::student)).toList().get(i);}
    static Result run(Endpoint e,List<Task> tasks,int concurrency,Policy policy,boolean replay)throws Exception{
        try(var http=MeasurementHttpTransport.controlled(e.uri())){return new MeasurementDriver(fixture,http,sessions(),policy,()->false).run(tasks,concurrency,()->replay);}
    }
    static void plans(){
        var p=fixture.plan(0,9876);Map<Kind,Long> expected=Map.of(Kind.DETAIL_HOT,180L,Kind.DETAIL_NORMAL,180L,Kind.CATEGORY,60L,Kind.SEARCH,30L,Kind.DEEP,30L,Kind.ORDERS,60L,Kind.CLAIM,60L);
        check("formal-six-hundred-exact-mixture",p.size()==600&&expected.entrySet().stream().allMatch(e->p.stream().filter(t->t.kind()==e.getKey()).count()==e.getValue()));
        check("seeded-order-reproducible",p.equals(fixture.plan(0,9876))&&!p.equals(fixture.plan(0,9877)));
        Set<String> keys=new HashSet<>();Set<Long> actors=new HashSet<>();for(int n=0;n<3;n++)for(Task t:fixture.plan(n,42))if(t.kind()==Kind.CLAIM){keys.add(t.key());actors.add(t.student());}
        check("three-groups-unique-claim-actors-and-keys",keys.size()==180&&actors.size()==180&&keys.stream().allMatch(k->k.matches("[A-Za-z0-9_-]{1,64}")));
        check("first-access-six-and-read-only-warmup-hundred",fixture.firstAccess().size()==6&&fixture.warmup(0).size()==100&&fixture.warmup(0).stream().noneMatch(t->t.kind()==Kind.CLAIM));
        var pilot=fixtureJson.deepCopy();((ObjectNode)pilot).put("profile","PILOT");var campaigns=((ObjectNode)pilot).putArray("campaigns");campaigns.add(object().put("id",2000001).put("total_quantity",2));
        var small=new MeasurementScenario(pilot,1,2);var tasks=small.plan(0,42);
        check("pilot-sixty-exact-mixture",tasks.size()==60&&tasks.stream().filter(t->t.kind()==Kind.CLAIM).count()==6&&tasks.stream().filter(t->t.kind()==Kind.ORDERS).count()==6&&tasks.stream().filter(t->t.kind()==Kind.DETAIL_HOT||t.kind()==Kind.DETAIL_NORMAL).count()==36);
        Policy pcy=Policy.fixed();check("fixed-three-ninety-sixty-and-poll-rate",pcy.request().toSeconds()==3&&pcy.foreground().toSeconds()==90&&pcy.observation().toSeconds()==60&&pcy.perId().toMillis()==250&&pcy.globalPoll().toMillis()==25);
        reject("refuse-altered-fixed-policy",()->new Policy("FIXED",Duration.ofSeconds(4),pcy.foreground(),pcy.observation(),pcy.perId(),pcy.globalPoll()));
    }
    static void happy()throws Exception{
        for(int concurrency:List.of(1,5,10))try(var server=new Endpoint();var http=MeasurementHttpTransport.controlled(server.uri())){
            Counted counted=new Counted(http);var driver=new MeasurementDriver(fixture,counted,sessions(),fast(),()->false);
            Result r=driver.run(fixture.plan(0,4200+concurrency),concurrency,()->true);
            check("mixed-http-c"+concurrency,r.workersClosed()&&!r.stopped()&&((Number)r.summary().get("sent")).intValue()==600&&((Number)r.summary().get("observedSucceeded")).intValue()==20
                    &&((Number)r.summary().get("censored")).intValue()==0&&r.samples().stream().filter(s->s.phase().equals("FOREGROUND")&&s.outcome().equals("EXPECTED_SOLD_OUT")).count()==40);
            check("concurrency-c"+concurrency+"-and-single-poller",counted.maxFront.get()<=concurrency&&counted.maxPoll.get()==1&&counted.front.get()==0&&counted.poll.get()==0);
            check("replay-outside-foreground-c"+concurrency,server.posts.get()==66&&r.samples().stream().filter(s->s.phase().equals("REPLAY")&&s.outcome().equals("REPLAY_OK")).count()==6
                    &&((Number)r.summary().get("planned")).intValue()==600);
            var plan=fixture.plan(0,4200+concurrency);Path artifact=outputRoot.resolve("controlled-c"+concurrency);
            var hashes=MeasurementDriver.writeArtifacts(artifact,fixture.caseId,0,4200+concurrency,plan,fast(),r);
            String csv=Files.readString(artifact.resolve("requests.csv"));
            check("raw-json-csv-and-manifest-c"+concurrency,hashes.size()==4&&csv.lines().count()==r.samples().size()+1
                &&!Files.readString(artifact.resolve("result.json")).contains("controlled_session_")&&!Files.exists(artifact.resolve("result.pending")));
            boolean refused=false;try{MeasurementDriver.writeArtifacts(artifact,fixture.caseId,0,4200+concurrency,plan,fast(),r);}catch(FileAlreadyExistsException expected){refused=true;}
            check("artifact-no-overwrite-c"+concurrency,refused);
            runs.add(Map.of("controlledConcurrency",concurrency,"foregroundRequests",600,"observedSucceeded",20,"expectedSoldOut",40,"replays",6,"maxForegroundInFlight",counted.maxFront.get()));
        }
        try(var server=new Endpoint();var http=MeasurementHttpTransport.controlled(server.uri())){
            var a=new MeasurementDriver(fixture,http,sessions(),fast(),()->false).readOnly("CACHE_FIRST",fixture.firstAccess());
            var b=new MeasurementDriver(fixture,http,sessions(),fast(),()->false).readOnly("WARMUP",fixture.warmup(0));
            check("separate-first-access-and-warmup-http",a.size()==6&&b.size()==100&&server.posts.get()==0&&b.stream().allMatch(s->s.outcome().equals("OK")));
            check("http11-connections-reused",server.clientPorts.size()<6);
        }
    }
    static void validation(){
        Instant now=Instant.parse("2026-11-07T04:00:00Z");Task t=detail();ObjectNode original=shop(t.target(),true);original.put("openNow",false);
        ObjectNode bad=original.deepCopy();bad.put("reviewCount",1);rejectCode("reject-wrong-rating-count","REVIEW_COUNT",()->fixture.validateRead(t,bad,now,now));
        ObjectNode wrong=original.deepCopy();wrong.put("rating",0);rejectCode("reject-wrong-rating-value","RATING_VALUE",()->fixture.validateRead(t,wrong,now,now));
        ObjectNode order=read("/api/v1/orders?page=1&size=20",900001);Task own=new Task(0,Kind.ORDERS,0,900001,"");
        ObjectNode cross=read("/api/v1/orders?page=1&size=20",900002);rejectCode("reject-cross-student-order-page","ORDER_IDENTITY",()->fixture.validateRead(own,cross,now,now));
        ((ObjectNode)order.withArray("items").get(0)).put("payableAmount",2999);rejectCode("reject-wrong-frozen-order-amount","ORDER_SUMMARY",()->fixture.validateRead(own,order,now,now));
        ObjectNode page=read("/api/v1/shops?page=20&size=20",0);JsonNode first=page.withArray("items").get(0);page.withArray("items").set(0,page.withArray("items").get(1));page.withArray("items").set(1,first);
        rejectCode("reject-unsorted-public-page","SHOP_IDENTITY",()->fixture.validateRead(new Task(0,Kind.DEEP,0,0,""),page,now,now));
        ObjectNode hours=original.deepCopy();hours.withArray("businessHours").remove(0);rejectCode("reject-incomplete-business-hours","HOUR_COUNT",()->fixture.validateRead(t,hours,now,now));
    }
    static void failures()throws Exception{
        for(String mode:List.of("http503","wrongReadId"))try(var e=new Endpoint()){
            e.mode=mode;var tasks=new ArrayList<Task>();for(int n=0;n<10;n++)tasks.add(new Task(n,Kind.DETAIL_HOT,1000006,0,""));
            Result r=run(e,tasks,1,fast(),false);
            check("stop-and-preserve-unsent-"+mode,r.stopped()&&r.samples().stream().filter(s->s.outcome().equals("UNSENT")).count()==9);
            check("redact-payloads-"+mode,!JSON.writeValueAsString(r).contains("DO_NOT_EXPORT")&&!new Call("GET","/api/v1/orders","secret-body","secret-token","").toString().contains("secret-token"));
        }
        try(var e=new Endpoint()){
            e.mode="sync201";Result r=run(e,List.of(claim(0)),1,fast(),false);
            check("refuse-sync-fallback-without-retrying-post",r.stopped()&&e.posts.get()==1&&((Number)r.summary().get("unknownPosts")).intValue()==1&&r.observations().isEmpty());
        }
        try(var e=new Endpoint()){
            e.mode="duplicateId";Result r=run(e,List.of(claim(0),claim(1)),1,fast(),false);
            check("duplicate-admission-id-stops",r.stopped()&&r.samples().stream().anyMatch(s->s.code().equals("DUPLICATE_CLAIM_ID")));
        }
        try(var e=new Endpoint()){
            e.mode="changedReplay";Result r=run(e,List.of(claim(0)),1,fast(),true);
            check("changed-replay-coupon-rejected",r.stopped()&&r.stopCode().equals("REPLAY_ERROR")&&e.posts.get()==2);
        }
        try(var e=new Endpoint()){
            e.mode="pending";Result r=run(e,List.of(claim(0)),1,Policy.controlled(Duration.ofMillis(200),Duration.ofSeconds(2),Duration.ofMillis(90)),true);
            check("pending-right-censored-and-no-replay",!r.stopped()&&r.observations().size()==1&&r.observations().getFirst().observedNanos()==null
                    &&r.observations().getFirst().lowerBoundNanos()>=TimeUnit.MILLISECONDS.toNanos(90)&&e.posts.get()==1
                    &&((Number)((Map<?,?>)r.summary().get("successfulSubsetObservedLatency")).get("count")).intValue()==0);
        }
        try(var e=new Endpoint()){
            e.mode="poll500";Result r=run(e,List.of(claim(0)),1,fast(),false);
            check("poll-error-preserves-accepted-request",r.stopped()&&r.stopCode().equals("POLL_ERROR")&&r.observations().size()==1&&r.observations().getFirst().observedNanos()==null);
        }
        try(var e=new Endpoint()){
            e.mode="terminalFailed";Result r=run(e,List.of(claim(0)),1,fast(),false);
            check("failed-terminal-counted-separately",!r.stopped()&&((Number)r.summary().get("observedFailed")).intValue()==1&&((Number)r.summary().get("observedSucceeded")).intValue()==0);
        }
        try(var e=new Endpoint()){
            e.mode="slow";List<Task> tasks=new ArrayList<>();for(int n=0;n<10;n++)tasks.add(new Task(n,Kind.DETAIL_HOT,1000006,0,""));
            Result r=run(e,tasks,1,Policy.controlled(Duration.ofMillis(200),Duration.ofMillis(90),Duration.ofMillis(60)),false);
            check("foreground-cutoff-keeps-unsent",!r.stopped()&&r.samples().stream().anyMatch(s->s.outcome().equals("FOREGROUND_CUTOFF"))&&((Number)r.summary().get("unsent")).intValue()==9);
        }
        try(var e=new Endpoint();var http=MeasurementHttpTransport.controlled(e.uri())){
            Result r=new MeasurementDriver(fixture,http,sessions(),fast(),()->true).run(fixture.plan(0,42),5,()->false);
            check("external-stop-sends-nothing",r.stopped()&&((Number)r.summary().get("sent")).intValue()==0&&((Number)r.summary().get("unsent")).intValue()==600);
        }
    }
    static void httpAndRates()throws Exception{
        try(var e=new Endpoint();var http=MeasurementHttpTransport.controlled(e.uri())){
            boolean timed=false;long at=System.nanoTime();try{http.exchange(new Call("GET","/api/v1/test/slowbody","","",""),at+TimeUnit.MILLISECONDS.toNanos(90));}catch(TransportFailure error){timed=error.code.equals("HTTP_TIMEOUT");}
            check("http-deadline-includes-stalled-body",timed&&System.nanoTime()-at<TimeUnit.SECONDS.toNanos(1));
            boolean large=false;try{http.exchange(new Call("GET","/api/v1/test/large","","",""),System.nanoTime()+TimeUnit.SECONDS.toNanos(2));}catch(TransportFailure error){large=error.code.equals("BODY_LIMIT");}
            check("http-body-memory-is-bounded",large);
            Reply redirect=http.exchange(new Call("GET","/api/v1/test/redirect","","controlled_session_900001",""),System.nanoTime()+TimeUnit.SECONDS.toNanos(2));
            check("http-redirect-does-not-forward-credentials",redirect.status()==307&&e.redirectHits.get()==0);
        }
        try(var e=new Endpoint()){
            Policy p=new Policy("CONTROLLED_TEST",Duration.ofSeconds(2),Duration.ofSeconds(4),Duration.ofSeconds(2),Duration.ofMillis(250),Duration.ofMillis(25));
            Result r=run(e,List.of(claim(0),claim(1)),1,p,false);var polls=r.samples().stream().filter(s->s.phase().equals("POLL")).sorted(Comparator.comparingLong(Sample::startNanos)).toList();
            boolean global=true;for(int n=1;n<polls.size();n++)global &= polls.get(n).startNanos()-polls.get(n-1).startNanos()>=TimeUnit.MILLISECONDS.toNanos(25);
            check("fixed-global-poll-spacing-forty-per-second",global&&polls.size()==4);
            boolean perId=true;for(Observation o:r.observations())perId &= o.polls()==2&&o.maxPollStartGapNanos()>=TimeUnit.MILLISECONDS.toNanos(250);
            check("fixed-per-id-spacing-and-fair-rotation",perId&&polls.get(0).student()!=polls.get(1).student());
        }
        Claim state=new Claim(900001,"2000001");
        Transport late=(call,deadline)->{
            if(call.method().equals("POST"))return reply(202,state.pending(),Map.of("location","/api/v1/coupon-claims/"+state.id,"idempotency-replayed","false"));
            try{Thread.sleep(120);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new TransportFailure("INTERRUPTED");}
            return reply(200,state.done(false),Map.of());
        };
        Result r=new MeasurementDriver(fixture,late,sessions(),Policy.controlled(Duration.ofMillis(250),Duration.ofSeconds(1),Duration.ofMillis(30)),()->false).run(List.of(claim(0)),1,()->false);
        check("late-terminal-excluded-after-observation-cutoff",!r.stopped()&&r.observations().getFirst().observedNanos()==null&&((Number)r.summary().get("observedSucceeded")).intValue()==0);
        try(var e=new Endpoint()){
            Result gated=run(e,List.of(claim(0)),1,fast(),false);
            check("no-replay-without-verified-drain-gate",!gated.stopped()&&Boolean.TRUE.equals(gated.summary().get("replayDrainGateChecked"))&&Boolean.FALSE.equals(gated.summary().get("replayDrainGateAllowed"))&&e.posts.get()==1);
        }
    }
    static void statistics(){
        var d=distribution(List.of(10L,20L,30L,40L));check("nearest-rank-small-sample",d.get("p50Nanos").equals(20L)&&d.get("p95Nanos").equals(40L));
        check("empty-distribution-no-invented-percentiles",distribution(List.of()).get("p95Nanos")==null);
        List<Sample> rows=List.of(new Sample("FOREGROUND",0,"CLAIM",1,0,100,202,"ACCEPTED",""),
                new Sample("FOREGROUND",1,"CLAIM",2,100,1,409,"EXPECTED_SOLD_OUT","COUPON_SOLD_OUT"),
                new Sample("FOREGROUND",2,"CLAIM",3,101,0,0,"UNSENT","FOREGROUND_DEADLINE"),
                new Sample("POLL",0,"CLAIM",1,1000,200,200,"SUCCEEDED",""),new Sample("REPLAY",0,"CLAIM",1,2000,100,200,"REPLAY_OK",""));
        var s=summary(rows,List.of(new Observation("1",1,"PENDING","","OBSERVATION_DEADLINE",3000,null,2,250)));
        check("latencies-separated-and-zero-unsent-excluded",((Map<?,?>)s.get("latencyGroups")).size()==4&&((Number)s.get("sent")).intValue()==2&&((Number)s.get("unsent")).intValue()==1);
        check("actual-foreground-window-excludes-poll-replay",((Number)s.get("foregroundWindowNanos")).longValue()==101&&((Number)s.get("foregroundCompletionPerSecond")).doubleValue()==2e9/101);
        check("censor-and-http202-not-issued-coupons",((Number)s.get("http202")).intValue()==1&&((Number)s.get("censored")).intValue()==1&&Boolean.TRUE.equals(s.get("sqlActualIssuanceNotMeasured")));
    }
    public static void main(String[] args)throws Exception{
        long started=System.nanoTime();Path output=Path.of(args[1]);Files.createDirectories(output);outputRoot=output;boolean passed=false;String failure="";
        try{fixtureJson=JSON.readTree(Files.readString(Path.of(args[0])));fixture=new MeasurementScenario(fixtureJson,1,2);plans();validation();statistics();happy();failures();httpAndRates();passed=true;}
        catch(Throwable error){failure=error instanceof AssertionError?error.getMessage():error instanceof Invalid?error.getMessage():error.getClass().getSimpleName();}
        Map<String,Object> result=new LinkedHashMap<>();result.put("task","M06.2-B.2-B");result.put("passed",passed);result.put("checks",checks);result.put("failureCode",failure);
        result.put("controlledRuns",runs);result.put("elapsedSeconds",(System.nanoTime()-started)/1e9);result.put("businessServicesStarted",false);result.put("databaseConnected",false);result.put("java",System.getProperty("java.version"));
        result.put("performanceConclusion",false);Files.writeString(output.resolve("result.json"),JSON.writeValueAsString(result));System.out.println(JSON.writeValueAsString(result));if(!passed)System.exit(1);
    }
}
