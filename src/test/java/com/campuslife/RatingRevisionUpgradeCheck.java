package com.campuslife;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.flyway.target=15","campuslife.rating-cache.enabled=false"})
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named="CAMPUSLIFE_CACHE_IT",matches="isolated-fixture")
class RatingRevisionUpgradeCheck {
    @Autowired JdbcTemplate jdbc;@Autowired Environment env;@Autowired PasswordEncoder passwords;@Autowired javax.sql.DataSource dataSource;
    @Test void actualV15FactsUpgradeWithoutChangingReviewsAndAllHiddenShopsReceiveARevision(){
        var fixture=new PublicShopQueryFixture(jdbc,env,passwords);fixture.initializeAtV15();fixture.grow(1,10);
        long allHidden=PublicShopQueryFixture.BASE+8;
        jdbc.update("UPDATE order_review SET visibility='HIDDEN',moderated_by=900000,moderation_reason='升级夹具',moderated_at=created_at WHERE shop_id=?",allHidden);
        var before=jdbc.queryForList("SELECT * FROM order_review ORDER BY id");
        var flyway=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("16").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("16");
        assertThat(jdbc.queryForList("SELECT * FROM order_review ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_rating_revision",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT revision FROM shop_rating_revision WHERE shop_id=?",Long.class,allHidden)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shop_rating_revision WHERE shop_id=?",Integer.class,PublicShopQueryFixture.BASE+5)).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
