package com.campuslife.coupon.service;

import com.campuslife.coupon.redis.CouponRedisStore;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CouponMaintenancePreflightTest {
    final CouponRedisStore store = mock(CouponRedisStore.class);
    final CouponRedisRecovery recovery = new CouponRedisRecovery(null, store, null, Clock.systemUTC());

    static RedisConnectionFailureException timeout() {
        return new RedisConnectionFailureException("synthetic-untrusted-detail",
                new RedisCommandTimeoutException("synthetic-timeout"));
    }

    @Test void healthyConnectionNeedsOneRead() {
        when(store.runId()).thenReturn("a".repeat(40));
        recovery.preflightMaintenanceConnection();
        verify(store).runId();
        verifyNoMoreInteractions(store);
    }

    @Test void onlyTheMatchingColdTimeoutGetsOneMoreRead() {
        when(store.runId()).thenThrow(timeout()).thenReturn("a".repeat(40));
        recovery.preflightMaintenanceConnection();
        verify(store, times(2)).runId();
        verifyNoMoreInteractions(store);
    }

    @Test void repeatedTimeoutStopsAtTwoAndExposesOnlyFixedCode() {
        when(store.runId()).thenThrow(timeout());
        assertThatThrownBy(recovery::preflightMaintenanceConnection)
                .isInstanceOf(CouponMaintenanceTransactions.Refused.class)
                .hasMessage("COUPON_REDIS_PREFLIGHT_FAILED").hasNoCause();
        verify(store, times(2)).runId();
        verifyNoMoreInteractions(store);
    }

    @Test void secondConnectionFailureDoesNotStartAnotherRetry() {
        when(store.runId()).thenThrow(timeout()).thenThrow(new RedisConnectionFailureException("synthetic-auth"));
        assertThatThrownBy(recovery::preflightMaintenanceConnection).hasMessage("COUPON_REDIS_PREFLIGHT_FAILED");
        verify(store, times(2)).runId();
    }

    @Test void authenticationConnectionFailureIsNotARetryableTimeout() {
        when(store.runId()).thenThrow(new RedisConnectionFailureException("synthetic-auth",
                new RedisCommandExecutionException("WRONGPASS synthetic")));
        assertThatThrownBy(recovery::preflightMaintenanceConnection).hasMessage("COUPON_REDIS_PREFLIGHT_FAILED");
        verify(store).runId();
    }

    static Stream<RuntimeException> nonConnectionFailures() {
        return Stream.of(new RedisCommandTimeoutException("synthetic-direct-command-timeout"),
                new CouponRedisStore.Unavailable("RUN_ID"),
                new DataAccessResourceFailureException("synthetic-sql-error"));
    }

    @ParameterizedTest @MethodSource("nonConnectionFailures")
    void unrelatedFailuresPropagateWithoutRetry(RuntimeException failure) {
        when(store.runId()).thenThrow(failure);
        assertThatThrownBy(recovery::preflightMaintenanceConnection).isSameAs(failure);
        verify(store).runId();
        verifyNoMoreInteractions(store);
    }

    @Test void alreadyInterruptedDoesNotConnectOrClearTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(recovery::preflightMaintenanceConnection).hasMessage("COUPON_REDIS_PREFLIGHT_FAILED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(store);
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedFailureDoesNotRetry() {
        when(store.runId()).thenAnswer(i -> { Thread.currentThread().interrupt(); throw timeout(); });
        try {
            assertThatThrownBy(recovery::preflightMaintenanceConnection).hasMessage("COUPON_REDIS_PREFLIGHT_FAILED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store).runId();
        } finally { Thread.interrupted(); }
    }

    @Test void interruptionAfterSuccessfulReadStillStopsBeforeTicket() {
        when(store.runId()).thenAnswer(i -> { Thread.currentThread().interrupt(); return "a".repeat(40); });
        try {
            assertThatThrownBy(recovery::preflightMaintenanceConnection).hasMessage("COUPON_REDIS_PREFLIGHT_FAILED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(store).runId();
        } finally { Thread.interrupted(); }
    }
}
