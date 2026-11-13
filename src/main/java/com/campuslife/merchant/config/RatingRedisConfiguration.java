package com.campuslife.merchant.config;

import com.campuslife.merchant.cache.RedisRatingStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Profile("mysql")
@EnableConfigurationProperties(RatingCacheProperties.class)
public class RatingRedisConfiguration {
    @Configuration(proxyBeanMethods = false)
    @Profile("mysql")
    @ConditionalOnProperty(name = "campuslife.rating-cache.enabled", havingValue = "true")
    static class Enabled {
        @Bean(destroyMethod = "shutdown")
        DefaultClientResources ratingRedisResources() {
            return DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).build();
        }
        @Bean
        LettuceConnectionFactory ratingRedisConnectionFactory(RatingCacheProperties props, @org.springframework.beans.factory.annotation.Qualifier("ratingRedisResources") DefaultClientResources resources) {
            var options = ClientOptions.builder().requestQueueSize(256)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                    .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(50))).build();
            var client = LettuceClientConfiguration.builder().clientResources(resources).clientOptions(options)
                    .commandTimeout(Duration.ofMillis(50)).shutdownTimeout(Duration.ofMillis(100)).build();
            var standalone = new RedisStandaloneConfiguration(props.host(), props.port());
            if (props.username() != null && !props.username().isBlank()) { standalone.setUsername(props.username()); }
            if (props.password() != null && !props.password().isBlank()) { standalone.setPassword(props.password()); }
            var factory = new LettuceConnectionFactory(standalone, client);
            factory.setEagerInitialization(false);
            return factory;
        }
        @Bean
        StringRedisTemplate ratingRedisTemplate(@org.springframework.beans.factory.annotation.Qualifier("ratingRedisConnectionFactory") LettuceConnectionFactory ratingRedisConnectionFactory) {
            return new StringRedisTemplate(ratingRedisConnectionFactory);
        }
        @Bean
        RedisRatingStore redisRatingStore(@org.springframework.beans.factory.annotation.Qualifier("ratingRedisTemplate") StringRedisTemplate ratingRedisTemplate, RatingCacheProperties props, ObjectMapper json) {
            return new RedisRatingStore(ratingRedisTemplate, props.namespace(), json);
        }
    }
}
