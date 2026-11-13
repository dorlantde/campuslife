package com.campuslife;

import com.campuslife.merchant.mapper.PublicShopMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in paired full HTTP comparison. Test-only original SQL routing never ships in the JAR. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@Import({PublicShopListAdoption.TestConfig.class,PublicShopQueryBaseline.CaptureConfiguration.class})
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_LIST_ADOPTION",matches="isolated-fixture")
class PublicShopListAdoption {
    private static final long BASE=PublicShopQueryFixture.BASE;
    private static final int WARMUP=4,ROUNDS=3,SAMPLES=12;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;@Autowired Route route;@Autowired PublicShopMapper mapper;
    @Autowired PublicShopQueryBaseline.Capture capture;@Autowired PlatformTransactionManager manager;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    private String target;
    private record Case(String name,Long category,String keyword,int page,int size){}

    @Test void validateAndMeasureActualHttpAcrossVisibilityDistributions()throws Exception {
        String phase=env.getRequiredProperty("CAMPUSLIFE_LIST_PHASE");
        assertThat(phase).isIn("validation","adopted");target=phase.equals("validation")?"candidate":"current";
        Path root=Path.of("").toAbsolutePath().normalize(),folder=Path.of(env.getRequiredProperty("CAMPUSLIFE_LIST_OUTPUT")).toAbsolutePath().normalize();
        assertThat(folder.getParent()).isEqualTo(root.resolve("output/performance"));
        assertThat(folder.getFileName().toString()).matches("public-shop-list-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}");
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initialize();
        var report=new LinkedHashMap<String,Object>();report.put("phase",phase);report.put("startedAt",Instant.now().toString());
        report.put("method",Map.of("rounds",ROUNDS,"samplesPerRound",SAMPLES,"warmupPerCaseVariant",WARMUP,"unit","full HTTP client milliseconds including JSON parsing","concurrency",1,"clock","2026-10-28T01:00:00Z"));
        report.put("mysql",jdbc.queryForMap("SELECT VERSION() AS version,@@innodb_buffer_pool_size AS bufferPoolBytes,@@optimizer_switch AS optimizerSwitch"));
        report.put("runtime",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"processors",Runtime.getRuntime().availableProcessors()));
        var digests=new TreeMap<String,String>();
        for(String name:List.of("src/main/java/com/campuslife/merchant/mapper/PublicShopMapper.java","src/main/java/com/campuslife/merchant/service/PublicShopCatalogService.java","src/test/java/com/campuslife/PublicShopQueryFixture.java","src/test/java/com/campuslife/PublicShopListAdoption.java")){
            digests.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(name)))));
        }
        report.put("sourceSha256",digests);var states=new ArrayList<Map<String,Object>>();int previous=0;
        for(int scale:List.of(1000,10000)){
            restore();fixture.grow(previous+1,scale);previous=scale;
            for(String profile:List.of("mixed60","dense90","sparse10")){
                profile(profile);
                var cases=cases(scale,profile,fixture.haircut,fixture.print);
                var state=new LinkedHashMap<String,Object>();state.put("shops",scale);state.put("profile",profile);
                state.put("semanticChecks",semantics(scale,profile,fixture.haircut,fixture.print));
                var entries=new LinkedHashMap<String,Map<String,Object>>();var expected=new HashMap<String,JsonNode>();
                for(var c:cases){
                    var original=call(c,"original");expected.put(c.name(),original);independent(c,original,scale,profile,fixture.haircut);
                    assertThat(call(c,target)).as(c.name()+" full response").isEqualTo(original);
                    var item=new LinkedHashMap<String,Object>();item.put("path",path(c));item.put("total",original.path("total").asLong());
                    var plans=new LinkedHashMap<String,Object>();
                    for(String mode:List.of("original",target)){
                        route.mode.set(mode);capture.begin();
                        try{call(c,mode);}finally{capture.end();}
                        var statements=capture.snapshot();assertThat(statements).hasSize(original.path("items").isEmpty()?2:4);
                        for(var statement:statements){assertThat(statement.isolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(statement.readOnly()).isTrue();}
                        var page=statements.get(1);var sqlEvidence=new LinkedHashMap<String,Object>();sqlEvidence.put("pageSql",page.sql());sqlEvidence.put("parameters",page.parameters());sqlEvidence.put("queryCount",statements.size());
                        sqlEvidence.put("analyze",jdbc.queryForObject("EXPLAIN ANALYZE FORMAT=TREE "+page.sql(),String.class,page.parameters().toArray()));
                        plans.put(mode,sqlEvidence);
                    }
                    item.put("plans",plans);entries.put(c.name(),item);
                }
                for(var c:cases){for(String mode:List.of("original",target)){for(int n=0;n<WARMUP;n++){call(c,mode);}}}
                var rounds=new HashMap<String,List<Map<String,Object>>>();cases.forEach(c->rounds.put(c.name(),new ArrayList<>()));
                var random=new Random(20261028L+scale+profile.hashCode());
                for(int round=1;round<=ROUNDS;round++){
                    var samples=new HashMap<String,Map<String,List<Double>>>();for(var c:cases){var m=new LinkedHashMap<String,List<Double>>();m.put("original",new ArrayList<>());m.put(target,new ArrayList<>());samples.put(c.name(),m);}
                    for(int n=0;n<SAMPLES;n++){var order=new ArrayList<>(cases);Collections.shuffle(order,random);
                        for(var c:order){var modes=new ArrayList<>(List.of("original",target));Collections.shuffle(modes,random);
                            for(String mode:modes){route.mode.set(mode);long start=System.nanoTime();var result=call(c,mode);double ms=(System.nanoTime()-start)/1_000_000.0;
                                assertThat(result).isEqualTo(expected.get(c.name()));samples.get(c.name()).get(mode).add(ms);
                            }
                        }
                    }
                    for(var c:cases){var block=new LinkedHashMap<String,Object>();block.put("round",round);for(String mode:List.of("original",target)){var values=samples.get(c.name()).get(mode);block.put(mode,Map.of("samplesMs",values,"stats",stats(values)));}rounds.get(c.name()).add(block);}
                    System.out.println("LIST "+phase+" shops="+scale+" profile="+profile+" round="+round+" HTTP equality passed");
                }
                for(var c:cases){entries.get(c.name()).put("rounds",rounds.get(c.name()));}
                state.put("cases",entries);states.add(state);
            }
        }
        restore();route.mode.set("current");report.put("states",states);report.put("finishedAt",Instant.now().toString());
        Files.createDirectories(folder);Files.writeString(folder.resolve(phase+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private List<Case> cases(int scale,String profile,long hair,long print){
        int ratio=profile.equals("mixed60")?60:profile.equals("dense90")?90:10;
        int all=scale*ratio/100,category=all/2;
        return List.of(new Case("all_first",null,null,1,20),new Case("all_size100",null,null,1,100),new Case("all_deep",null,null,Math.max(2,(all+19)/20),20),
                new Case("hair_first",hair,null,1,20),new Case("hair_size100",hair,null,1,100),new Case("hair_deep",hair,null,Math.max(2,(category+19)/20),20),
                new Case("print_first",print,null,1,20),new Case("print_size100",print,null,1,100),new Case("print_deep",print,null,Math.max(2,(category+19)/20),20));
    }
    private String path(Case c){return "/api/v1/shops?page="+c.page()+"&size="+c.size()+(c.category()==null?"":"&categoryId="+c.category())+(c.keyword()==null?"":"&keyword="+URLEncoder.encode(c.keyword(),java.nio.charset.StandardCharsets.UTF_8));}
    private JsonNode call(Case c,String mode)throws Exception{route.mode.set(mode);var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+env.getRequiredProperty("local.server.port")+path(c))).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as(path(c)).isEqualTo(200);return json.readTree(response.body()).path("data");}
    private void independent(Case c,JsonNode value,int scale,String profile,long hair){
        var ids=new ArrayList<String>();for(int n=1;n<=scale;n++){
            boolean visible=switch(profile){case "mixed60"->n%10==0||n%10>=5;case "dense90"->n%20>=2;default->n%20==0||n%20==1;};
            if(c.category()!=null){visible&=c.category()==hair?n%2==0:n%2==1;}
            if(visible){ids.add(Long.toString(BASE+n));}
        }
        assertThat(value.path("total").asLong()).isEqualTo(ids.size());int offset=Math.min((c.page()-1)*c.size(),ids.size());
        var actual=new ArrayList<String>();value.path("items").forEach(item->actual.add(item.path("id").asString()));
        assertThat(actual).isEqualTo(ids.subList(offset,Math.min(offset+c.size(),ids.size())));
    }
    private void profile(String profile){
        restore();
        if(!profile.equals("mixed60")){
            jdbc.update("UPDATE shop SET approval_status='APPROVED' WHERE id>?",BASE);jdbc.update("UPDATE shop_member SET status='ACTIVE' WHERE shop_id>?",BASE);
            String active=profile.equals("dense90")?"MOD(id-1000000,20) NOT IN(0,1)":"MOD(id-1000000,20) IN(0,1)";
            jdbc.update("UPDATE account SET status=IF("+active+",'ACTIVE','DISABLED') WHERE id>?",BASE);
        }
        for(String table:List.of("account","shop","shop_member")){jdbc.execute("ANALYZE TABLE "+table);}
    }
    private void restore(){
        jdbc.update("UPDATE shop SET approval_status=CASE MOD(id-1000000,10) WHEN 1 THEN 'PENDING' WHEN 2 THEN 'SUSPENDED' ELSE 'APPROVED' END WHERE id>?",BASE);
        jdbc.update("UPDATE shop_member SET status=CASE MOD(shop_id-1000000,10) WHEN 1 THEN 'PENDING' WHEN 4 THEN 'DISABLED' ELSE 'ACTIVE' END WHERE shop_id>?",BASE);
        jdbc.update("UPDATE account SET role='MERCHANT',status=IF(MOD(id-1000000,10)=3,'DISABLED','ACTIVE') WHERE id>?",BASE);
    }
    private List<String> semantics(int scale,String profile,long hair,long print)throws Exception{
        var checks=new ArrayList<String>();
        for(var c:List.of(new Case("search",hair,"校园",1,20),new Case("intersection",print,"稀有",1,20),new Case("literal",null,"_",1,20),new Case("missing",999999L,null,1,20),new Case("beyond",null,null,2147483647,100),new Case("blank",hair,"  ",1,20))){
            assertThat(call(c,target)).isEqualTo(call(c,"original"));checks.add(c.name());
        }
        for(long category:List.of(hair,print)){
            var c=new Case("snapshot",category,null,1,20);var before=call(c,"original");long account=Long.parseLong(before.path("items").get(0).path("id").asString());
            var read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            try(var writer=Executors.newSingleThreadExecutor()){
                for(String mode:List.of("original",target)){
                    route.mode.set(mode);
                    try{read.execute(status->{
                        long total=mapper.count(category,null);var expected=mapper.categoryFirstPage(category,20);
                        try{writer.submit(()->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",account)).get(10,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
                        sqlSession.clearCache(); // Re-execute SQL, rather than passing solely through MyBatis's first-level cache.
                        assertThat(mapper.categoryFirstPage(category,20)).isEqualTo(expected);assertThat(mapper.count(category,null)).isEqualTo(total);return null;
                    });
                        assertThat(call(c,mode).path("total").asLong()).isEqualTo(before.path("total").asLong()-1);checks.add("rr_"+category+"_"+mode);
                    }finally{jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",account);}
                }
            }
        }
        return checks;
    }
    private static Map<String,Object> stats(List<Double> values){var s=values.stream().sorted().toList();return Map.of("n",s.size(),"p50",s.get((int)Math.ceil(s.size()*.5)-1),"p95",s.get((int)Math.ceil(s.size()*.95)-1),"min",s.getFirst(),"max",s.getLast());}

    @TestConfiguration(proxyBeanMethods=false)
    static class TestConfig {
        @Bean @Primary Clock comparisonClock(){return Clock.fixed(Instant.parse("2026-10-28T01:00:00Z"),ZoneOffset.UTC);}
        @Bean Route listRoute(){return new Route();}
    }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class Route implements Interceptor {
        final AtomicReference<String> mode=new AtomicReference<>("current");
        private final Map<org.apache.ibatis.session.Configuration,SqlSource> originals=new ConcurrentHashMap<>();
        @Override public Object intercept(Invocation invocation)throws Throwable{
            var ms=(MappedStatement)invocation.getArgs()[0];
            if(ms.getId().equals(PublicShopMapper.class.getName()+".page")||ms.getId().equals(PublicShopMapper.class.getName()+".categoryFirstPage")){
                var config=ms.getConfiguration();SqlSource source;
                if(mode.get().equals("original")){
                    source=originals.computeIfAbsent(config,c->c.getLanguageDriver(null).createSqlSource(c,"SELECT "+PublicShopMapper.COLUMNS+PublicShopMapper.FROM_VISIBLE+PublicShopMapper.FILTER+" ORDER BY s.id ASC LIMIT #{size} OFFSET #{offset}",Map.class));
                    @SuppressWarnings("unchecked") var input=(Map<String,Object>)invocation.getArgs()[1];var parameters=new HashMap<>(input);
                    parameters.putIfAbsent("keyword",null);parameters.putIfAbsent("offset",0L);invocation.getArgs()[1]=parameters;
                }else{source=ms.getSqlSource();}
                invocation.getArgs()[0]=new MappedStatement.Builder(config,ms.getId(),source,ms.getSqlCommandType()).resource(ms.getResource()).fetchSize(ms.getFetchSize()).timeout(ms.getTimeout())
                        .statementType(ms.getStatementType()).resultSetType(ms.getResultSetType()).parameterMap(ms.getParameterMap()).resultMaps(ms.getResultMaps())
                        .cache(ms.getCache()).flushCacheRequired(ms.isFlushCacheRequired()).useCache(ms.isUseCache()).databaseId(ms.getDatabaseId()).lang(ms.getLang()).resultOrdered(ms.isResultOrdered()).build();
            }
            return invocation.proceed();
        }
    }
}
