package com.campuslife.file.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("campuslife.files.s3")
public record S3StorageProperties(boolean enabled,String endpoint,String region,String bucket,
        String namespace,String accessKey,String secretKey) {
    public S3StorageProperties {
        if (enabled) {
            try {
                URI uri=URI.create(endpoint);
                if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getFragment()!=null
                        || !(uri.getPath().isEmpty()||uri.getPath().equals("/"))
                        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())&&"127.0.0.1".equals(uri.getHost()))
                        || uri.getPort()==0 || bucket==null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                        || namespace==null || !namespace.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                        || region==null || region.isBlank() || accessKey==null || accessKey.isBlank() || secretKey==null || secretKey.isBlank()) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid explicit S3 storage configuration"); }
        }
    }
    // Record's generated toString must never expose credentials in diagnostics.
    @Override public String toString() { return "S3StorageProperties[enabled="+enabled+",credentials=redacted]"; }
}
