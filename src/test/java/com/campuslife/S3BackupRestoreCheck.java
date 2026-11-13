package com.campuslife;

import com.campuslife.auth.security.AuthPrincipal;
import com.campuslife.auth.vo.AccountView;
import com.campuslife.file.entity.FileAssetEntity;
import com.campuslife.file.mapper.FileAssetMapper;
import com.campuslife.file.service.*;
import com.campuslife.file.storage.*;
import com.campuslife.file.vo.FileView;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

/** Standalone: every Spring application is explicitly closed before offline backup or rehearsal writes. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="CAMPUSLIFE_S3_BUSINESS_IT",matches="owned-isolated-fixture")
class S3BackupRestoreCheck {
    static final Instant NOW=Instant.parse("2026-11-02T01:00:00Z");
    final ObjectMapper json=new ObjectMapper();final HttpClient http=HttpClient.newHttpClient();
    static String env(String n){return S3RestoreResources.env(n);}
    static String fixture(String n){return env("S3_PROBE_"+n);}
    static final String PASSWORD="Isolated_Recovery_Test123!";
    @Configuration(proxyBeanMethods=false) @Profile("s3-backup-test")
    static class ClockConfig {@Bean @Primary Clock backupClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
    ConfigurableApplicationContext start(String endpoint,Path local,String url,String password) {
        var app=new SpringApplication(CampusLifeApplication.class,ClockConfig.class);app.setAdditionalProfiles("mysql","s3-backup-test");
        var settings=new HashMap<String,Object>();settings.put("server.address","127.0.0.1");settings.put("server.port","0");
        settings.put("campuslife.files.root",local.toString());settings.put("campuslife.files.upload-target","S3");settings.put("campuslife.files.s3.enabled",true);
        settings.put("campuslife.files.s3.endpoint",endpoint);settings.put("campuslife.files.s3.bucket",fixture("BUCKET"));settings.put("campuslife.files.s3.namespace",fixture("NAMESPACE"));
        settings.put("campuslife.files.s3.region","us-east-1");settings.put("campuslife.files.s3.access-key",fixture("APP_KEY"));settings.put("campuslife.files.s3.secret-key",fixture("APP_SECRET"));
        for(String setting:List.of("files.cleanup-enabled","booking.expiry-enabled","print.expiry-enabled","coupon.expiry-enabled","messaging.enabled","coupon-redis.enabled","rating-cache.enabled")){settings.put("campuslife."+setting,false);}
        settings.put("campuslife.mock-payment.enabled",true);
        if(url!=null){settings.put("spring.datasource.url",url);settings.put("spring.datasource.username","campuslife");settings.put("spring.datasource.password",password);}
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-restore-settings",settings)));
        return app.run();
    }
    S3Client admin(String endpoint) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(fixture("ADMIN_KEY"),fixture("ADMIN_SECRET"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
                .httpClientBuilder(Apache5HttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)).proxyConfiguration(software.amazon.awssdk.http.apache5.ProxyConfiguration.builder().useEnvironmentVariableValues(false).useSystemPropertyValues(false).build()))
                .overrideConfiguration(b->b.apiCallTimeout(Duration.ofSeconds(10))).build();
    }
    void waitStore(S3Client admin)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<end){try{admin.listBuckets();return;}catch(Exception ex){Thread.sleep(100);}}throw new AssertionError("Owned S3 ready");}
    void initialize(S3Client admin)throws Exception {
        waitStore(admin);String bucket=fixture("BUCKET"),ns=fixture("NAMESPACE");admin.createBucket(b->b.bucket(bucket));admin.putObject(b->b.bucket(bucket).key("_storage-id"),RequestBody.fromString(ns));
        String policy=json.writeValueAsString(Map.of("Version","2012-10-17","Statement",List.of(
                Map.of("Effect","Deny","Principal","*","Action","s3:DeleteObject","Resource",List.of("arn:aws:s3:::"+bucket+"/"+ns+"/objects/","arn:aws:s3:::"+bucket+"/"+ns+"/objects","arn:aws:s3:::"+bucket+"/"+ns+"/")),
                Map.of("Effect","Deny","Principal","*","Action","s3:GetObject","Resource","arn:aws:s3:::"+bucket+"/_storage-id?*"))));admin.putBucketPolicy(b->b.bucket(bucket).policy(policy));
        admin.putObject(b->b.bucket(bucket).key("unmanaged/backup-sentinel"),RequestBody.fromString("whole-volume-backup-includes-foreign-prefix"));
    }
    record User(long id,String name,String token){}
    record Scenario(User student,User merchant,User admin,long shop,String s3File,String localFile,String s3Order,String localOrder,byte[] pdf){}
    User user(ConfigurableApplicationContext c,String role)throws Exception {
        var jdbc=c.getBean(JdbcTemplate.class);String name="restore_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        jdbc.update("INSERT INTO account(username,password_hash,nickname,role,status,version) VALUES (?,?,?,?,'ACTIVE',0)",name,c.getBean(PasswordEncoder.class).encode(PASSWORD),"虚构恢复用户",role);
        long id=jdbc.queryForObject("SELECT id FROM account WHERE username=?",Long.class,name);String token=data(call(c,"POST","/api/v1/auth/login",Map.of("username",name,"password",PASSWORD),null)).path("accessToken").asString();return new User(id,name,token);
    }
    AuthPrincipal principal(ConfigurableApplicationContext c,User u) {
        String jti=c.getBean(JdbcTemplate.class).queryForObject("SELECT jti FROM auth_session WHERE account_id=? ORDER BY created_at DESC LIMIT 1",String.class,u.id());
        return new AuthPrincipal(u.id(),jti,new AccountView(Long.toString(u.id()),u.name(),"虚构恢复用户","STUDENT","ACTIVE","0"));
    }
    Scenario seed(ConfigurableApplicationContext c)throws Exception {
        var student=user(c,"STUDENT");var merchant=user(c,"MERCHANT");var admin=user(c,"ADMIN");var jdbc=c.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO shop(category_id,name,campus_code,location_text,description,approval_status,business_enabled,version) SELECT id,'隔离恢复打印店','XINGHAI_MAIN','虚构','', 'APPROVED',1,0 FROM shop_category WHERE code='PRINT'");
        long shop=jdbc.queryForObject("SELECT MAX(id) FROM shop",Long.class);jdbc.update("INSERT INTO shop_member(shop_id,account_id,status) VALUES (?,?,'ACTIVE')",shop,merchant.id());
        jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) VALUES (?,1,'08:00','22:00')",shop);
        jdbc.update("INSERT INTO service_item(shop_id,service_type,name,description,price_amount,duration_minutes,enabled,version) VALUES (?,'PRINT','恢复测试打印','',100,NULL,1,0)",shop);
        long service=jdbc.queryForObject("SELECT MAX(id) FROM service_item WHERE shop_id=?",Long.class,shop);
        jdbc.update("INSERT INTO print_price_rule(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled,version) VALUES (?,'A4','BW','DOUBLE',100,1,0)",service);
        byte[] pdf=com.campuslife.file.PdfSamples.pdf(2);var part=new org.springframework.mock.web.MockMultipartFile("file","restore.pdf","application/pdf",pdf);
        var s3=c.getBean(FileUploadService.class).upload(principal(c,student),part);
        var localFacade=new FileStorage(c.getBean(LocalFileStorage.class),c.getBeanProvider(S3FileStorage.class),"LOCAL");
        var localUploader=new FileUploadService(c.getBean(LocalFileStorage.class),localFacade,c.getBean(PdfInspector.class),c.getBean(FileTransactions.class),c.getBean(FileCleanup.class));
        var local=localUploader.upload(principal(c,student),part);
        String s3Order=create(c,student,service,s3.id()),localOrder=create(c,student,service,local.id());
        data(call(c,"POST","/api/v1/orders/"+s3Order+"/payments/mock",null,student.token()));
        for(String action:List.of("accept","start","ready-for-pickup")){data(call(c,"POST","/api/v1/merchant/orders/"+s3Order+"/"+action,null,merchant.token()));}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM message_outbox",Integer.class)).isEqualTo(1);
        return new Scenario(student,merchant,admin,shop,s3.id(),local.id(),s3Order,localOrder,pdf);
    }
    String create(ConfigurableApplicationContext c,User u,long service,String file)throws Exception {
        var body=Map.of("serviceId",Long.toString(service),"fileId",file,"paperSize","A4","colorMode","BW","sideMode","DOUBLE","copies",2,"expectedPriceVersion","0","expectedPayableAmount",400);
        return data(call(c,"POST","/api/v1/orders/prints",body,u.token())).path("id").asString();
    }
    HttpResponse<String> call(ConfigurableApplicationContext c,String method,String path,Object body,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+c.getEnvironment().getRequiredProperty("local.server.port")+path)).timeout(Duration.ofSeconds(30));if(token!=null){b.header("Authorization","Bearer "+token);}if(body!=null){b.header("Content-Type","application/json");}if(path.endsWith("/prints")){b.header("Idempotency-Key",UUID.randomUUID().toString());}
        return http.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isIn(200,201);return json.readTree(r.body()).path("data");}
    void download(ConfigurableApplicationContext c,String file,String token,byte[] expected)throws Exception {
        var r=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+c.getEnvironment().getRequiredProperty("local.server.port")+"/api/v1/files/"+file+"/content")).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(r.statusCode()).isEqualTo(200);assertThat(r.body()).isEqualTo(expected);
    }
    Map<String,Object> snapshot(JdbcTemplate jdbc) {
        var result=new TreeMap<String,Object>();
        for(String table:jdbc.queryForList("SHOW TABLES",String.class)) {
            assertThat(table).matches("[a-z_]+|flyway_schema_history");
            var keys=jdbc.queryForList("SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=DATABASE() AND table_name=? AND constraint_name='PRIMARY' ORDER BY ordinal_position",String.class,table);
            String order=keys.stream().map(k->"`"+k+"`").collect(java.util.stream.Collectors.joining(","));
            var rows=new ArrayList<Map<String,String>>();for(var row:jdbc.queryForList("SELECT * FROM `"+table+"` ORDER BY "+order)){var fields=new LinkedHashMap<String,String>();row.forEach((k,v)->fields.put(k,v==null?null:v instanceof byte[] bytes?HexFormat.of().formatHex(bytes):v.toString()));rows.add(fields);}result.put(table,rows);
        }return result;
    }
    String sha(byte[] bytes)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    Map<String,String> tree(Path root)throws Exception {var values=new TreeMap<String,String>();try(var paths=Files.walk(root)){for(var p:paths.filter(Files::isRegularFile).toList()){values.put(root.relativize(p).toString(),sha(Files.readAllBytes(p)));}}return values;}
    @Test void offlineFullBackupRestoreAndLocalToS3RehearsalPreserveOldOrders()throws Exception {
        Path root=Path.of(env("S3_RECOVERY_OUTPUT"),"restore-"+UUID.randomUUID().toString().replace("-","")).toAbsolutePath();
        Path local=Path.of(env("FILE_STORAGE_ROOT")).toAbsolutePath().normalize();assertThat(local.startsWith(Path.of(".cache/mysql-it").toAbsolutePath().normalize())).isTrue();
        Map<String,Object> before;Map<String,String> localBefore;Scenario scenario;
        try(var resources=new S3RestoreResources(root)) {
            resources.sourceGuard();try(var admin=admin(fixture("ENDPOINT"))){initialize(admin);}
            var source=start(fixture("ENDPOINT"),local,null,null);
            try {scenario=seed(source);before=snapshot(source.getBean(JdbcTemplate.class));}finally{source.close();}
            assertThat(source.isActive()).isFalse();localBefore=tree(local);
            resources.dumpDatabase();S3RestoreResources.copyTree(local,root.resolve("local-backup"));resources.snapshotStorage();
            assertThat(tree(root.resolve("local-backup"))).isEqualTo(localBefore);
            resources.restoreDatabase();resources.restoreStorage();Path restoredLocal=root.resolve("restored-local");S3RestoreResources.copyTree(root.resolve("local-backup"),restoredLocal);
            try(var a=admin(resources.endpoint())){waitStore(a);assertThat(a.getObjectAsBytes(b->b.bucket(fixture("BUCKET")).key("unmanaged/backup-sentinel")).asUtf8String()).isEqualTo("whole-volume-backup-includes-foreign-prefix");}
            Map<String,Object> beforeMigration;
            var restored=start(resources.endpoint(),restoredLocal,resources.sqlUrl(),resources.dbPassword);
            try {
                var restoredSnapshot=snapshot(restored.getBean(JdbcTemplate.class));assertThat(restoredSnapshot.keySet()).isEqualTo(before.keySet());
                for(String table:before.keySet()){assertThat(sha(json.writeValueAsBytes(restoredSnapshot.get(table)))).as("restored table "+table).isEqualTo(sha(json.writeValueAsBytes(before.get(table))));}
                assertThat(tree(restoredLocal)).isEqualTo(localBefore);
                download(restored,scenario.s3File(),scenario.student().token(),scenario.pdf());download(restored,scenario.localFile(),scenario.student().token(),scenario.pdf());download(restored,scenario.s3File(),scenario.merchant().token(),scenario.pdf());
                assertThat(data(call(restored,"GET","/api/v1/orders/"+scenario.s3Order(),null,scenario.student().token())).path("status").asString()).isEqualTo("IN_SERVICE");
                String appUrl="http://127.0.0.1:"+restored.getEnvironment().getRequiredProperty("local.server.port");
                assertThat(http.send(HttpRequest.newBuilder(URI.create(appUrl+"/swagger-ui/index.html")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
                long manual=Long.parseLong(System.getenv().getOrDefault("S3_RECOVERY_MANUAL_SECONDS","0"));
                if(manual>0){
                    var manifest=Map.of("swagger",appUrl+"/swagger-ui/index.html","student",scenario.student().name(),"merchant",scenario.merchant().name(),"admin",scenario.admin().name(),"password",PASSWORD,"s3File",scenario.s3File(),"localFile",scenario.localFile(),"s3Order",scenario.s3Order(),"localOrder",scenario.localOrder());
                    Files.writeString(Path.of(env("S3_RECOVERY_OUTPUT"),"manual.json"),json.writeValueAsString(manifest));
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(manual);while(System.nanoTime()<deadline&&!Files.exists(Path.of(env("S3_RECOVERY_OUTPUT"),"end-manual"))){Thread.sleep(200);}
                }
                beforeMigration=snapshot(restored.getBean(JdbcTemplate.class));
            }finally{restored.close();}
            assertThat(restored.isActive()).isFalse();
            // Test-only offline rehearsal, scoped to this restored DB/root. No production Apply command is shipped.
            var dataSource=new org.springframework.jdbc.datasource.DriverManagerDataSource(resources.sqlUrl(),"campuslife",resources.dbPassword);var jdbc=new JdbcTemplate(dataSource);
            long id=Long.parseLong(scenario.localFile());var old=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id);String oldId=old.get("storage_id").toString(),key=old.get("storage_key").toString();long version=((Number)old.get("version")).longValue();
            assertThat(old.get("storage_kind")).isEqualTo("LOCAL");assertThat(old.get("status")).isEqualTo("AVAILABLE");
            var plan=new LinkedHashMap<String,Object>();plan.put("fileId",scenario.localFile());plan.put("from",Map.of("kind","LOCAL","namespace",oldId,"key",key));plan.put("to",Map.of("kind","S3","namespace",fixture("NAMESPACE"),"key",key));plan.put("expectedVersion",version);plan.put("bytes",old.get("byte_size"));plan.put("sha256",old.get("sha256"));plan.put("expiresAt",old.get("expires_at")==null?null:old.get("expires_at").toString());
            Files.writeString(root.resolve("migration-plan.json"),json.writeValueAsString(plan));
            var staging=new LocalFileStorage(restoredLocal.toString(),Clock.fixed(NOW,ZoneOffset.UTC));
            try(var copyProxy=new S3FaultProxy(resources.endpoint());var remote=new S3FileStorage(new com.campuslife.file.config.S3StorageProperties(true,copyProxy.endpoint(),"us-east-1",fixture("BUCKET"),fixture("NAMESPACE"),fixture("APP_KEY"),fixture("APP_SECRET")))) {
                var providers=new org.springframework.beans.factory.support.DefaultListableBeanFactory();providers.registerSingleton("remote",remote);var store=new FileStorage(staging,providers.getBeanProvider(S3FileStorage.class),"S3");
                var target=new FileAssetEntity();target.setStorageKind("S3");target.setStorageId(fixture("NAMESPACE"));target.setStorageKey(key);target.setByteSize(((Number)old.get("byte_size")).longValue());
                LocalFileStorage.Received copied;
                try(var original=staging.open(oldId,key,target.getByteSize())){copied=staging.receive(original);}
                try {
                    assertThat(copied.sha256()).isEqualTo(old.get("sha256"));
                    copyProxy.fault=S3FaultProxy.Fault.DROP_PUT_REPLY;
                    assertThatThrownBy(()->store.write(target,copied)).isInstanceOf(IOException.class);
                    assertThat(copyProxy.forwardedPuts.get()).isGreaterThan(0);
                    assertThat(jdbc.queryForObject("SELECT storage_kind FROM file_asset WHERE id=?",String.class,id)).isEqualTo("LOCAL");
                    copyProxy.fault=S3FaultProxy.Fault.NONE;
                    store.write(target,copied); // same fixed key/bytes, then full readback; never delete on unknown outcome.
                    Files.writeString(root.resolve("migration-copy.json"),json.writeValueAsString(Map.of("lostRealPutReply",true,"forwardedPuts",copyProxy.forwardedPuts.get(),"fullReadbackVerified",true)));
                }finally{staging.releaseTemporary(copied.path());}
                // Commit outcome is resolved by rereading facts; replay cannot advance the version twice.
                int changed=jdbc.update("UPDATE file_asset SET storage_kind='S3',storage_id=?,version=version+1 WHERE id=? AND storage_kind='LOCAL' AND storage_id=? AND storage_key=? AND version=? AND status='AVAILABLE'",fixture("NAMESPACE"),id,oldId,key,version);assertThat(changed).isEqualTo(1);
                assertThat(jdbc.update("UPDATE file_asset SET storage_kind='S3',storage_id=?,version=version+1 WHERE id=? AND storage_kind='LOCAL' AND storage_id=? AND storage_key=? AND version=? AND status='AVAILABLE'",fixture("NAMESPACE"),id,oldId,key,version)).isZero();
                var current=jdbc.queryForMap("SELECT * FROM file_asset WHERE id=?",id);var expected=new LinkedHashMap<>(old);expected.put("storage_kind","S3");expected.put("storage_id",fixture("NAMESPACE"));expected.put("version",version+1);assertThat(current).isEqualTo(expected);
                var after=snapshot(jdbc);for(String table:beforeMigration.keySet()){if(!table.equals("file_asset")){assertThat(after.get(table)).as(table+" unchanged by migration").isEqualTo(beforeMigration.get(table));}}
                try(var stream=staging.open(oldId,key,target.getByteSize())){assertThat(stream.readAllBytes()).isEqualTo(scenario.pdf());}
            }
            try(var migrated=start(resources.endpoint(),restoredLocal,resources.sqlUrl(),resources.dbPassword)) {
                download(migrated,scenario.localFile(),scenario.student().token(),scenario.pdf());download(migrated,scenario.s3File(),scenario.merchant().token(),scenario.pdf());
                data(call(migrated,"POST","/api/v1/orders/"+scenario.localOrder()+"/cancel",null,scenario.student().token()));
                assertThat(migrated.getBean(FileAssetMapper.class).selectById(id).getExpiresAt()).isEqualTo(java.time.LocalDateTime.ofInstant(NOW.plus(Duration.ofDays(7)),ZoneOffset.UTC));
            }
            assertThat(tree(local)).isEqualTo(localBefore);
            var verified=new LinkedHashMap<String,Object>(Map.of("sourceApplicationsClosed",true,"restoredTablesIncludingFlyway",before.size(),"localFiles",localBefore.size(),"wholeVolumeRestored",true,"newSqlPort",resources.sqlPort,"newS3Port",resources.s3Port,"offlineRehearsal",true,"originalLocalBytesRetained",true));
            verified.put("planSha256",sha(Files.readAllBytes(root.resolve("migration-plan.json"))));verified.put("storageArchiveSha256",sha(Files.readAllBytes(root.resolve("storage.tgz"))));verified.put("databaseDumpSha256",sha(Files.readAllBytes(root.resolve("database.sql"))));Files.writeString(root.resolve("verified.json"),json.writeValueAsString(verified));
        }
    }
}
