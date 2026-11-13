package com.campuslife.file.config;

import com.campuslife.file.service.FileRules;
import com.campuslife.file.storage.LocalFileStorage;
import jakarta.servlet.MultipartConfigElement;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.campuslife.file.storage.S3FileStorage;

@Configuration(proxyBeanMethods=false)
@Profile("mysql")
@MapperScan("com.campuslife.file.mapper")
@EnableConfigurationProperties(S3StorageProperties.class)
public class FileConfiguration {
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="campuslife.files.s3.enabled",havingValue="true")
    public S3FileStorage s3FileStorage(S3StorageProperties settings) { return new S3FileStorage(settings); }
    @Bean
    public MultipartConfigElement multipartConfigElement(LocalFileStorage storage) {
        return new MultipartConfigElement(storage.multipartDirectory().toString(),FileRules.MAX_BYTES,22L*1024*1024,0);
    }
}
