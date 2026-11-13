package campuslife.measurement;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.math.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;

/** Explicit isolated measurement seed, not part of the Maven production/test source sets. */
public final class V24MeasurementFixture implements AutoCloseable {
    static final long BASE=1_000_000, ADMIN=900_000;
    static final String URL="jdbc:mysql://mysql:3306/campuslife_deploy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=3000&socketTimeout=15000";
    static final String VISIBLE=" FROM shop s JOIN shop_member m ON m.shop_id=s.id AND m.member_role='OWNER' JOIN account a ON a.id=m.account_id WHERE s.approval_status='APPROVED' AND m.status='ACTIVE' AND a.status='ACTIVE' AND a.role='MERCHANT'";
    final Connection connection;
    final JdbcTemplate jdbc;
    final Map<String,Object> permit;
    final String password;
    long haircut,print;
    private String hash;

    static final class Refused extends RuntimeException {
        Refused(String code){super(code);}
    }
    static void require(boolean condition,String code){if(!condition)throw new Refused(code);}
    public V24MeasurementFixture(Path input)throws Exception {
        require(!Files.isSymbolicLink(input),"PERMIT_SYMLINK");
        permit=new ObjectMapper().readValue(Files.readString(input),Map.class);
        password=Files.readString(input.getParent().resolve("STUDENT_PASSWORD")).strip();
        String dbPassword=Files.readString(input.getParent().resolve("DB_PASSWORD")).strip();
        connection=DriverManager.getConnection(URL,"campuslife",dbPassword);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.setQueryTimeout(15);
    }
    long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    void identity(Map<String,Object> value){
        require(Integer.valueOf(1).equals(value.get("schema")),"PERMIT_SCHEMA");
        require("test".equals(value.get("kind")),"TEST_ONLY");
        String id=Objects.toString(value.get("instanceId"),"");
        require(id.matches("[a-f0-9]{32}"),"INSTANCE_ID");
        require(Objects.toString(value.get("caseId"),"").matches("case-[a-f0-9]{12,32}"),"CASE_ID");
        require(("campuslife-base-"+id).equals(value.get("project")),"PROJECT_ID");
        require(("campuslife-base-"+id+"_mysql-data").equals(value.get("volumeName")),"VOLUME_ID");
        require(Objects.toString(value.get("mysqlContainerId"),"").matches("[a-f0-9]{64}"),"CONTAINER_ID");
        require("campuslife_deploy".equals(value.get("database")) && "campuslife_deploy".equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_ID");
        require(Objects.toString(value.get("serverUuid"),"").matches("[a-f0-9-]{36}") && value.get("serverUuid").equals(jdbc.queryForObject("SELECT @@server_uuid",String.class)),"SERVER_ID");
        require(Objects.equals(value.get("serverHostname"),jdbc.queryForObject("SELECT @@hostname",String.class)),"SERVER_HOSTNAME");
        require("/var/lib/mysql/".equals(jdbc.queryForObject("SELECT @@datadir",String.class)),"DATA_DIRECTORY");
        require("FORMAL".equals(value.get("profile"))||"PILOT".equals(value.get("profile")),"PROFILE");
    }
    List<String> tables(){
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class);
    }
    void emptyGuard(){
        identity(permit);
        require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0")==0 && count("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")==24,"V24_REQUIRED");
        require(tables().size()==32,"TABLE_COUNT");
        require(count("SELECT COUNT(*) FROM shop_category")==2 && count("SELECT COUNT(*) FROM shop_category WHERE code IN ('HAIRCUT','PRINT') AND enabled=1")==2,"BASE_DICTIONARY");
        for(String table:tables())if(!table.equals("shop_category")) require(count("SELECT COUNT(*) FROM `"+table+"`")==0,"NONEMPTY_DATABASE");
    }
    void seed(boolean injectRollback)throws Exception {
        identity(permit);
        // Serialize this trusted tool's seeding, then recheck emptiness on the live connection.
        String lock="m062-fixture-"+permit.get("instanceId");
        require(jdbc.queryForObject("SELECT GET_LOCK(?,0)",Integer.class,lock)==1,"SEED_BUSY");
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            emptyGuard();
            require(password.matches("[a-f0-9]{64}"),"GENERATED_PASSWORD_REQUIRED");
            hash=new BCryptPasswordEncoder().encode(password);
            String inaccessibleHash=new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
            haircut=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class);
            print=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='PRINT'",Long.class);
            jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,created_at,updated_at) VALUES(?,'measurement_admin',?,'虚构测量管理员','ADMIN','ACTIVE','2024-01-01','2024-01-01')",ADMIN,inaccessibleHash);
            jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,created_at,updated_at) SELECT ?+n,CONCAT('measurement_student_',n),?,CONCAT('虚构测量学生',n),'STUDENT','ACTIVE','2024-01-01','2024-01-01' FROM "+numbers(1,180),ADMIN,hash);
            seed(1,1000,inaccessibleHash);seedOrders("s.shop_id="+(BASE+6),20,1019);
            jdbc.update("INSERT INTO shop_rating_revision(shop_id,revision) SELECT DISTINCT shop_id,1 FROM order_review");
            int campaigns=permit.get("profile").equals("PILOT")?1:3;
            int stock=campaigns==1?2:20;
            for(int i=1;i<=campaigns;i++){
                jdbc.update("""
                    INSERT INTO coupon_campaign(id,shop_id,name,threshold_amount,reduction_amount,total_quantity,status,
                    claim_start_at,claim_end_at,use_start_at,use_end_at,version,created_at,updated_at,published_at,published_by,publish_expected_version)
                    VALUES(?,?,'虚构测量满减',3000,500,?,'PUBLISHED',UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 2 HOUR,
                    UTC_TIMESTAMP(3)-INTERVAL 2 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 3 HOUR,1,UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,0)
                    """,2_000_000+i,BASE+6,stock,BASE+6);
                jdbc.update("INSERT INTO coupon_dispatch_gate(campaign_id,mode,phase) VALUES(?,'ASYNC','PAUSED')",2_000_000+i);
            }
            verify();
            if(injectRollback)throw new Refused("INJECTED_ROLLBACK");
            connection.commit();
        } catch(Exception error){connection.rollback();throw error;}
        finally {connection.setAutoCommit(true);jdbc.queryForObject("SELECT RELEASE_LOCK(?)",Integer.class,lock);}
        // ANALYZE may implicitly commit: deliberately outside the seed transaction.
        for(String table:List.of("account","shop","shop_member","shop_business_hour","service_order","order_review"))jdbc.execute("ANALYZE TABLE "+table);
    }
    void verify(){
        require(count("SELECT COUNT(*) FROM shop")==1000 && count("SELECT COUNT(*) "+VISIBLE)==600,"SHOP_DISTRIBUTION");
        require(count("SELECT COUNT(*) FROM shop s JOIN shop_category c ON c.id=s.category_id WHERE c.code='HAIRCUT'")==500,"CATEGORY_DISTRIBUTION");
        require(count("SELECT COUNT(*) FROM service_order")==11000 && count("SELECT COUNT(*) FROM order_review")==11000 && count("SELECT COUNT(*) FROM order_event")==55000,"HISTORY_COUNTS");
        require(count("SELECT COUNT(*) FROM order_review r JOIN service_order o ON o.id=r.order_id JOIN booking_detail b ON b.order_id=o.id JOIN payment_record p ON p.order_id=o.id WHERE o.status='COMPLETED' AND o.payment_status='PAID' AND b.occupancy_status='CONSUMED' AND r.student_id=o.student_id AND r.shop_id=o.shop_id AND p.amount=o.payable_amount AND r.created_at>=o.completed_at")==11000,"HISTORY_RELATIONS");
        require(count("SELECT COUNT(*) FROM booking_detail b JOIN barber_schedule x ON x.barber_id=b.barber_id AND x.service_date=b.service_date WHERE b.start_at>=x.start_at AND b.end_at<=x.end_at AND DATE(b.start_at+INTERVAL 8 HOUR)=b.service_date")==11000,"SCHEDULE_RELATIONS");
        require(count("SELECT COUNT(*) FROM booking_detail a JOIN booking_detail b ON a.barber_id=b.barber_id AND a.order_id<b.order_id AND a.start_at<b.end_at AND b.start_at<a.end_at")==0,"NO_BOOKING_OVERLAP");
        require(count("SELECT COUNT(DISTINCT student_id) FROM service_order")==180 && count("SELECT COUNT(*) FROM account WHERE role='STUDENT' AND status='ACTIVE'")==180,"STUDENT_HISTORY");
        require(count("SELECT COUNT(*) FROM order_review WHERE visibility='HIDDEN'")>0 && count("SELECT COUNT(*) FROM shop_rating_revision")==500,"RATING_METADATA");
        int campaigns=permit.get("profile").equals("PILOT")?1:3;int stock=campaigns==1?2:20;
        require(count("SELECT COUNT(*) FROM coupon_campaign c JOIN coupon_dispatch_gate g ON g.campaign_id=c.id WHERE c.status='PUBLISHED' AND c.total_quantity="+stock+" AND c.issued_count=0 AND c.claim_start_at<=UTC_TIMESTAMP(3) AND c.claim_end_at>UTC_TIMESTAMP(3)+INTERVAL 30 MINUTE AND g.mode='ASYNC' AND g.phase='PAUSED' AND g.generation=1 AND g.control_version=0 AND g.rebuild_token IS NULL AND g.resume_phase IS NULL")==campaigns,"CAMPAIGN_PAUSED");
        for(String table:List.of("auth_session","user_coupon","coupon_claim_request","message_outbox","message_consumption","refund_record","file_asset"))require(count("SELECT COUNT(*) FROM "+table)==0,"NO_LIVE_TRANSACTION_FACTS");
        List<Long> actual=jdbc.queryForList("SELECT s.id"+VISIBLE+" ORDER BY s.id",Long.class);
        List<Long> expected=new ArrayList<>();for(int n=1;n<=1000;n++)if(n%10==0||n%10>=5)expected.add(BASE+n);
        require(actual.equals(expected),"VISIBLE_IDS");
    }
    Map<String,Object> expected(){
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("caseId",permit.get("caseId"));result.put("instanceId",permit.get("instanceId"));result.put("profile",permit.get("profile"));
        result.put("syntheticSqlHistory",true);result.put("businessJourneyExecuted",false);result.put("gateState","ASYNC/PAUSED");
        Map<String,Long> counts=new LinkedHashMap<>();for(String table:tables())counts.put(table,count("SELECT COUNT(*) FROM `"+table+"`"));result.put("counts",counts);
        List<Map<String,Object>> students=new ArrayList<>();
        for(int n=1;n<=180;n++)students.add(Map.of("id",ADMIN+n,"username","measurement_student_"+n,"group",(n-1)/60+1,
                "total",count("SELECT COUNT(*) FROM service_order WHERE student_id="+(ADMIN+n)),
                "orderIds",jdbc.queryForList("SELECT id FROM service_order WHERE student_id=? ORDER BY created_at DESC,id DESC LIMIT 20",Long.class,ADMIN+n)));
        result.put("students",students);
        result.put("detailShopIds",List.of(BASE+6,BASE+10,BASE+16,BASE+20,BASE+26,BASE+30));
        result.put("ratings",jdbc.queryForList("SELECT shop_id,COUNT(*) AS review_count,SUM(rating) AS rating_sum,ROUND(AVG(rating),2) AS rating_average FROM order_review WHERE visibility='VISIBLE' GROUP BY shop_id ORDER BY shop_id"));
        result.put("publicShopIds",jdbc.queryForList("SELECT s.id"+VISIBLE+" ORDER BY s.id",Long.class));
        result.put("campaigns",jdbc.queryForList("SELECT id,total_quantity,claim_end_at,use_end_at FROM coupon_campaign ORDER BY id"));
        return result;
    }
    @Override public void close()throws Exception{connection.close();}
    // SQL relationship construction is adapted from PublicShopQueryFixture, whose guard is untouched.
    private String numbers(int first,int last){
        String values=java.util.stream.IntStream.rangeClosed(first,last).mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(",","[","]"));
        return "JSON_TABLE('"+values+"', '$[*]' COLUMNS(n INT PATH '$')) seq";
    }
    private void seed(int first,int last,String hash){
        String ns=numbers(first,last);
        jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,created_at,updated_at) SELECT ?+n,CONCAT('baseline_owner_',n),?,CONCAT('虚构店主',n),'MERCHANT',IF(MOD(n,10)=3,'DISABLED','ACTIVE'),'2024-01-01','2024-01-01' FROM "+ns,BASE,hash);
        jdbc.update("""
                INSERT INTO shop(id,category_id,name,campus_code,location_text,description,approval_status,reviewed_by,reviewed_at,business_enabled,version,created_at,updated_at)
                SELECT ?+n,IF(MOD(n,2)=0,?,?),CONCAT(IF(MOD(n,997)=0,'稀有','校园'),IF(MOD(n,2)=0,'理发','打印'),'模拟店_',LPAD(n,5,'0')),
                'XINGHAI_MAIN','虚构校园服务区','查询基线合成数据，无真实经营',CASE MOD(n,10) WHEN 1 THEN 'PENDING' WHEN 2 THEN 'SUSPENDED' ELSE 'APPROVED' END,
                IF(MOD(n,10)=1,NULL,?),IF(MOD(n,10)=1,NULL,'2024-01-02'),MOD(n,3)<>0,1,'2024-01-01','2024-01-02' FROM
                """+ns,BASE,haircut,print,ADMIN);
        jdbc.update("INSERT INTO shop_member(shop_id,account_id,status,created_at,updated_at) SELECT id,id,CASE MOD(id-?,10) WHEN 1 THEN 'PENDING' WHEN 4 THEN 'DISABLED' ELSE 'ACTIVE' END,'2024-01-01','2024-01-02' FROM shop WHERE id BETWEEN ? AND ?",BASE,BASE+first,BASE+last);
        jdbc.update("INSERT INTO shop_review(shop_id,reviewed_version,decision,reviewed_by,reviewed_at) SELECT id,0,'APPROVED',?,'2024-01-02' FROM shop WHERE id BETWEEN ? AND ? AND approval_status<>'PENDING'",ADMIN,BASE+first,BASE+last);
        jdbc.update("INSERT INTO shop_business_hour(shop_id,weekday,start_time,end_time) SELECT id,n,'08:00','22:00' FROM shop CROSS JOIN "+numbers(1,7)+" WHERE id BETWEEN ? AND ?",BASE+first,BASE+last);
        jdbc.update("INSERT INTO service_item(id,shop_id,service_type,name,price_amount,duration_minutes,enabled,created_at,updated_at) SELECT id,id,'HAIRCUT','模拟理发',3000,30,TRUE,'2024-01-02','2024-01-02' FROM shop WHERE category_id=? AND id BETWEEN ? AND ?",haircut,BASE+first,BASE+last);
        jdbc.update("INSERT INTO barber(id,shop_id,display_name,enabled,created_at,updated_at) SELECT id,shop_id,'虚构理发师',TRUE,'2024-01-02','2024-01-02' FROM service_item WHERE shop_id BETWEEN ? AND ?",BASE+first,BASE+last);
        jdbc.update("INSERT INTO barber_service(barber_id,service_id) SELECT id,id FROM barber WHERE shop_id BETWEEN ? AND ?",BASE+first,BASE+last);
        seedOrders("s.shop_id BETWEEN "+(BASE+first)+" AND "+(BASE+last),0,19);
    }
    private void seedOrders(String condition,int first,int last){
        String from=" FROM service_item s CROSS JOIN "+numbers(first,last)+" WHERE "+condition;
        String start="TIMESTAMPADD(MINUTE,MOD(n,20)*30,TIMESTAMPADD(DAY,FLOOR(n/20),'2025-01-01 01:00:00'))";
        jdbc.update("INSERT INTO barber_day_guard(barber_id,service_date) SELECT DISTINCT s.id,DATE(TIMESTAMPADD(DAY,FLOOR(n/20),'2025-01-01'))"+from+" AND NOT EXISTS(SELECT 1 FROM barber_day_guard g WHERE g.barber_id=s.id AND g.service_date=DATE(TIMESTAMPADD(DAY,FLOOR(n/20),'2025-01-01')))");
        jdbc.update("INSERT INTO barber_schedule(barber_id,service_date,start_at,end_at) SELECT g.barber_id,g.service_date,TIMESTAMP(g.service_date,'00:00:00'),TIMESTAMP(g.service_date,'14:00:00') FROM barber_day_guard g LEFT JOIN barber_schedule x ON x.barber_id=g.barber_id AND x.service_date=g.service_date WHERE x.id IS NULL");
        jdbc.update("""
                INSERT INTO service_order(id,order_no,student_id,shop_id,order_type,status,payment_status,original_amount,discount_amount,payable_amount,currency,shop_name_snapshot,
                created_at,payment_deadline,acceptance_deadline,paid_at,accepted_at,started_at,completed_at,version)
                SELECT s.shop_id*10000+n,CONCAT('BL',s.shop_id,'_',n),900001+MOD((s.shop_id-1000000)*20+n,180),s.shop_id,'BOOKING','COMPLETED','PAID',3000,0,3000,'CNY',p.name,
                TIMESTAMPADD(HOUR,-3,START),TIMESTAMPADD(MINUTE,-165,START),TIMESTAMPADD(MINUTE,-149,START),TIMESTAMPADD(MINUTE,-179,START),TIMESTAMPADD(MINUTE,-178,START),START,TIMESTAMPADD(MINUTE,30,START),4
                """.replace("START",start)+" FROM service_item s JOIN shop p ON p.id=s.shop_id CROSS JOIN "+numbers(first,last)+" WHERE "+condition);
        jdbc.update("INSERT INTO booking_detail(order_id,service_id,barber_id,service_date,start_at,end_at,occupancy_status,service_name_snapshot,barber_name_snapshot,duration_minutes_snapshot,service_price_snapshot,source_service_version) SELECT s.shop_id*10000+n,s.id,s.id,DATE(TIMESTAMPADD(DAY,FLOOR(n/20),'2025-01-01')),"+start+",TIMESTAMPADD(MINUTE,30,"+start+"),'CONSUMED','模拟理发','虚构理发师',30,3000,0"+from);
        String range=" WHERE o.id BETWEEN s.shop_id*10000+"+first+" AND s.shop_id*10000+"+last+" AND "+condition;
        jdbc.update("INSERT INTO payment_record(order_id,payment_no,amount,channel,status,paid_at) SELECT o.id,CONCAT('BP',o.id),3000,'MOCK','SUCCEEDED',o.paid_at FROM service_order o JOIN service_item s ON s.shop_id=o.shop_id"+range);
        for(int n=0;n<5;n++){
            String action=List.of("CREATE","PAYMENT","ACCEPT","START","COMPLETE").get(n);
            String to=List.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE","COMPLETED").get(n);
            String before=n==0?"NULL":"'"+List.of("PENDING_PAYMENT","PENDING_ACCEPTANCE","ACCEPTED","IN_SERVICE").get(n-1)+"'";
            String at=List.of("created_at","paid_at","accepted_at","started_at","completed_at").get(n);
            jdbc.update("INSERT INTO order_event(order_id,event_seq,action,from_status,to_status,actor_type,actor_id,occurred_at) SELECT o.id,"+n+",'"+action+"',"+before+",'"+to+"','"+(n<2?"STUDENT":"MERCHANT")+"',"+(n<2?"o.student_id":"o.shop_id")+",o."+at+" FROM service_order o JOIN service_item s ON s.shop_id=o.shop_id"+range);
        }
        jdbc.update("INSERT INTO order_review(order_id,student_id,shop_id,rating,content,visibility,created_at,moderated_by,moderation_reason,moderated_at) SELECT o.id,o.student_id,o.shop_id,MOD(o.id,5)+1,'虚构已完成预约的基线评价',IF(MOD(o.id,10)=0,'HIDDEN','VISIBLE'),TIMESTAMPADD(MINUTE,1,o.completed_at),IF(MOD(o.id,10)=0,900000,NULL),IF(MOD(o.id,10)=0,'虚构审核原因',NULL),IF(MOD(o.id,10)=0,TIMESTAMPADD(MINUTE,2,o.completed_at),NULL) FROM service_order o JOIN service_item s ON s.shop_id=o.shop_id"+range);
    }

}
