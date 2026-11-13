package com.campuslife.merchant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("campuslife.rating-cache")
public record RatingCacheProperties(boolean enabled, @DefaultValue("") String namespace,
                                    @DefaultValue("127.0.0.1") String host, @DefaultValue("6379") int port,
                                    @DefaultValue("") String username, @DefaultValue("") String password) {
    public RatingCacheProperties(boolean enabled, String namespace, String host, int port) {
        this(enabled, namespace, host, port, "", "");
    }
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public RatingCacheProperties {
        if (enabled && (namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,64}"))) {
            throw new IllegalArgumentException("An explicit rating cache dataset namespace is required");
        }
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Invalid Redis port"); }
    }
    @Override public String toString() {
        return "RatingCacheProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
