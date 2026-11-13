package campuslife.measurement;

import java.time.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import tools.jackson.databind.JsonNode;
import static campuslife.measurement.MeasurementScenario.*;

/** One segment only. No service startup, SQL truth inference, implicit HTTP retry or next-segment loop. */
final class MeasurementDriver {
    record Policy(String mode,Duration request,Duration foreground,Duration observation,Duration perId,Duration globalPoll){
        static Policy fixed(){return new Policy("FIXED",Duration.ofSeconds(3),Duration.ofSeconds(90),Duration.ofSeconds(60),Duration.ofMillis(250),Duration.ofMillis(25));}
        static Policy controlled(Duration request,Duration foreground,Duration observation){return new Policy("CONTROLLED_TEST",request,foreground,observation,Duration.ofMillis(25),Duration.ofMillis(5));}
        Policy{
            need(request.isPositive()&&foreground.isPositive()&&observation.isPositive()&&perId.isPositive()&&globalPoll.isPositive(),"POLICY");
            need(Set.of("FIXED","CONTROLLED_TEST").contains(mode),"POLICY_MODE");
            if(mode.equals("FIXED"))need(request.equals(Duration.ofSeconds(3))&&foreground.equals(Duration.ofSeconds(90))&&observation.equals(Duration.ofSeconds(60))
                &&perId.equals(Duration.ofMillis(250))&&globalPoll.equals(Duration.ofMillis(25)),"FIXED_POLICY_CHANGED");
        }
    }
    record Call(String method,String path,String body,String token,String key){
        @Override public String toString(){return "Call["+method+",credentials-and-body-redacted]";}
    }
    record Reply(int status,Map<String,String> headers,byte[] body){@Override public String toString(){return "Reply["+status+",body-redacted]";}}
    interface Transport {Reply exchange(Call call,long absoluteDeadline)throws TransportFailure;}
    static final class TransportFailure extends Exception {final String code;TransportFailure(String code){this.code=code;}@Override public String getMessage(){return code;}}
    record Sample(String phase,int ordinal,String kind,long student,long startNanos,long elapsedNanos,int status,String outcome,String code){ }
    record Observation(String claimId,long student,String state,String failureCode,String censorReason,long lowerBoundNanos,Long observedNanos,int polls,long maxPollStartGapNanos){ }
    record Result(String policy,int concurrency,List<Sample> samples,List<Observation> observations,Map<String,Object> summary,boolean stopped,String stopCode,boolean workersClosed) {}
    static final class Pending {
        final Task task;final String id;final Instant accepted,deadline;final long firstSent;long due,lastPoll,maxGap;int polls;String state="PENDING",failureCode="";
        Long observed;JsonNode terminalSnapshot;Pending(Task task,String id,Instant accepted,Instant deadline,long firstSent){this.task=task;this.id=id;this.accepted=accepted;this.deadline=deadline;this.firstSent=firstSent;}
    }
    final MeasurementScenario scenario;final Transport transport;final Map<Long,String> sessions;
    final Policy policy;final BooleanSupplier externalStop;
    final AtomicBoolean stopped=new AtomicBoolean();final AtomicReference<String> stopCode=new AtomicReference<>("");
    final List<Sample> samples=Collections.synchronizedList(new ArrayList<>());
    final ConcurrentLinkedQueue<Pending> arriving=new ConcurrentLinkedQueue<>();
    final ConcurrentMap<String,Pending> pending=new ConcurrentHashMap<>();
    final Set<String> couponIds=ConcurrentHashMap.newKeySet();final AtomicBoolean foregroundDone=new AtomicBoolean();
    final AtomicLong observationEnd=new AtomicLong(Long.MAX_VALUE);long origin;
    MeasurementDriver(MeasurementScenario scenario,Transport transport,Map<Long,String> sessions,Policy policy,BooleanSupplier stop){
        this.scenario=scenario;this.transport=transport;this.sessions=Map.copyOf(sessions);this.policy=policy;this.externalStop=stop;
        for(var entry:sessions.entrySet())need(scenario.students.containsKey(entry.getKey())&&entry.getValue()!=null&&entry.getValue().matches("[A-Za-z0-9_.-]{1,4096}"),"SESSION_INPUT");
    }
    boolean stop(){if(!stopped.get()&&externalStop.getAsBoolean())fail("EXTERNAL_STOP");return stopped.get();}
    void fail(String code){stopCode.compareAndSet("",code);stopped.set(true);}
    String token(long student){if(student==0)return "";String token=sessions.get(student);need(token!=null,"MISSING_SESSION");return token;}
    static JsonNode data(Reply r){
        try{JsonNode root=JSON.readTree(r.body);need("OK".equals(text(root.get("code")))&&root.get("data")!=null&&!root.get("data").isNull(),"API_ENVELOPE");return root.get("data");}
        catch(Invalid e){throw e;}catch(Exception e){throw new Invalid("INVALID_JSON");}
    }
    Call call(Task task){return new Call(task.kind()==Kind.CLAIM?"POST":"GET",task.path(scenario.haircutCategory),
            task.kind()==Kind.CLAIM?"{\"campaignId\":\""+task.target()+"\"}":"",token(task.student()),task.key());}
    void sample(String phase,Task t,long at,int status,String outcome,String code){samples.add(new Sample(phase,t.ordinal(),t.kind().name(),t.student(),Math.max(0,at-origin),System.nanoTime()-at,status,outcome,code));}
    void unsent(String phase,Task t,String reason){samples.add(new Sample(phase,t.ordinal(),t.kind().name(),t.student(),Math.max(0,System.nanoTime()-origin),0,0,"UNSENT",reason));}
    boolean soldOut(Reply r){
        if(r.status!=409)return false;
        try{return "COUPON_SOLD_OUT".equals(text(JSON.readTree(r.body).get("code")));}
        catch(Exception e){return false;}
    }
    Pending admitted(Task t,Reply reply,JsonNode d,long at){
        String id=text(d.get("id"));MeasurementScenario.id(d.get("id"));
        need(MeasurementScenario.id(d.get("campaignId"))==t.target()&&text(d.get("status")).equals("PENDING"),"ADMISSION_IDENTITY");
        need(("/api/v1/coupon-claims/"+id).equals(reply.headers.get("location"))&&"false".equals(reply.headers.get("idempotency-replayed")),"ADMISSION_HEADERS");
        Instant accepted=instant(d.get("acceptedAt")),deadline=instant(d.get("deadline"));
        need(deadline.isAfter(accepted)&&!deadline.isAfter(accepted.plusSeconds(300)),"CLAIM_DEADLINE");
        nil(d.get("completedAt"));nil(d.get("coupon"));nil(d.get("failureCode"));
        Pending value=new Pending(t,id,accepted,deadline,at);
        need(pending.putIfAbsent(id,value)==null,"DUPLICATE_CLAIM_ID");arriving.add(value);return value;
    }
    void execute(String phase,Task t,long end){
        if(stop()||System.nanoTime()>=end){unsent(phase,t,stopped.get()?stopCode.get():"FOREGROUND_DEADLINE");return;}
        long at=System.nanoTime();Instant wall=Instant.now();int status=0;
        try{
            Reply r=transport.exchange(call(t),Math.min(end,at+policy.request.toNanos()));status=r.status;
            if(t.kind()==Kind.CLAIM){
                if(soldOut(r)){sample(phase,t,at,status,"EXPECTED_SOLD_OUT","COUPON_SOLD_OUT");return;}
                need(status==202,"UNEXPECTED_CLAIM_HTTP");admitted(t,r,data(r),at);
            }else{need(status==200,"UNEXPECTED_READ_HTTP");scenario.validateRead(t,data(r),wall,Instant.now());}
            sample(phase,t,at,status,t.kind()==Kind.CLAIM?"ACCEPTED":"OK","");
        }catch(TransportFailure e){
            if(e.code.equals("DEADLINE_BEFORE_SEND")){unsent(phase,t,"FOREGROUND_DEADLINE");return;}
            if(e.code.equals("HTTP_TIMEOUT")&&System.nanoTime()>=end&&end<=at+policy.request.toNanos()){
                sample(phase,t,at,status,t.kind()==Kind.CLAIM?"UNKNOWN_POST":"FOREGROUND_CUTOFF","FOREGROUND_DEADLINE");return;
            }
            sample(phase,t,at,status,t.kind()==Kind.CLAIM?"UNKNOWN_POST":"TRANSPORT_ERROR",e.code);fail(e.code);
        }catch(Exception e){sample(phase,t,at,status,t.kind()==Kind.CLAIM?"UNKNOWN_POST":"INVALID_RESPONSE",e instanceof Invalid?e.getMessage():"VALIDATION_ERROR");fail("INVALID_RESPONSE");}
    }
    String validateClaim(Pending p,JsonNode d){
        need(text(d.get("id")).equals(p.id)&&MeasurementScenario.id(d.get("campaignId"))==p.task.target(),"POLL_IDENTITY");
        need(instant(d.get("acceptedAt")).equals(p.accepted)&&instant(d.get("deadline")).equals(p.deadline),"POLL_TIMES");
        String state=text(d.get("status"));need(Set.of("PENDING","SUCCEEDED","FAILED").contains(state),"CLAIM_STATE");
        if(state.equals("PENDING")){nil(d.get("completedAt"));nil(d.get("coupon"));nil(d.get("failureCode"));return state;}
        Instant complete=instant(d.get("completedAt"));need(!complete.isBefore(p.accepted),"COMPLETION_TIME");
        if(state.equals("FAILED")){
            nil(d.get("coupon"));String code=text(d.get("failureCode"));
            need(Set.of("PROCESSING_EXPIRED","QUALIFICATION_CHANGED","ALREADY_CLAIMED","SOLD_OUT").contains(code),"FAILURE_CODE");p.failureCode=code;
        }
        else{
            nil(d.get("failureCode"));JsonNode c=d.get("coupon");String coupon=Long.toString(MeasurementScenario.id(c.get("id")));
            need(MeasurementScenario.id(c.get("campaignId"))==p.task.target()&&MeasurementScenario.id(c.get("shopId"))==1000006
                    &&number(c.get("thresholdAmount"))==3000&&number(c.get("reductionAmount"))==500
                    &&text(c.get("name")).equals("虚构测量满减")&&text(c.get("sourceCampaignVersion")).equals("1")&&text(c.get("state")).equals("AVAILABLE"),"COUPON_SNAPSHOT");
            Instant claimed=instant(c.get("claimedAt")),useStart=instant(c.get("useStartAt")),useEnd=instant(c.get("useEndAt"));
            need(!claimed.isBefore(p.accepted)&&!claimed.isAfter(complete)&&!claimed.isBefore(useStart)&&claimed.isBefore(useEnd),"COUPON_TIMES");
            if(p.state.equals("PENDING"))need(couponIds.add(coupon),"DUPLICATE_COUPON_ID");
        }
        if(p.state.equals("PENDING"))p.terminalSnapshot=d.deepCopy();else need(d.equals(p.terminalSnapshot),"REPLAY_FACT_CHANGED");
        return state;
    }
    void poll(){
        ArrayDeque<Pending> queue=new ArrayDeque<>();long global=0;
        try{
            while(!stop()){
                Pending incoming;while((incoming=arriving.poll())!=null)queue.addLast(incoming);
                if(foregroundDone.get()&&queue.isEmpty()&&arriving.isEmpty())return;
                long now=System.nanoTime(),end=observationEnd.get();if(now>=end)return;
                Pending chosen=null;int size=queue.size();
                for(int n=0;n<size;n++){
                    Pending p=queue.removeFirst();
                    if(chosen==null&&now>=p.due&&now>=global)chosen=p;else queue.addLast(p);
                }
                if(chosen==null){TimeUnit.MILLISECONDS.sleep(1);continue;}
                Pending p=chosen;long at=System.nanoTime();global=at+policy.globalPoll.toNanos();
                if(p.lastPoll!=0)p.maxGap=Math.max(p.maxGap,at-p.lastPoll);p.lastPoll=at;p.polls++;
                int status=0;
                try{
                    Reply r=transport.exchange(new Call("GET","/api/v1/coupon-claims/"+p.id,"",token(p.task.student()),""),Math.min(end,at+policy.request.toNanos()));status=r.status;
                    if(System.nanoTime()>=observationEnd.get()){sample("POLL",p.task,at,status,"OBSERVATION_CUTOFF","");return;}
                    need(status==200,"UNEXPECTED_POLL_HTTP");String state=validateClaim(p,data(r));
                    sample("POLL",p.task,at,status,state,"");p.state=state;
                    if(state.equals("PENDING")){p.due=at+policy.perId.toNanos();queue.addLast(p);}
                    else p.observed=System.nanoTime()-p.firstSent;
                }catch(Exception e){
                    if(System.nanoTime()>=observationEnd.get()){sample("POLL",p.task,at,status,"OBSERVATION_CUTOFF","");return;}
                    sample("POLL",p.task,at,status,"POLL_ERROR",e instanceof TransportFailure t?t.code:"INVALID_POLL");fail("POLL_ERROR");return;
                }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();fail("POLL_INTERRUPTED");}
        catch(Exception e){fail("POLL_DRIVER_ERROR");}
    }
    List<Sample> readOnly(String phase,List<Task> tasks){
        need(samples.isEmpty()&&Set.of("CACHE_FIRST","WARMUP").contains(phase)&&tasks.stream().noneMatch(t->t.kind()==Kind.CLAIM),"READ_ONLY_PHASE");
        origin=System.nanoTime();for(Task t:tasks)execute(phase,t,Long.MAX_VALUE);return List.copyOf(samples);
    }
    Result run(List<Task> tasks,int concurrency,BooleanSupplier verifiedDrain){
        need(Set.of(1,5,10).contains(concurrency)&&samples.isEmpty()&&pending.isEmpty(),"ONE_SEGMENT_ONLY");
        need(tasks.size()<=600&&tasks.stream().map(Task::ordinal).distinct().count()==tasks.size(),"BOUNDED_PLAN");
        for(Task t:tasks)token(t.student());
        origin=System.nanoTime();long end=origin+policy.foreground.toNanos();AtomicInteger next=new AtomicInteger();
        ExecutorService workers=Executors.newFixedThreadPool(concurrency,Thread.ofPlatform().name("measurement-foreground-",0).factory());
        Thread poller=Thread.ofPlatform().name("measurement-poll").start(this::poll);List<Future<?>> jobs=new ArrayList<>();
        try{
            for(int n=0;n<concurrency;n++)jobs.add(workers.submit(()->{
                while(!stop()&&System.nanoTime()<end){int i=next.getAndIncrement();if(i>=tasks.size())return;execute("FOREGROUND",tasks.get(i),end);}
            }));
            for(Future<?> job:jobs)job.get(Math.max(1,end-System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),TimeUnit.NANOSECONDS);
        }catch(Exception e){fail("FOREGROUND_DRIVER_ERROR");for(Future<?> f:jobs)f.cancel(true);}
        finally{workers.shutdownNow();}
        boolean closed=false;
        try{closed=workers.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();fail("FOREGROUND_INTERRUPTED");}
        if(!closed)fail("WORKERS_NOT_CLOSED");
        Set<Integer> recorded=new HashSet<>();synchronized(samples){for(Sample s:samples)if(s.phase.equals("FOREGROUND"))recorded.add(s.ordinal);}
        for(Task t:tasks)if(!recorded.contains(t.ordinal()))unsent("FOREGROUND",t,stopped.get()?stopCode.get():"FOREGROUND_DEADLINE");
        long foregroundFinished=System.nanoTime();observationEnd.set(foregroundFinished+policy.observation.toNanos());foregroundDone.set(true);
        try{poller.join(policy.observation.toMillis()+policy.request.toMillis()+1000);}
        catch(InterruptedException e){Thread.currentThread().interrupt();fail("POLL_JOIN_INTERRUPTED");}
        if(poller.isAlive()){fail("POLL_NOT_CLOSED");poller.interrupt();try{poller.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        closed=closed&&!poller.isAlive();long finish=System.nanoTime();
        List<Observation> observations=new ArrayList<>();
        for(Pending p:pending.values())observations.add(new Observation(p.id,p.task.student(),p.state,p.failureCode,p.observed==null?(stopped.get()?stopCode.get():"OBSERVATION_DEADLINE"):"",
                (p.observed==null?Math.min(finish,observationEnd.get())-p.firstSent:p.observed),p.observed,p.polls,p.maxGap));
        observations.sort(Comparator.comparing(Observation::claimId));
        boolean unknownPost;synchronized(samples){unknownPost=samples.stream().anyMatch(s->s.phase.equals("FOREGROUND")&&s.outcome.equals("UNKNOWN_POST"));}
        boolean gateChecked=false,gateAllowed=false;
        if(!stop()&&!unknownPost&&observations.stream().noneMatch(o->o.observedNanos==null)){
            gateChecked=true;
            try{gateAllowed=verifiedDrain.getAsBoolean();if(gateAllowed)replay();}catch(Exception error){fail("DRAIN_CHECK_ERROR");}
        }
        List<Sample> copy; synchronized(samples){copy=List.copyOf(samples);}
        Map<String,Object> report=summary(copy,observations);report.put("replayDrainGateChecked",gateChecked);report.put("replayDrainGateAllowed",gateAllowed);
        return new Result(policy.mode,concurrency,copy,List.copyOf(observations),report,stopped.get(),stopCode.get(),closed);
    }
    void replay(){
        pending.values().stream().sorted(Comparator.comparing(p->p.id)).limit(6).forEach(p->{
            if(stop())return;long at=System.nanoTime();int status=0;
            try{
                Reply r=transport.exchange(call(p.task),at+policy.request.toNanos());status=r.status;
                need(status==200&&"true".equals(r.headers.get("idempotency-replayed"))&&("/api/v1/coupon-claims/"+p.id).equals(r.headers.get("location")),"REPLAY_HEADERS");
                need(validateClaim(p,data(r)).equals(p.state),"REPLAY_FACT");sample("REPLAY",p.task,at,status,"REPLAY_OK","");
            }catch(Exception e){sample("REPLAY",p.task,at,status,"REPLAY_ERROR","REPLAY_INVALID");fail("REPLAY_ERROR");}
        });
    }
    static Map<String,Object> distribution(List<Long> durations){
        Map<String,Object> result=new LinkedHashMap<>();List<Long> a=durations.stream().sorted().toList();result.put("count",a.size());
        result.put("p50Nanos",a.isEmpty()?null:a.get((int)Math.ceil(a.size()*.50)-1));result.put("p95Nanos",a.isEmpty()?null:a.get((int)Math.ceil(a.size()*.95)-1));
        result.put("maxNanos",a.isEmpty()?null:a.getLast());return result;
    }
    static Map<String,String> writeArtifacts(Path folder,String caseId,int segment,long seed,List<Task> tasks,Policy policy,Result result)throws Exception{
        need(caseId.matches("case-[a-f0-9]{12,32}")&&segment>=0&&segment<3&&policy.mode.equals(result.policy),"ARTIFACT_IDENTITY");
        need(tasks.size()==result.samples.stream().filter(s->s.phase.equals("FOREGROUND")).count(),"ARTIFACT_PLAN");
        Files.createDirectory(folder); // Do not overwrite an earlier sample set.
        Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("caseId",caseId);manifest.put("segment",segment);manifest.put("seed",seed);
        manifest.put("concurrency",result.concurrency);manifest.put("policyMode",policy.mode);manifest.put("requestNanos",policy.request.toNanos());
        manifest.put("foregroundLimitNanos",policy.foreground.toNanos());manifest.put("observationLimitNanos",policy.observation.toNanos());
        manifest.put("perIdPollNanos",policy.perId.toNanos());manifest.put("globalPollSpacingNanos",policy.globalPoll.toNanos());manifest.put("tasks",tasks);
        Files.writeString(folder.resolve("manifest.json"),JSON.writeValueAsString(manifest),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        StringBuilder rows=new StringBuilder("phase,ordinal,kind,student,startNanos,elapsedNanos,status,outcome,code\n");
        for(Sample s:result.samples)rows.append(s.phase).append(',').append(s.ordinal).append(',').append(s.kind).append(',').append(s.student).append(',')
            .append(s.startNanos).append(',').append(s.elapsedNanos).append(',').append(s.status).append(',').append(s.outcome).append(',').append(s.code).append('\n');
        Files.writeString(folder.resolve("requests.csv"),rows.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.writeString(folder.resolve("observations.json"),JSON.writeValueAsString(result.observations),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Path temporary=folder.resolve("result.pending");Files.writeString(temporary,JSON.writeValueAsString(result),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        Files.move(temporary,folder.resolve("result.json"),StandardCopyOption.ATOMIC_MOVE);
        Map<String,String> hashes=new LinkedHashMap<>();for(String file:List.of("manifest.json","requests.csv","observations.json","result.json"))
            hashes.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(folder.resolve(file)))));
        return hashes;
    }
    static Map<String,Object> summary(List<Sample> samples,List<Observation> observations){
        Map<String,Object> result=new LinkedHashMap<>();Map<String,List<Long>> groups=new TreeMap<>();
        List<Sample> front=samples.stream().filter(s->s.phase.equals("FOREGROUND")).toList();
        for(Sample s:samples)if(!s.outcome.equals("UNSENT"))groups.computeIfAbsent(s.phase+"/"+s.kind+"/"+s.outcome,k->new ArrayList<>()).add(s.elapsedNanos);
        Map<String,Object> distributions=new TreeMap<>();groups.forEach((k,v)->distributions.put(k,distribution(v)));result.put("latencyGroups",distributions);
        result.put("planned",front.size());long sent=front.stream().filter(s->!s.outcome.equals("UNSENT")).count();result.put("sent",sent);result.put("unsent",front.size()-sent);
        long window=front.stream().filter(s->!s.outcome.equals("UNSENT")).mapToLong(s->s.startNanos+s.elapsedNanos).max().orElse(0);result.put("foregroundWindowNanos",window);
        result.put("foregroundCompletionPerSecond",window==0?null:sent*1e9/window);
        result.put("http202",front.stream().filter(s->s.status==202).count());result.put("acceptedValidated",observations.size());
        result.put("unknownPosts",front.stream().filter(s->s.outcome.equals("UNKNOWN_POST")).count());
        result.put("observedSucceeded",observations.stream().filter(o->o.state.equals("SUCCEEDED")).count());
        result.put("observedFailed",observations.stream().filter(o->o.state.equals("FAILED")).count());
        result.put("censored",observations.stream().filter(o->o.observedNanos==null).count());
        result.put("successfulSubsetObservedLatency",distribution(observations.stream().filter(o->o.state.equals("SUCCEEDED")&&o.observedNanos!=null).map(Observation::observedNanos).toList()));
        result.put("sqlActualIssuanceNotMeasured",true);return result;
    }
}
