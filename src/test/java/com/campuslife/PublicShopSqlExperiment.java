package com.campuslife;

import com.campuslife.merchant.mapper.PublicShopMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** M01.1 only: query alternatives run through JDBC in an isolated fixture, never replace production Mappers. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "campuslife.booking.expiry-enabled=false","campuslife.print.expiry-enabled=false","campuslife.files.cleanup-enabled=false"})
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_SQL_EXPERIMENT",matches="isolated-fixture")
class PublicShopSqlExperiment {
    private static final int ROUNDS=3,SAMPLES=20,WARMUP=5;
    private static final long BASE=PublicShopQueryFixture.BASE;
    private static final List<String> VARIANTS=List.of("original","exists_derived","exists_no_semijoin");
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager manager;
    @Autowired SqlSessionFactory sessions;
    @Autowired PublicShopMapper mapper;
    @Autowired ObjectMapper json;
    private TransactionTemplate read;
    private record Scenario(String name,Long category,String keyword,long offset,int size){}
    private record Query(String sql,List<Object> args){}
    private record Pair(Query count,Query page){}
    private record Result(long total,List<Map<String,Object>> rows){}

    @Test void compareEquivalentQueriesAcrossScalesAndReadSnapshots()throws Exception {
        var root=Path.of("").toAbsolutePath().normalize();
        var folder=Path.of(env.getRequiredProperty("CAMPUSLIFE_SQL_OUTPUT")).toAbsolutePath().normalize();
        assertThat(folder.getParent()).isEqualTo(root.resolve("output/performance"));
        assertThat(folder.getFileName().toString()).matches("public-shop-sql-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}");
        int run=Integer.parseInt(env.getRequiredProperty("CAMPUSLIFE_SQL_RUN"));assertThat(run).isBetween(1,2);
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initialize();
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var evidence=new LinkedHashMap<String,Object>();evidence.put("startedAt",Instant.now().toString());evidence.put("run",run);
        evidence.put("method",Map.of("rounds",ROUNDS,"samplesPerRound",SAMPLES,"warmupPerScenarioVariant",WARMUP,"concurrency",1,"unit","count+page JDBC transaction milliseconds; not HTTP"));
        evidence.put("mysql",jdbc.queryForMap("SELECT VERSION() AS version,@@innodb_buffer_pool_size AS bufferPoolBytes,@@optimizer_switch AS optimizerSwitch"));
        evidence.put("runtime",Map.of("java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"availableProcessors",Runtime.getRuntime().availableProcessors()));
        var fingerprints=new TreeMap<String,String>();
        for(String file:List.of("src/main/java/com/campuslife/merchant/mapper/PublicShopMapper.java","src/main/java/com/campuslife/merchant/service/PublicShopCatalogService.java","src/main/java/com/campuslife/merchant/mapper/PublicReviewMapper.java","src/main/java/com/campuslife/merchant/mapper/BusinessHourMapper.java","src/test/java/com/campuslife/PublicShopQueryFixture.java","src/test/java/com/campuslife/PublicShopSqlExperiment.java")){
            fingerprints.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(file)))));
        }
        evidence.put("sourceSha256",fingerprints);
        evidence.put("indexes",jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('shop','shop_category','shop_member','account') ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"));
        var tiers=new ArrayList<Map<String,Object>>();int previous=0;
        for(int scale:List.of(1000,10000)){
            fixture.grow(previous+1,scale);previous=scale;
            var scenarios=scenarios(scale,fixture.haircut);
            var expected=new HashMap<String,Result>();var queries=new LinkedHashMap<String,Map<String,Pair>>();
            for(var scenario:scenarios){
                var byVariant=new LinkedHashMap<String,Pair>();for(String variant:VARIANTS){byVariant.put(variant,build(variant,scenario));}
                queries.put(scenario.name(),byVariant);var original=execute(byVariant.get("original"));expected.put(scenario.name(),original);
                assertIndependentIds(scenario,original,scale,fixture.haircut);
                // Independently cross-check the rendered original SQL against the actual production MyBatis Mapper.
                read.execute(status->{
                    assertThat(mapper.count(scenario.category(),scenario.keyword())).isEqualTo(original.total());
                    assertThat(mapper.page(scenario.category(),scenario.keyword(),scenario.offset(),scenario.size()).stream().map(r->r.id()).toList())
                            .isEqualTo(original.rows().stream().map(r->((Number)r.get("id")).longValue()).toList());return null;
                });
                for(String variant:VARIANTS){assertThat(execute(byVariant.get(variant))).as(scenario.name()+" "+variant).isEqualTo(original);}
            }
            var tier=new LinkedHashMap<String,Object>();tier.put("shops",scale);tier.put("rows",fixture.counts());
            tier.put("semanticChecks",semanticChecks(scale,fixture.haircut));
            var records=new LinkedHashMap<String,Map<String,Object>>();
            for(var scenario:scenarios){
                var item=new LinkedHashMap<String,Object>();item.put("categoryId",scenario.category());item.put("pattern",scenario.keyword());item.put("offset",scenario.offset());item.put("size",scenario.size());item.put("total",expected.get(scenario.name()).total());
                var plans=new LinkedHashMap<String,Object>();
                for(String variant:VARIANTS){var pair=queries.get(scenario.name()).get(variant);plans.put(variant,Map.of("count",plan(pair.count()),"page",plan(pair.page())));}
                item.put("variants",plans);records.put(scenario.name(),item);
            }
            for(var scenario:scenarios){for(String variant:VARIANTS){for(int n=0;n<WARMUP;n++){execute(queries.get(scenario.name()).get(variant));}}}
            var measurements=new LinkedHashMap<String,List<Map<String,Object>>>();scenarios.forEach(s->measurements.put(s.name(),new ArrayList<>()));
            var random=new Random(20261028L+run);
            for(int round=1;round<=ROUNDS;round++){
                var samples=new HashMap<String,Map<String,List<Double>>>();
                for(var scenario:scenarios){var map=new LinkedHashMap<String,List<Double>>();VARIANTS.forEach(v->map.put(v,new ArrayList<>()));samples.put(scenario.name(),map);}
                for(int n=0;n<SAMPLES;n++){
                    var order=new ArrayList<>(scenarios);Collections.shuffle(order,random);
                    for(var scenario:order){var variants=new ArrayList<>(VARIANTS);Collections.shuffle(variants,random);
                        for(String variant:variants){long start=System.nanoTime();var value=execute(queries.get(scenario.name()).get(variant));double ms=(System.nanoTime()-start)/1_000_000.0;
                            assertThat(value).as("timed result equivalence").isEqualTo(expected.get(scenario.name()));samples.get(scenario.name()).get(variant).add(ms);
                        }
                    }
                }
                for(var scenario:scenarios){var block=new LinkedHashMap<String,Object>();block.put("round",round);
                    for(String variant:VARIANTS){var values=samples.get(scenario.name()).get(variant);block.put(variant,Map.of("samplesMs",values,"summaryMs",stats(values)));}
                    measurements.get(scenario.name()).add(block);
                }
                System.out.println("SQL EXPERIMENT run="+run+" shops="+scale+" round="+round+" verified "+scenarios.size()+" scenarios x 3 variants x "+SAMPLES+" samples");
            }
            for(var scenario:scenarios){records.get(scenario.name()).put("rounds",measurements.get(scenario.name()));}
            tier.put("scenarios",records);tiers.add(tier);
        }
        evidence.put("tiers",tiers);evidence.put("finishedAt",Instant.now().toString());
        evidence.put("productionChanged",false);evidence.put("indexesOrOptimizerSettingsChanged",false);
        Files.createDirectories(folder);Files.writeString(folder.resolve("run"+run+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    private List<Scenario> scenarios(int scale,long haircut){
        return List.of(new Scenario("first",null,null,0,20),new Scenario("size100",null,null,0,100),new Scenario("category",haircut,null,0,20),
                new Scenario("common",null,"%校园%",0,20),new Scenario("rare",null,"%稀有%",0,20),new Scenario("absent",null,"%not_present_baseline%",0,20),
                new Scenario("deep",null,null,scale*6L/10-20,20));
    }
    private Pair build(String variant,Scenario s){
        if(variant.equals("original")){
            var params=new HashMap<String,Object>();params.put("categoryId",s.category());params.put("keyword",s.keyword());params.put("offset",s.offset());params.put("size",s.size());
            return new Pair(rendered("count",params),rendered("page",params));
        }
        String hint=variant.equals("exists_no_semijoin")?"/*+ NO_SEMIJOIN(@eligibility) */ ":"";
        var args=new ArrayList<Object>();String where=" FROM shop s WHERE s.approval_status='APPROVED'";
        if(s.category()!=null){where+=" AND s.category_id=?";args.add(s.category());}
        if(s.keyword()!=null){where+=" AND s.name LIKE ? ESCAPE '!'";args.add(s.keyword());}
        where+="""
                 AND EXISTS (SELECT /*+ QB_NAME(eligibility) */ 1
                   FROM shop_member m JOIN account a ON a.id=m.account_id
                   WHERE m.shop_id=s.id AND m.member_role='OWNER' AND m.status='ACTIVE'
                     AND a.status='ACTIVE' AND a.role='MERCHANT')
                """;
        var count=new Query("SELECT "+hint+"COUNT(*)"+where,List.copyOf(args));
        var pageArgs=new ArrayList<>(args);pageArgs.add(s.size());pageArgs.add(s.offset());
        var page=new Query("SELECT "+PublicShopMapper.COLUMNS+" FROM (SELECT "+hint+"s.id"+where+
                " ORDER BY s.id ASC LIMIT ? OFFSET ?) selected JOIN shop s ON s.id=selected.id JOIN shop_category c ON c.id=s.category_id ORDER BY s.id ASC",List.copyOf(pageArgs));
        return new Pair(count,page);
    }
    private Query rendered(String method,Map<String,Object> params){
        var bound=sessions.getConfiguration().getMappedStatement(PublicShopMapper.class.getName()+"."+method).getBoundSql(params);
        var args=new ArrayList<Object>();for(var p:bound.getParameterMappings()){
            args.add(bound.hasAdditionalParameter(p.getProperty())?bound.getAdditionalParameter(p.getProperty()):sessions.getConfiguration().newMetaObject(params).getValue(p.getProperty()));
        }
        return new Query(bound.getSql().replaceAll("\\s+"," ").strip(),Collections.unmodifiableList(args));
    }
    private Result execute(Pair pair){return read.execute(status->{
        var c=DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource()));
        try{assertThat(c.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);assertThat(c.isReadOnly()).isTrue();}
        catch(SQLException ex){throw new IllegalStateException(ex);}
        finally{DataSourceUtils.releaseConnection(c,jdbc.getDataSource());}
        long count=jdbc.queryForObject(pair.count().sql(),Long.class,pair.count().args().toArray());
        return new Result(count,jdbc.queryForList(pair.page().sql(),pair.page().args().toArray()));
    });}
    private Map<String,Object> plan(Query q){return jdbc.execute((ConnectionCallback<Map<String,Object>>)connection->{
        var result=new LinkedHashMap<String,Object>();result.put("sql",q.sql().replaceAll("\\s+"," ").strip());result.put("parameters",q.args());
        try(var ps=connection.prepareStatement("EXPLAIN "+q.sql())){bind(ps,q.args());try(var rs=ps.executeQuery()){result.put("explain",maps(rs));}}
        try(var ps=connection.prepareStatement("EXPLAIN ANALYZE FORMAT=TREE "+q.sql())){bind(ps,q.args());try(var rs=ps.executeQuery()){assertThat(rs.next()).isTrue();result.put("analyze",rs.getString(1));}}
        try(var st=connection.createStatement();var rs=st.executeQuery("SHOW WARNINGS")){
            var warnings=maps(rs);result.put("warnings",warnings);
            assertThat(warnings.stream().filter(w->"Warning".equalsIgnoreCase(w.get("Level").toString())).toList()).as("optimizer hint warnings").isEmpty();
        }
        return result;
    });}
    private static void bind(PreparedStatement statement,List<Object> args)throws SQLException{for(int n=0;n<args.size();n++){statement.setObject(n+1,args.get(n));}}
    private static List<Map<String,Object>> maps(ResultSet rs)throws SQLException{
        var rows=new ArrayList<Map<String,Object>>();var meta=rs.getMetaData();while(rs.next()){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=meta.getColumnCount();i++){row.put(meta.getColumnLabel(i),rs.getObject(i));}rows.add(row);}return rows;
    }
    private void assertIndependentIds(Scenario s,Result result,int scale,long haircut){
        var ids=new ArrayList<Long>();for(int n=1;n<=scale;n++){
            boolean visible=n%10==0||n%10>=5;
            if(s.category()!=null){visible &= s.category()==haircut&&n%2==0;}
            if(s.keyword()!=null){visible &= switch(s.name()){case "common"->n%997!=0;case "rare"->n%997==0;default->false;};}
            if(visible){ids.add(BASE+n);}
        }
        assertThat(result.total()).isEqualTo(ids.size());int start=(int)Math.min(s.offset(),ids.size());int end=(int)Math.min(s.offset()+s.size(),ids.size());
        assertThat(result.rows().stream().map(row->((Number)row.get("id")).longValue()).toList()).isEqualTo(ids.subList(start,end));
    }
    private List<String> semanticChecks(int scale,long haircut)throws Exception{
        var checks=new ArrayList<String>();
        for(var scenario:List.of(new Scenario("missing_category",999999L,null,0,20),new Scenario("intersection",haircut,"%稀有%",0,20),
                new Scenario("literal_underscore",null,"%!_%",0,20),new Scenario("literal_percent",null,"%!%%",0,20),
                new Scenario("literal_bang",null,"%!!%",0,20),new Scenario("beyond_end",null,null,scale*6L/10+20,20),
                new Scenario("long_offset",null,null,214748364600L,20),new Scenario("injection_literal",null,"%' OR 1=1 --%",0,20))){
            equivalent(scenario);checks.add(scenario.name());
        }
        long shop=BASE+8;String oldName=jdbc.queryForObject("SELECT name FROM shop WHERE id=?",String.class,shop);
        try{jdbc.update("UPDATE shop SET name='校园百分%下划_惊叹!商店' WHERE id=?",shop);
            for(String pattern:List.of("%!%%","%!_%","%!!%")){equivalent(new Scenario("escaped_existing",null,pattern,0,20));}checks.add("escaped_existing_name");
        }finally{jdbc.update("UPDATE shop SET name=? WHERE id=?",oldName,shop);}
        jdbc.update("UPDATE shop_category SET enabled=FALSE WHERE id=?",haircut);
        try{var result=equivalent(new Scenario("disabled_category",haircut,null,0,20));assertThat(result.total()).isEqualTo(scale*3L/10);checks.add("disabled_category_stays_visible");}
        finally{jdbc.update("UPDATE shop_category SET enabled=TRUE WHERE id=?",haircut);}
        for(String change:List.of("approval","owner_status","account_status","account_role","business")){
            String update=switch(change){case "approval"->"UPDATE shop SET approval_status='SUSPENDED' WHERE id=?";case "owner_status"->"UPDATE shop_member SET status='DISABLED' WHERE shop_id=?";case "account_status"->"UPDATE account SET status='DISABLED' WHERE id=?";case "account_role"->"UPDATE account SET role='STUDENT' WHERE id=?";default->"UPDATE shop SET business_enabled=FALSE WHERE id=?";};
            String restore=switch(change){case "approval"->"UPDATE shop SET approval_status='APPROVED' WHERE id=?";case "owner_status"->"UPDATE shop_member SET status='ACTIVE' WHERE shop_id=?";case "account_status"->"UPDATE account SET status='ACTIVE' WHERE id=?";case "account_role"->"UPDATE account SET role='MERCHANT' WHERE id=?";default->"UPDATE shop SET business_enabled=TRUE WHERE id=?";};
            jdbc.update(update,shop);try{var value=equivalent(new Scenario(change,null,null,0,20));assertThat(value.total()).isEqualTo(scale*6L/10-(change.equals("business")?0:1));checks.add(change);}finally{jdbc.update(restore,shop);}
        }
        try(var executor=Executors.newSingleThreadExecutor()){
            var scenario=new Scenario("snapshot",null,null,0,20);
            for(String variant:VARIANTS){var pair=build(variant,scenario);var before=execute(pair);
                try{
                    read.execute(status->{
                        assertThat(jdbc.queryForObject(pair.count().sql(),Long.class,pair.count().args().toArray())).isEqualTo(before.total());
                        try{executor.submit(()->jdbc.update("UPDATE account SET status='DISABLED' WHERE id=?",shop)).get(10,TimeUnit.SECONDS);}catch(Exception ex){throw new IllegalStateException(ex);}
                        assertThat(jdbc.queryForList(pair.page().sql(),pair.page().args().toArray())).isEqualTo(before.rows());return null;
                    });
                    assertThat(execute(pair).total()).isEqualTo(before.total()-1);checks.add("rr_snapshot_"+variant);
                }finally{jdbc.update("UPDATE account SET status='ACTIVE' WHERE id=?",shop);}
            }
        }
        return checks;
    }
    private Result equivalent(Scenario s){var original=execute(build("original",s));for(String variant:VARIANTS){assertThat(execute(build(variant,s))).as(s.name()+" "+variant).isEqualTo(original);}return original;}
    private static Map<String,Object> stats(List<Double> samples){var sorted=samples.stream().sorted().toList();return Map.of("n",samples.size(),"p50",sorted.get((int)Math.ceil(samples.size()*.5)-1),"p95",sorted.get((int)Math.ceil(samples.size()*.95)-1),"min",sorted.getFirst(),"max",sorted.getLast(),"mean",samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow());}
}
