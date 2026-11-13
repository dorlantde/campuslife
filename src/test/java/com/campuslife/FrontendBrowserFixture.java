package com.campuslife;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit, disposable browser fixture. Never an application bootstrap or a production account initializer. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"campuslife.rating-cache.enabled=false", "campuslife.mock-payment.enabled=true", "campuslife.booking.expiry-enabled=false", "campuslife.print.expiry-enabled=false"})
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_FRONTEND_IT",matches="isolated-browser-fixture")
class FrontendBrowserFixture {
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Test void serveDisposableDataUntilExplicitStop() throws Exception {
        Path root=Path.of("").toAbsolutePath().normalize();
        Path output=Path.of(env.getRequiredProperty("CAMPUSLIFE_FRONTEND_OUTPUT")).toAbsolutePath().normalize();
        assertThat(output.getParent()).isEqualTo(root.resolve(".cache/frontend-it"));
        assertThat(output.getFileName().toString()).matches("[a-f0-9]{32}");
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);
        fixture.initialize(); fixture.grow(1,30);
        // These public credentials exist only inside this generated, loopback-only disposable MySQL instance.
        for(String role:List.of("STUDENT","MERCHANT","ADMIN")) {
            jdbc.update("INSERT INTO account(username,password_hash,nickname,role,status) VALUES(?,?,?,?,'ACTIVE')",
                    "ui_"+role.toLowerCase(Locale.ROOT)+"_m052a",passwords.encode("Ui_Test_2026!"),"前端验收"+role,role);
        }
        jdbc.update("UPDATE shop SET name=CONCAT('星海',IF(category_id=?,'理发','打印'),'验收店 ',id-?),description='虚构校园商家，仅用于隔离前端验收。',location_text=CONCAT('星海校园 · 服务中心 ',id-?,' 号')",fixture.haircut,PublicShopQueryFixture.BASE,PublicShopQueryFixture.BASE);
        jdbc.update("UPDATE account SET nickname='<img src=x onerror=alert(1)>纯文本' WHERE id=?",PublicShopQueryFixture.ADMIN+1);
        jdbc.update("INSERT INTO service_item(shop_id,service_type,name,description,price_amount,duration_minutes,enabled) SELECT id,'PRINT','校园PDF打印','A4打印参考起价，实际以规格报价为准',20,NULL,TRUE FROM shop WHERE category_id=?",fixture.print);
        // C-batch seed: a real approved PRINT shop with a known disposable merchant and four price rules.
        long printShop=PublicShopQueryFixture.BASE+5;
        jdbc.update("UPDATE account SET password_hash=? WHERE id=?",passwords.encode("Ui_Test_2026!"),printShop);
        jdbc.update("UPDATE shop SET business_enabled=TRUE WHERE id=?",printShop);
        jdbc.update("UPDATE shop_business_hour SET start_time='00:00',end_time='23:59' WHERE shop_id=?",printShop);
        for(String color:List.of("BW","COLOR")) for(String side:List.of("SINGLE","DOUBLE")) {
            jdbc.update("INSERT INTO print_price_rule(service_id,paper_size,color_mode,side_mode,price_per_impression,enabled) SELECT id,'A4',?,?,?,TRUE FROM service_item WHERE shop_id=? AND service_type='PRINT'",color,side,color.equals("BW")?20:100,printShop);
        }
        jdbc.update("INSERT INTO account(username,password_hash,nickname,role,status) VALUES('ui_other_m052c',?,'另一位虚构学生','STUDENT','ACTIVE')",passwords.encode("Ui_Test_2026!"));
        // D-batch: future schedules with the real campus date; no clock override or started-order backdoor.
        long hairShop=PublicShopQueryFixture.BASE+8;
        jdbc.update("UPDATE account SET password_hash=? WHERE id=?",passwords.encode("Ui_Test_2026!"),hairShop);
        jdbc.update("UPDATE account SET password_hash=? WHERE id=?",passwords.encode("Ui_Test_2026!"),PublicShopQueryFixture.ADMIN+1);
        jdbc.update("UPDATE shop SET business_enabled=TRUE WHERE id=?",hairShop);
        jdbc.update("UPDATE shop_business_hour SET start_time='00:00',end_time='23:59' WHERE shop_id=?",hairShop);
        LocalDate campusToday=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for(int i=0;i<7;i++) {
            LocalDate day=campusToday.plusDays(i);
            jdbc.update("INSERT INTO barber_day_guard(barber_id,service_date) VALUES(?,?)",hairShop,day);
            jdbc.update("INSERT INTO barber_schedule(barber_id,service_date,start_at,end_at) VALUES(?,?,?,?)",hairShop,day,
                    LocalDateTime.ofInstant(day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC),
                    LocalDateTime.ofInstant(day.atTime(23,45).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),ZoneOffset.UTC));
        }
        var info=new LinkedHashMap<String,Object>();
        info.put("backendPort",env.getRequiredProperty("local.server.port"));
        info.put("databaseName",env.getRequiredProperty("DB_NAME"));
        info.put("fixtureDataDir",jdbc.queryForObject("SELECT @@datadir",String.class));
        info.put("shopId",Long.toString(PublicShopQueryFixture.BASE+8));
        info.put("printShopId",Long.toString(printShop));
        info.put("printServiceId",jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM service_item WHERE shop_id=? AND service_type='PRINT'",String.class,printShop));
        info.put("hairShopId",Long.toString(hairShop));
        info.put("hairServiceId",Long.toString(hairShop));
        info.put("hairBarberId",Long.toString(hairShop));
        info.put("otherStudentId",jdbc.queryForObject("SELECT CAST(id AS CHAR) FROM account WHERE username='ui_other_m052c'",String.class));
        info.put("readyAt",Instant.now().toString());
        Files.writeString(output.resolve("ready.json"),json.writeValueAsString(info));
        long deadline=System.nanoTime()+Duration.ofMinutes(90).toNanos();
        while(!Files.exists(output.resolve("stop")) && System.nanoTime()<deadline) { Thread.sleep(250); }
        assertThat(Files.exists(output.resolve("stop"))).as("Explicit stop before 90-minute fixture expiry").isTrue();
        Files.writeString(output.resolve("browser-fixture-finished.json"),json.writeValueAsString(Map.of("finishedAt",Instant.now().toString(),"applicationTables",jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",Integer.class))));
    }
}
