package com.campuslife.merchant.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RatingRedisConfigurationTest {
    @Test void optionalAclBindsAndIsAppliedWithoutPrintingSecrets() {
        var props = new Binder(new MapConfigurationPropertySource(Map.of(
                "campuslife.rating-cache.enabled", "true", "campuslife.rating-cache.namespace", "acl_test",
                "campuslife.rating-cache.username", "rating_app", "campuslife.rating-cache.password", "test-secret-value")))
                .bind("campuslife.rating-cache", RatingCacheProperties.class).get();
        var config = new RatingRedisConfiguration.Enabled();
        var resources = config.ratingRedisResources();
        var factory = config.ratingRedisConnectionFactory(props, resources);
        try {
            assertThat(factory.getStandaloneConfiguration().getUsername()).isEqualTo("rating_app");
            assertThat(factory.getStandaloneConfiguration().getPassword().get()).isEqualTo("test-secret-value".toCharArray());
            assertThat(props.toString()).doesNotContain("test-secret-value");
        } finally { factory.destroy(); resources.shutdown().syncUninterruptibly(); }
    }

    @Test void existingAnonymousLoopbackConfigurationRemainsValid() {
        var props = new RatingCacheProperties(true, "legacy_test", "127.0.0.1", 6379);
        assertThat(props.username()).isEmpty();
        assertThat(props.password()).isEmpty();
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of("campuslife.rating-cache.enabled", "false")))
                .bind("campuslife.rating-cache", RatingCacheProperties.class).get().enabled()).isFalse();
    }
}
