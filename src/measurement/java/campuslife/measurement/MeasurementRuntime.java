package campuslife.measurement;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import static campuslife.measurement.MeasurementScenario.*;
import static campuslife.measurement.MeasurementDriver.*;
import static campuslife.measurement.MeasurementFacts.*;

/** C.3 wiring check only: FORMAL seed, 8 foreground requests, one real claim. Not Pilot. */
public final class MeasurementRuntime {
    static final Path OUT=Path.of("/output");
    static final List<String> checks=new ArrayList<>(),tokens=new ArrayList<>();
    static final List<Map<String,Object>> views=Collections.synchronizedList(new ArrayList<>());
    static void check(String code,boolean yes)throws Exception{need(yes,code);checks.add(code);write("progress.json",checks);}
    static void write(String name,Object value)throws Exception{
        Path tmp=OUT.resolve(name+".pending");Files.writeString(tmp,JSON.writeValueAsString(value));Files.move(tmp,OUT.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static void await(String name,int seconds)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(!Files.exists(OUT.resolve(name))&&System.nanoTime()<end){if(Files.exists(OUT.resolve("abort.json")))throw new Invalid("HOST_ABORT");Thread.sleep(100);}
        need(Files.exists(OUT.resolve(name)),"HANDSHAKE_DEADLINE");
    }
    static boolean validHost(tools.jackson.databind.JsonNode state,String caseId,String instance,long now){
        long until=state.path("validUntilUnixMillis").asLong(0);
        return caseId.equals(state.path("caseId").asText())&&instance.equals(state.path("instanceId").asText())
            &&state.path("baselinePassed").asBoolean()&&!state.path("stop").asBoolean(true)&&until>=now&&until<=now+20000;
    }
    static BooleanSupplier stopper(String caseId,String instance){return ()->{try{return !validHost(JSON.readTree(Files.readString(OUT.resolve("host-state.json"))),caseId,instance,System.currentTimeMillis());}catch(Exception e){return true;}};}
    static Map<String,Object> view(Snapshot s){
        var sql=new LinkedHashMap<>(s.sqlAfter());sql.remove("claims");sql.remove("outboxFacts");
        return Map.of("startedNanos",s.started(),"finishedNanos",s.finished(),"available",s.available(),"missingCode",s.missingCode(),"sql",sql,"redis",s.redis(),"queues",s.queues(),"metrics",s.metrics());
    }
    static Snapshot sampled(MeasurementFacts.Sampler sampler)throws Exception{
        for(int i=0;i<120;i++){var next=sampler.sample();if(next.isPresent()){views.add(view(next.get()));return next.get();}Thread.sleep(50);}
        throw new Invalid("SAMPLER_DEADLINE");
    }
    static final class Observer implements AutoCloseable {
        final AtomicBoolean done=new AtomicBoolean();final AtomicReference<String> failure=new AtomicReference<>("");final Thread thread;
        Observer(MeasurementFactSources source){
            var sampler=new MeasurementFacts.Sampler(source::collect);
            thread=Thread.ofPlatform().name("runtime-observer").start(()->{
                try{Snapshot previous=null;while(!done.get()){var item=sampler.sample();if(item.isPresent()){var s=item.get();views.add(view(s));if(!s.available())failure.compareAndSet("","CRITICAL_SAMPLE_UNAVAILABLE");if(previous!=null&&decision(previous,s).state().equals("INVALID"))failure.compareAndSet("","INVALID_COMPONENT_FACTS");previous=s;write("facts-samples.json",List.copyOf(views));}Thread.sleep(100);}}
                catch(Exception e){failure.compareAndSet("","OBSERVER_FAILED");}
            });
        }
        public void close()throws Exception{done.set(true);thread.join(15000);need(!thread.isAlive(),"OBSERVER_NOT_CLOSED");}
    }
    static Map<Long,String> login(MeasurementHttpTransport http,Path input,MeasurementScenario scenario)throws Exception{
        Map<Long,String> sessions=new LinkedHashMap<>();String password=Files.readString(input.resolve("STUDENT_PASSWORD")).strip();
        for(long student:List.of(900001L,900002L)){
            var body=JSON.writeValueAsString(Map.of("username","measurement_student_"+(student-900000),"password",password));
            var reply=http.exchange(new Call("POST","/api/v1/auth/login",body,"",""),System.nanoTime()+Duration.ofSeconds(3).toNanos());need(reply.status()==200,"LOGIN_HTTP");var data=data(reply);
            need(id(data.path("account").get("id"))==student&&"STUDENT".equals(data.path("account").path("role").asText())&&"Bearer".equals(data.path("tokenType").asText())&&instant(data.get("expiresAt")).isAfter(Instant.now()),"LOGIN_IDENTITY");
            String token=text(data.get("accessToken"));need(token.matches("[A-Za-z0-9_.-]{1,4096}"),"LOGIN_TOKEN_FORMAT");tokens.add(token);sessions.put(student,token);
            var me=http.exchange(new Call("GET","/api/v1/me","",token,""),System.nanoTime()+Duration.ofSeconds(3).toNanos());need(me.status()==200&&id(data(me).get("id"))==student,"SESSION_IDENTITY");
        }
        var denied=http.exchange(new Call("GET","/api/v1/orders?page=1&size=20","","",""),System.nanoTime()+Duration.ofSeconds(3).toNanos());
        check("normal-login-two-identities-and-anonymous-order-denied",sessions.size()==2&&denied.status()==401);return sessions;
    }
    static void run(Path input)throws Exception{
        long started=System.nanoTime();Map<String,Object> permit=JSON.readValue(Files.readString(input.resolve("permit.json")),Map.class);MeasurementScenario scenario;
        try(var fixture=new V24MeasurementFixture(input.resolve("permit.json"))){
            fixture.identity(fixture.permit);Flyway.configure().dataSource(V24MeasurementFixture.URL,"campuslife",Files.readString(input.resolve("DB_PASSWORD")).strip()).locations("filesystem:/tool/migrations").load().migrate();
            need("FORMAL".equals(permit.get("profile")),"C3_NOT_PILOT");fixture.seed(false);var expected=fixture.expected();write("expected.json",expected);
            // Parse the persisted JSON contract: JDBC SUM is BigDecimal even when JSON is an integer.
            scenario=new MeasurementScenario(OUT.resolve("expected.json"),fixture.haircut,fixture.print);write("seed-ready.json",Map.of("campaign",2000001,"controlVersion",0,"profile","FORMAL","syntheticHistory",true));
        }
        await("app-ready.json",300);
        try(var initial=new MeasurementFactSources(input,List.of(),2000001)){
            var sampler=new MeasurementFacts.Sampler(initial::collect);Snapshot a=sampled(sampler),b=sampled(sampler);
            check("empty-async-gate-open-and-two-stable-facts",decision(a,b).allowReplay()&&MeasurementFacts.n(b.sqlAfter(),"issued")==0);
            write("facts-ready.json",Map.of("emptyDrain",true));
            try(var observer=new Observer(initial);var http=MeasurementHttpTransport.privateEdge()){
                await("go.json",90);var hostStop=stopper(scenario.caseId,permit.get("instanceId").toString());
                BooleanSupplier stop=()->hostStop.getAsBoolean()||!observer.failure.get().isEmpty();need(!stop.getAsBoolean(),"HOST_GATE_NOT_VALID");
                var sessions=login(http,input,scenario);var policy=Policy.fixed();
                var cold=new MeasurementDriver(scenario,http,sessions,policy,stop);var first=cold.readOnly("CACHE_FIRST",scenario.firstAccess());write("cache-first.json",first);check("six-initial-detail-validations",!cold.stopped.get()&&first.size()==6);
                List<Task> tasks=List.of(new Task(0,Kind.DETAIL_HOT,1000006,0,""),new Task(1,Kind.DETAIL_NORMAL,1000010,0,""),
                    new Task(2,Kind.CATEGORY,0,0,""),new Task(3,Kind.SEARCH,0,0,""),new Task(4,Kind.DEEP,0,0,""),
                    new Task(5,Kind.ORDERS,0,900001,""),new Task(6,Kind.ORDERS,0,900002,""),new Task(7,Kind.CLAIM,2000001,900001,"c3_"+scenario.caseId.substring(5)));
                var driver=new MeasurementDriver(scenario,http,sessions,policy,stop);AtomicReference<Decision> drain=new AtomicReference<>();
                BooleanSupplier verified=()->{
                    try{
                        observer.close();need(observer.failure.get().isEmpty()&&!hostStop.getAsBoolean(),"DRAIN_HOST_STOP");
                        var refs=driver.pending.values().stream().map(p->new Expected(Long.parseLong(p.id),p.task.student(),p.task.key())).toList();
                        need(refs.size()==1,"C3_ACCEPTED_SET");
                        try(var source=new MeasurementFactSources(input,refs,2000001)){
                            var reads=new MeasurementFacts.Sampler(source::collect);Snapshot previous=sampled(reads);long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
                            while(System.nanoTime()<deadline&&!hostStop.getAsBoolean()){
                                Snapshot now=sampled(reads);need(previous.available()&&now.available(),"CRITICAL_DRAIN_SAMPLE_MISSING");Decision d=decision(previous,now);drain.set(d);
                                if(d.allowReplay())return true;if(d.state().equals("INVALID"))return false;previous=now;
                            }
                        }return false;
                    }catch(Exception e){return false;}
                };
                Result result=driver.run(tasks,1,verified);write("artifact-hashes.json",MeasurementDriver.writeArtifacts(OUT.resolve("segment"),scenario.caseId,0,6203,tasks,policy,result));
                check("eight-real-foreground-requests-valid",!result.stopped()&&result.workersClosed()&&result.samples().stream().filter(s->s.phase().equals("FOREGROUND")&&Set.of("OK","ACCEPTED").contains(s.outcome())).count()==8);
                check("one-real-async-admission-and-observed-success",result.observations().size()==1&&result.observations().getFirst().state().equals("SUCCEEDED")&&result.observations().getFirst().observedNanos()!=null);
                check("sql-redis-receipt-drain-authorizes-original-key-replay",drain.get()!=null&&drain.get().allowReplay()&&drain.get().actualSqlCoupons()==1&&Boolean.TRUE.equals(result.summary().get("replayDrainGateAllowed"))&&result.samples().stream().filter(s->s.phase().equals("REPLAY")&&s.outcome().equals("REPLAY_OK")).count()==1);
                need(!hostStop.getAsBoolean(),"FINAL_HOST_STOP");write("drain.json",drain.get());
            }
        }
        write("facts-samples.json",List.copyOf(views));
        var artifacts=new ArrayList<Path>();for(String name:List.of("progress.json","expected.json","seed-ready.json","facts-ready.json","cache-first.json","artifact-hashes.json","drain.json","facts-samples.json"))artifacts.add(OUT.resolve(name));
        try(var paths=Files.list(OUT.resolve("segment"))){artifacts.addAll(paths.toList());}
        for(Path p:artifacts){
            String value=Files.readString(p);for(String token:tokens)need(!value.contains(token),"TOKEN_ARTIFACT_LEAK");
        }
        check("tokens-memory-only-no-artifact-leak",tokens.size()==2);tokens.clear();
        write("checks.json",Map.of("task","M06.2-B.2-C.3","passed",true,"checks",checks,"foreground",8,"initialDetails",6,"realIssued",1,"normalLogins",2,"formalMeasurement",false,"pilot",false,"elapsedSeconds",(System.nanoTime()-started)/1e9));
    }
    public static void main(String[] args){try{run(Path.of(args[0]));}catch(Throwable error){
        String code=error instanceof Invalid?error.getMessage():"RUNTIME_CHECK_FAILED";
        try{write("failure.json",Map.of("code",code,"failureType",error.getClass().getSimpleName(),"checks",checks));write("facts-samples.json",List.copyOf(views));}catch(Exception ignored){}
        System.out.println("{\"passed\":false,\"code\":\""+code+"\"}");System.exit(1);
    }}
}
