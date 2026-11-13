package com.campuslife;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Run alone against a fresh owned MySQL fixture. Does not open any developer files. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="spring.flyway.target=23")
@ActiveProfiles("mysql")
class S3StorageUpgradeCheck {
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired com.campuslife.file.storage.LocalFileStorage local;
    @Test void actualV23LocalFileMetadataAndBytesSurviveV24WithoutMigrationOrNewTables() throws Exception {
        assertThat(System.getenv("DB_NAME")).isEqualTo("campuslife_it");String ns=local.storageId(),key=UUID.randomUUID().toString().replace("-","")+".pdf";
        byte[] pdf=com.campuslife.file.PdfSamples.pdf(1);var received=local.receive(new java.io.ByteArrayInputStream(pdf));local.move(received,key);local.releaseTemporary(received.path());
        try {
            jdbc.update("INSERT INTO account(id,username,password_hash,nickname,role) VALUES (840001,'storage_upgrade_s','test','模拟学生','STUDENT')");
            var now=LocalDateTime.of(2026,11,2,0,0);
            for(String status:List.of("AVAILABLE","UPLOADING","DELETING","DELETED")) {
                jdbc.update("INSERT INTO file_asset(owner_id,storage_kind,storage_id,storage_key,original_name,byte_size,sha256,pdf_page_count,status,expires_at,created_at,updated_at,deleted_at,version) VALUES (840001,'LOCAL',?,?, 'legacy.pdf',?,?,1,?,?,?,?,?,7)",
                        ns,status.equals("AVAILABLE")?key:UUID.randomUUID().toString().replace("-","")+".pdf",pdf.length,received.sha256(),status,now.plusDays(7),now,now,status.equals("DELETED")?now:null);
            }
            var before=jdbc.queryForList("SELECT * FROM file_asset WHERE owner_id=840001 ORDER BY id");
            var flyway=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("24");
            assertThat(jdbc.queryForList("SELECT * FROM file_asset WHERE owner_id=840001 ORDER BY id")).isEqualTo(before);
            try(var input=local.open(ns,key,pdf.length)){assertThat(input.readAllBytes()).isEqualTo(pdf);}
            long file=jdbc.queryForObject("SELECT MIN(id) FROM file_asset WHERE owner_id=840001",Long.class);
            jdbc.update("UPDATE file_asset SET storage_kind='S3' WHERE id=?",file);
            assertThatThrownBy(()->jdbc.update("UPDATE file_asset SET storage_kind='OTHER' WHERE id=?",file)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->jdbc.update("UPDATE file_asset SET storage_key='objects/' WHERE id=?",file)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(32);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='file_asset' AND index_name='ix_file_tombstone'",Integer.class)).isEqualTo(3);
            flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        } finally {jdbc.update("DELETE FROM file_asset WHERE owner_id=840001");jdbc.update("DELETE FROM account WHERE id=840001");local.delete(ns,key);}
    }
}
