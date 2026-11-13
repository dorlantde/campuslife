package campuslife.measurement;

import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/** Runs only inside the separately attested, newly created MySQL fixture network. */
public final class V24FixtureChecks {
    static final List<String> checks=new ArrayList<>();
    static void check(String name,boolean value){
        V24MeasurementFixture.require(value,name);checks.add(name);
        try{Files.writeString(Path.of("/output/progress.json"),new ObjectMapper().writeValueAsString(checks));}
        catch(Exception error){throw new IllegalStateException("PROGRESS_WRITE_FAILED");}
    }
    static void refused(String name,String code,Runnable action){
        try{action.run();throw new AssertionError("REFUSAL_MISSING");}
        catch(V24MeasurementFixture.Refused error){check(name,code.equals(error.getMessage()));}
    }
    static void migrate(V24MeasurementFixture f,int version,Path passwordFile){
        try{
            Flyway.configure().dataSource(V24MeasurementFixture.URL,"campuslife",Files.readString(passwordFile).strip())
                .locations("filesystem:/tool/migrations").target(Integer.toString(version)).load().migrate();
        }catch(Exception error){throw new IllegalStateException("MIGRATION_FAILED");}
    }
    public static void main(String[] args){
        try{run(Path.of(args[0]));}
        catch(Throwable error){
            String code=error instanceof V24MeasurementFixture.Refused?error.getMessage():"FIXTURE_CHECKS_FAILED";
            System.out.println("{\"passed\":false,\"failureType\":\""+error.getClass().getSimpleName()+"\",\"code\":\""+code+"\"}");
            System.exit(1);
        }
    }
    static void run(Path input)throws Exception{
        long started=System.nanoTime();
        try(var f=new V24MeasurementFixture(input)){
            f.identity(f.permit);check("owned-server-identity-before-ddl",true);
            check("empty-initial-schema",f.tables().isEmpty());
            for(var pair:List.of(
                    Map.entry("kind","base"),Map.entry("instanceId","bad"),Map.entry("caseId","bad"),
                    Map.entry("project","other"),Map.entry("volumeName","other"),Map.entry("database","campuslife"),
                    Map.entry("serverUuid","00000000-0000-0000-0000-000000000000"),Map.entry("serverHostname","other"),
                    Map.entry("mysqlContainerId","bad"),Map.entry("profile","other"))){
                String code=Map.of("kind","TEST_ONLY","instanceId","INSTANCE_ID","caseId","CASE_ID","project","PROJECT_ID","volumeName","VOLUME_ID",
                        "database","DATABASE_ID","serverUuid","SERVER_ID","serverHostname","SERVER_HOSTNAME","mysqlContainerId","CONTAINER_ID","profile","PROFILE").get(pair.getKey());
                var wrong=new LinkedHashMap<>(f.permit);wrong.put(pair.getKey(),pair.getValue());
                refused("refuse-"+pair.getKey(),code,()->f.identity(wrong));
            }
            check("identity-refusals-perform-no-ddl",f.tables().isEmpty());
            migrate(f,23,input.getParent().resolve("DB_PASSWORD"));
            refused("reject-real-v23-schema","V24_REQUIRED",f::emptyGuard);
            check("v23-refusal-keeps-accounts-empty",f.count("SELECT COUNT(*) FROM account")==0);
            migrate(f,24,input.getParent().resolve("DB_PASSWORD"));f.emptyGuard();
            check("v24-empty-business-guard",true);
            try{f.seed(true);throw new AssertionError("ROLLBACK_MISSING");}
            catch(V24MeasurementFixture.Refused error){check("injected-rollback-observed",error.getMessage().equals("INJECTED_ROLLBACK"));}
            f.emptyGuard();check("all-business-writes-rolled-back",true);
            f.seed(false);f.verify();check("full-scale-relations-and-paused-campaigns",true);
            check("all-180-students-use-generated-bcrypt-hash",f.count("SELECT COUNT(DISTINCT password_hash) FROM account WHERE role='STUDENT'")==1
                    && new BCryptPasswordEncoder().matches(f.password,f.jdbc.queryForObject("SELECT password_hash FROM account WHERE id=900001",String.class)));
            check("student-password-does-not-login-as-admin",!new BCryptPasswordEncoder().matches(f.password,f.jdbc.queryForObject("SELECT password_hash FROM account WHERE id=900000",String.class)));
            refused("refuse-nonempty-reseed","NONEMPTY_DATABASE",f::emptyGuard);
            try{f.seed(false);throw new AssertionError("RESEED_MISSING");}
            catch(V24MeasurementFixture.Refused error){check("seed-rechecks-nonempty-database",error.getMessage().equals("NONEMPTY_DATABASE"));}
            f.verify();check("reseed-refusal-preserves-existing-facts",true);
            var expected=f.expected();var json=new ObjectMapper();
            Path output=Path.of("/output/expected.json");Files.writeString(output,json.writeValueAsString(expected));
            var result=new LinkedHashMap<String,Object>();result.put("task","M06.2-B.2-A");result.put("passed",true);result.put("checks",checks);
            result.put("elapsedSeconds",(System.nanoTime()-started)/1e9);result.put("counts",expected.get("counts"));
            result.put("profile",f.permit.get("profile"));result.put("java",System.getProperty("java.version"));result.put("maxHeap",Runtime.getRuntime().maxMemory());
            result.put("productionContextStarted",false);result.put("measuredHttpRequests",0);
            Files.writeString(Path.of("/output/checks.json"),json.writeValueAsString(result));
            System.out.println(json.writeValueAsString(result));
        }
    }
}
