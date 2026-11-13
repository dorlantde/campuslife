package com.campuslife;

import java.nio.file.Path;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

/** Shared deterministic synthetic data for opt-in read-only-query experiments. Never a production seeder. */
final class PublicShopQueryFixture {
    static final long BASE=1_000_000, ADMIN=900_000;
    private final JdbcTemplate jdbc;
    private final Environment env;
    private final PasswordEncoder passwords;
    private final Path root=Path.of("").toAbsolutePath().normalize();
    long haircut, print;
    private String hash;
    private boolean hasRatingRevision;

    PublicShopQueryFixture(JdbcTemplate jdbc,Environment env,PasswordEncoder passwords){
        this.jdbc=jdbc;this.env=env;this.passwords=passwords;
    }
    void initialize(){ initialize(false); }
    void initializeAtV15(){ initialize(true); }
    private void initialize(boolean upgradingV15){
        // Refuse all writes unless this is the script-owned native fixture and an empty business database.
        assertThat(env.getRequiredProperty("DB_NAME")).isEqualTo("campuslife_it");
        String data=jdbc.queryForObject("SELECT @@datadir",String.class).replace('\\','/');
        String prefix=root.resolve(".cache/mysql-it").toString().replace('\\','/')+"/";
        assertThat(data.toLowerCase(Locale.ROOT)).startsWith(prefix.toLowerCase(Locale.ROOT));
        assertThat(data.substring(prefix.length())).matches("[a-f0-9]{32}/data/?");
        for(String table:List.of("account","shop","service_order","order_review")){
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).as("empty fixture "+table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(upgradingV15?15:23);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(upgradingV15?23:32);
        hasRatingRevision=!upgradingV15;
        haircut=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='HAIRCUT'",Long.class);
        print=jdbc.queryForObject("SELECT id FROM shop_category WHERE code='PRINT'",Long.class);
        // One random BCrypt hash, no known/default password and no login sessions. Fixture only.
        hash=passwords.encode(UUID.randomUUID().toString());
        jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,created_at,updated_at) VALUES(?, 'baseline_admin',?,'虚构基线管理员','ADMIN','ACTIVE','2024-01-01','2024-01-01')",ADMIN,hash);
        jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role,status,created_at,updated_at) SELECT ?+n,CONCAT('baseline_student_',n),?,CONCAT('虚构学生',n),'STUDENT',IF(MOD(n,5)=0,'DISABLED','ACTIVE'),'2024-01-01','2024-01-01' FROM "+numbers(1,100),ADMIN,hash);
    }
    void grow(int first,int last){
        seed(first,last,hash);
        if(first==1){seedOrders("s.shop_id="+(BASE+6),20,5019);}
        if(hasRatingRevision){jdbc.update("INSERT INTO shop_rating_revision(shop_id,revision) SELECT DISTINCT shop_id,1 FROM order_review WHERE TRUE ON DUPLICATE KEY UPDATE revision=revision+1");}
        for(String table:List.of("account","shop","shop_member","shop_business_hour","order_review")){jdbc.execute("ANALYZE TABLE "+table);}
        validateFixture(last);
    }
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
                SELECT s.shop_id*10000+n,CONCAT('BL',s.shop_id,'_',n),900001+MOD(n,100),s.shop_id,'BOOKING','COMPLETED','PAID',3000,0,3000,'CNY',p.name,
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
    Map<String,Long> counts(){
        var result=new LinkedHashMap<String,Long>();
        for(String table:List.of("account","shop","shop_member","shop_business_hour","service_item","barber","barber_day_guard","barber_schedule","service_order","booking_detail","payment_record","order_event","order_review")){result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));}
        result.put("publicShops",jdbc.queryForObject("SELECT COUNT(*) "+com.campuslife.merchant.mapper.PublicShopMapper.FROM_VISIBLE,Long.class));
        result.put("visibleReviews",jdbc.queryForObject("SELECT COUNT(*) FROM order_review WHERE visibility='VISIBLE'",Long.class));return result;
    }
    void validateFixture(int scale){
        assertThat(counts().get("publicShops")).isEqualTo(scale*6L/10);
        long orders=scale*10L+5000;assertThat(counts().get("service_order")).isEqualTo(orders);
        assertThat(counts().get("order_review")).isEqualTo(orders);assertThat(counts().get("order_event")).isEqualTo(orders*5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_review r JOIN service_order o ON o.id=r.order_id JOIN booking_detail b ON b.order_id=o.id JOIN payment_record p ON p.order_id=o.id WHERE o.status='COMPLETED' AND o.payment_status='PAID' AND b.occupancy_status='CONSUMED' AND r.student_id=o.student_id AND r.shop_id=o.shop_id AND p.amount=o.payable_amount AND r.created_at>=o.completed_at",Long.class)).isEqualTo(orders);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_detail b JOIN barber_schedule x ON x.barber_id=b.barber_id AND x.service_date=b.service_date WHERE b.start_at>=x.start_at AND b.end_at<=x.end_at",Long.class)).isEqualTo(orders);
    }
}
