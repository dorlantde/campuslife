package com.campuslife.coupon.redis;

import com.campuslife.coupon.service.CouponRecoveryTransactions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** No fallback and no SQL transaction around any network command. IDs stay strings in Lua. */
public class CouponRedisStore {
 public static class Unavailable extends RuntimeException {
  private final String code;
  public Unavailable(String code){super("Coupon reservation store unavailable ("+code+")");this.code=code;}
  public String code(){return code;}
 }
 public record Reserved(String status,String reservation) {}
 public record Stats(int total,int free,int held,int spent) {}
 public record Page(String cursor,List<Reservation> reservations) {}
 private final StringRedisTemplate redis;private final String namespace;private final ObjectMapper json;
 private final Map<String,DefaultRedisScript<List>> scripts=new HashMap<>();
 public CouponRedisStore(StringRedisTemplate redis,String namespace,ObjectMapper json){
  this.redis=redis;this.namespace=namespace;this.json=json;
  for(String name:List.of("reserve","settle","prepare","initialize","seed","activate","inspect","scan","retire")){
   scripts.put(name,new DefaultRedisScript<>(read("common")+read(name),List.class));
  }
 }
 private static String read(String name){try(var input=new ClassPathResource("redis/coupon/"+name+".lua").getInputStream()){return new String(input.readAllBytes(),StandardCharsets.UTF_8);}catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}}
 public String controlKey(long campaign){return "coupon:"+namespace+":{"+campaign+"}:control";}
 public String epochKey(long campaign,long generation){return "coupon:"+namespace+":{"+campaign+"}:g:"+generation;}
 public String runId(){
  noTransaction();Properties p=redis.execute((RedisCallback<Properties>)c->c.serverCommands().info("server"));
  String run=p==null?null:p.getProperty("run_id");if(run==null||!run.matches("[a-f0-9]{40}")){throw new Unavailable("RUN_ID");}return run;
 }
 private void requireRun(String expected){if(expected==null||!expected.equals(runId())){throw new Unavailable("RESTARTED");}}
 public Reserved reserve(long campaign,long generation,String run,long student,String key,String reservation,Instant at){
  positive(campaign);positive(generation);positive(student);requireRun(run);
  var a=execute("reserve",campaign,generation,Long.toString(generation),run,reservation,Long.toString(student),key,Long.toString(at.toEpochMilli()),Long.toString(campaign));
  String status=text(a,0);String rid=a.size()>1?text(a,1):null;
  if(Set.of("HELD","SPENT","PENDING","CLAIMED","SOLD_OUT").contains(status)||("CLOSED".equals(status)&&rid!=null)){return new Reserved(status,rid);}throw new Unavailable(status);
 }
 public void settle(Settlement t,Instant now){
  requireRun(t.run());var a=execute("settle",t.campaign(),t.generation(),Long.toString(t.generation()),t.run(),t.reservation(),Long.toString(t.student()),t.key(),Long.toString(now.toEpochMilli()),Long.toString(t.campaign()),t.status(),t.coupon()==null?"":t.coupon().toString());expect(a,"DONE","ALREADY");
 }
 public Stats inspect(long campaign,long generation,String run){
  requireRun(run);var a=execute("inspect",campaign,generation,Long.toString(generation),run);expect(a,"HEALTHY");
  return new Stats(Integer.parseInt(text(a,1)),Integer.parseInt(text(a,2)),Integer.parseInt(text(a,3)),Integer.parseInt(text(a,4)));
 }
 public Page scan(long campaign,long generation,String run,String cursor){
  requireRun(run);var a=execute("scan",campaign,generation,Long.toString(generation),run,cursor);expect(a,"PAGE");
  if(a.size()!=3||!(a.get(2) instanceof List<?> pairs)||pairs.size()%2!=0){throw new Unavailable("SCAN_SHAPE");}
  var rows=new ArrayList<Reservation>();
  for(int i=0;i<pairs.size();i+=2){
   try{var r=json.readValue(pairs.get(i+1).toString(),Reservation.class);
    if(!pairs.get(i).toString().equals("r:"+r.id())||r.campaign()!=campaign||r.generation()!=generation||r.student()<1||r.at()<0||!r.id().matches("[a-f0-9]{32}")||!r.key().matches("[A-Za-z0-9_-]{1,64}")||!Set.of("HELD","SPENT","CLOSED").contains(r.state())){throw new Unavailable("RECORD");}rows.add(r);
   }catch(Unavailable ex){throw ex;}catch(Exception ex){throw new Unavailable("RECORD");}
  }
  return new Page(text(a,1),List.copyOf(rows));
 }
 public void prepare(Rebuild t,String run){
  requireRun(run);var a=execute("prepare",t.campaign(),t.generation(),Long.toString(t.generation()),run,t.token());expect(a,"PREPARED");
  long old=Long.parseLong(text(a,1));if(old>0&&old<t.generation()){
   var result=executeWithKeys("retire",List.of(controlKey(t.campaign()),epochKey(t.campaign(),old)),Long.toString(t.generation()),run,t.token(),Long.toString(old));expect(result,"RETIRED");
  }
 }
 public void initialize(Rebuild t,String run,int pending){requireRun(run);expect(execute("initialize",t.campaign(),t.generation(),Long.toString(t.generation()),run,t.token(),Integer.toString(t.total()),Integer.toString(pending),Integer.toString(t.issued())),"INITIALIZED");}
 public void seed(Rebuild t,String run,int batch,List<Map<String,Object>> entries){
  if(entries.isEmpty()||entries.size()>200){throw new IllegalArgumentException("Bounded seed batch required");}
  String body=json.writeValueAsString(entries);if(body.getBytes(StandardCharsets.UTF_8).length>262144){throw new IllegalArgumentException("Seed batch too large");}
  requireRun(run);expect(execute("seed",t.campaign(),t.generation(),Long.toString(t.generation()),run,t.token(),Integer.toString(batch),digest(body),body,Long.toString(t.campaign())),"SEEDED");
 }
 public void activate(Rebuild t,String run){requireRun(run);expect(execute("activate",t.campaign(),t.generation(),Long.toString(t.generation()),run,t.token(),t.resume()),"ACTIVE");}
 private List<?> execute(String name,long campaign,long generation,String... args){return executeWithKeys(name,List.of(controlKey(campaign),epochKey(campaign,generation)),args);}
 private List<?> executeWithKeys(String name,List<String> keys,String... args){
  noTransaction();List<?> value=redis.execute(scripts.get(name),keys,(Object[])args);if(value==null||value.isEmpty()){throw new Unavailable("EMPTY_REPLY");}return value;
 }
 private static String digest(String body){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
 private static String text(List<?> v,int i){if(v.size()<=i||v.get(i)==null){throw new Unavailable("REPLY");}return v.get(i).toString();}
 private static void expect(List<?> v,String... allowed){if(!Arrays.asList(allowed).contains(text(v,0))){throw new Unavailable(text(v,0));}}
 private static void positive(long v){if(v<1){throw new IllegalArgumentException("Positive identity required");}}
 private static void noTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive()){throw new IllegalStateException("Redis IO must be outside SQL transactions");}}
}
