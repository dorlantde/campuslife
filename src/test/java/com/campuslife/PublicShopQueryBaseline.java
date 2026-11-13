package com.campuslife;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Opt in with -Dit.test=PublicShopQueryBaseline; deliberately outside default *Test/*IT patterns. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@Import(PublicShopQueryBaseline.CaptureConfiguration.class)
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_QUERY_BASELINE",matches="isolated-fixture")
class PublicShopQueryBaseline {
    private static final long BASE=PublicShopQueryFixture.BASE;
    private static final int WARMUP=5, SAMPLES=30;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired Capture capture;
    private long haircut, print;

    @Test void measureCurrentCatalogWithoutChangingProductionSqlOrIndexes()throws Exception {
        Path root=Path.of("").toAbsolutePath().normalize();
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initialize();
        haircut=fixture.haircut;print=fixture.print;
        var report=new LinkedHashMap<String,Object>();
        report.put("startedAt",Instant.now().toString());
        report.put("kind","single-client warm-read baseline; no capacity or cache speedup claim");
        report.put("warmupPerCase",WARMUP);report.put("samplesPerCase",SAMPLES);
        report.put("mysql",jdbc.queryForMap("SELECT VERSION() AS version,@@innodb_buffer_pool_size AS bufferPoolBytes,@@transaction_isolation AS defaultIsolation,@@character_set_server AS charset,@@collation_server AS collation,@@optimizer_switch AS optimizerSwitch"));
        report.put("runtime",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"),"availableProcessors",Runtime.getRuntime().availableProcessors(),"poolMax",5,"httpConcurrency",1));
        var digests=new TreeMap<String,String>();
        for(String file:List.of("merchant/mapper/PublicShopMapper.java","merchant/mapper/PublicReviewMapper.java","merchant/mapper/BusinessHourMapper.java","merchant/service/PublicShopCatalogService.java")){
            digests.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("src/main/java/com/campuslife").resolve(file)))));
        }
        report.put("productionSourceSha256",digests);
        report.put("fixtureSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("src/test/java/com/campuslife/PublicShopQueryFixture.java")))));
        report.put("harnessSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("src/test/java/com/campuslife/PublicShopQueryBaseline.java")))));
        report.put("indexes",jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('shop','shop_category','shop_member','account','shop_business_hour','order_review') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"));
        var tiers=new ArrayList<Map<String,Object>>();
        int previous=0;
        for(int scale:List.of(1_000,10_000)){
            fixture.grow(previous+1,scale);
            var tier=new LinkedHashMap<String,Object>();tier.put("shops",scale);
            tier.put("rows",fixture.counts());
            var cases=cases(scale);
            var evidence=new LinkedHashMap<String,Map<String,Object>>();
            for(var c:cases){
                capture.begin();JsonNode body;
                try{body=request(c);}finally{capture.end();}
                validateResponse(c,body,scale);
                var statements=capture.snapshot();assertThat(statements).hasSize(c.expectedStatements());
                var item=new LinkedHashMap<String,Object>();item.put("path",c.path());item.put("sqlStatements",statements.size());
                item.put("returnedItems",body.has("items")?body.path("items").size():1);
                if(body.has("total")){item.put("total",body.path("total").asLong());}
                var plans=new ArrayList<Map<String,Object>>();
                for(var statement:statements){plans.add(plan(statement));}
                item.put("queries",plans);evidence.put(c.name(),item);
            }
            for(var c:cases){for(int n=0;n<WARMUP;n++){request(c);}}
            var samples=new HashMap<String,List<Double>>();cases.forEach(c->samples.put(c.name(),new ArrayList<>()));
            var random=new Random(20261028);var order=new ArrayList<>(cases);
            for(int round=0;round<SAMPLES;round++){
                Collections.shuffle(order,random);
                for(var c:order){long start=System.nanoTime();request(c);samples.get(c.name()).add((System.nanoTime()-start)/1_000_000.0);}
            }
            for(var c:cases){evidence.get(c.name()).put("httpClientMs",stats(samples.get(c.name())));evidence.get(c.name()).put("httpSamplesMs",samples.get(c.name()));}
            tier.put("cases",evidence);tiers.add(tier);previous=scale;
            System.out.println("BASELINE measured "+scale+" shops; "+cases.size()+" cases x "+SAMPLES+" HTTP samples");
        }
        report.put("tiers",tiers);report.put("finishedAt",Instant.now().toString());
        report.put("fixtureValidation","relationships, complete booking facts, visibility, payload/count/order and SQL count assertions passed; no production data used");
        Path output=root.resolve("output/performance/public-shop-query-baseline.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("BASELINE raw evidence: output/performance/public-shop-query-baseline.json");
        // Whole instance is disposed by test-mysql.ps1, including seed rows. No DELETE/DROP against user DBs.
    }

    private record Case(String name,String path,String kind,int page,int size,String filter,int expectedStatements){}
    private List<Case> cases(int scale){
        String base="/api/v1/shops";int last=scale*6/10/20;
        return List.of(new Case("list_first",base,"list",1,20,"all",4),new Case("list_size100",base+"?size=100","list",1,100,"all",4),
                new Case("list_category",base+"?categoryId="+haircut,"list",1,20,"haircut",4),
                new Case("list_common_keyword",base+"?keyword="+encode("校园"),"list",1,20,"common",4),
                new Case("list_rare_keyword",base+"?keyword="+encode("稀有"),"list",1,20,"rare",4),
                new Case("list_no_match",base+"?keyword=not_present_baseline","list",1,20,"none",2),
                new Case("list_deep",base+"?page="+last,"list",last,20,"all",4),
                new Case("detail_normal",base+"/"+(BASE+8),"detail",1,1,"normal",3),
                new Case("detail_hot",base+"/"+(BASE+6),"detail",1,1,"hot",3),
                new Case("reviews_hot_first",base+"/"+(BASE+6)+"/reviews","review",1,20,"hot",3),
                new Case("reviews_hot_deep",base+"/"+(BASE+6)+"/reviews?page=225","review",225,20,"hot",3));
    }
    private static String encode(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    private JsonNode request(Case c)throws Exception{
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+c.path())).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(c.name()).isEqualTo(200);return json.readTree(response.body()).path("data");
    }
    private void validateResponse(Case c,JsonNode body,int scale){
        if(c.kind().equals("list")){
            var expected=new ArrayList<String>();
            for(int n=1;n<=scale;n++){
                boolean matches= n%10==0||n%10>=5;
                matches &= switch(c.filter()){case "haircut"->n%2==0;case "common"->n%997!=0;case "rare"->n%997==0;case "none"->false;default->true;};
                if(matches){expected.add(Long.toString(BASE+n));}
            }
            assertThat(body.path("total").asLong()).isEqualTo(expected.size());
            var actual=new ArrayList<String>();for(var item:body.path("items")){actual.add(item.path("id").asString());}
            int offset=(c.page()-1)*c.size();assertThat(actual).isEqualTo(expected.subList(Math.min(offset,expected.size()),Math.min(offset+c.size(),expected.size())));
        }else if(c.kind().equals("detail")){
            long shop=BASE+(c.filter().equals("hot")?6:8);
            assertThat(body.path("reviewCount").asLong()).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE shop_id=? AND visibility='VISIBLE'",Long.class,shop));
            assertThat(body.path("rating").asDouble()).isEqualTo(jdbc.queryForObject("SELECT ROUND(AVG(rating),2) FROM order_review WHERE shop_id=? AND visibility='VISIBLE'",Double.class,shop));
        }else{
            assertThat(body.path("total").asLong()).isEqualTo(4518);assertThat(body.path("items").size()).isEqualTo(20);
        }
    }
    private Map<String,Object> plan(Statement statement){
        var result=new LinkedHashMap<String,Object>();result.put("sql",statement.sql());result.put("parameters",statement.parameters());
        assertThat(statement.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(statement.readOnly()).isTrue();
        result.put("requestTransactionIsolation","REPEATABLE_READ");result.put("requestConnectionReadOnly",true);
        Object[] args=statement.parameters().toArray();
        result.put("explain",jdbc.queryForList("EXPLAIN "+statement.sql(),args));
        result.put("analyze",jdbc.queryForObject("EXPLAIN ANALYZE "+statement.sql(),String.class,args));
        for(int n=0;n<WARMUP;n++){jdbc.queryForList(statement.sql(),args);}
        var times=new ArrayList<Double>();for(int n=0;n<SAMPLES;n++){long start=System.nanoTime();jdbc.queryForList(statement.sql(),args);times.add((System.nanoTime()-start)/1_000_000.0);}
        result.put("jdbcRoundTripMs",stats(times));result.put("jdbcSamplesMs",times);return result;
    }
    private static Map<String,Object> stats(List<Double> values){
        var sorted=values.stream().sorted().toList();return Map.of("n",values.size(),"min",sorted.getFirst(),"p50",sorted.get((int)Math.ceil(values.size()*.50)-1),"p95",sorted.get((int)Math.ceil(values.size()*.95)-1),"max",sorted.getLast(),"mean",values.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
    }
    record Statement(String sql,List<Object> parameters,int isolation,boolean readOnly){}
    @TestConfiguration(proxyBeanMethods=false)
    static class CaptureConfiguration {@Bean Capture baselineCapture(){return new Capture();}}
    @Intercepts(@Signature(type=StatementHandler.class,method="prepare",args={Connection.class,Integer.class}))
    static class Capture implements Interceptor {
        private final AtomicBoolean enabled=new AtomicBoolean();
        private final List<Statement> captured=new CopyOnWriteArrayList<>();
        void begin(){captured.clear();enabled.set(true);}void end(){enabled.set(false);}List<Statement> snapshot(){return List.copyOf(captured);}
        @Override public Object intercept(Invocation invocation)throws Throwable{
            if(enabled.get()){
                var handler=(StatementHandler)invocation.getTarget();var bound=handler.getBoundSql();
                var values=new ArrayList<Object>();var param=bound.getParameterObject();
                for(var mapping:bound.getParameterMappings()){
                    String property=mapping.getProperty();Object value;
                    if(bound.hasAdditionalParameter(property)){value=bound.getAdditionalParameter(property);}
                    else if(param==null){value=null;}
                    else if(param instanceof Number||param instanceof CharSequence||param instanceof Boolean){value=param;}
                    else{value=org.apache.ibatis.reflection.SystemMetaObject.forObject(param).getValue(property);}
                    values.add(value);
                }
                var connection=(Connection)invocation.getArgs()[0];
                captured.add(new Statement(bound.getSql().replaceAll("\\s+"," ").strip(),Collections.unmodifiableList(values),connection.getTransactionIsolation(),connection.isReadOnly()));
            }
            return invocation.proceed();
        }
    }
}
