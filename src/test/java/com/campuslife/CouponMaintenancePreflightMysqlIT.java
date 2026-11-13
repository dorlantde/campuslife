package com.campuslife;

import com.campuslife.coupon.mapper.DispatchGateMapper;
import com.campuslife.coupon.redis.CouponRedisStore;
import com.campuslife.coupon.service.*;
import io.lettuce.core.RedisCommandTimeoutException;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL transactions and locks; Redis is injected here, actual Redis is checked separately. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class CouponMaintenancePreflightMysqlIT extends CouponAsyncFixture {
    @Autowired CouponMaintenance maintenance;
    @MockitoBean CouponRedisStore store;

    // Exercise the original maintenance/recovery beans without starting a broker or Redis client.
    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {
        @Bean @Primary CouponMaintenance testedMaintenance(CouponMaintenanceTransactions tx,
                ObjectProvider<CouponRedisRecovery> recovery) {
            return new CouponMaintenance(tx, recovery, true);
        }
        @Bean CouponRedisRecovery testedRecovery(CouponRecoveryTransactions tx, CouponRedisStore store,
                DispatchGateMapper gates, Clock clock) {
            return new CouponRedisRecovery(tx, store, gates, clock);
        }
    }

    Campaign paused() {
        var c = campaign(2);
        jdbc.update("UPDATE coupon_dispatch_gate SET mode='ASYNC',phase='PAUSED',control_version=7 WHERE campaign_id=?", c.row().getId());
        return c;
    }

    void rejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(CouponMaintenanceTransactions.Refused.class).hasMessage(code);
    }

    static RedisConnectionFailureException timeout() {
        return new RedisConnectionFailureException("synthetic-connection", new RedisCommandTimeoutException("synthetic-timeout"));
    }

    @ParameterizedTest @ValueSource(strings = {"Resume", "Sync"})
    void twoTimeoutsLeaveCompleteGateAndInventoryUntouched(String action) {
        var c = paused(); long id = c.row().getId(); var before = gates.find(id);
        when(store.runId()).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(gates.find(id)).isEqualTo(before);
            throw timeout();
        });
        rejected(() -> maintenance.run(action, id, 7L, true), "COUPON_REDIS_PREFLIGHT_FAILED");
        assertThat(gates.find(id)).isEqualTo(before);
        assertThat(issued(c)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_coupon WHERE campaign_id=?", Integer.class, id)).isZero();
        verify(store, times(2)).runId(); verifyNoMoreInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings = {"Resume", "Sync"})
    void versionChangedInAnotherTransactionDuringReadStillRefusesTicket(String action) {
        var c = paused(); long id = c.row().getId(); var before = gates.find(id);
        when(store.runId()).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> tx().executeWithoutResult(s -> {
                    shops.share(c.row().getShopId()); gates.lock(id);
                    jdbc.update("UPDATE coupon_dispatch_gate SET control_version=control_version+1 WHERE campaign_id=?", id);
                })).get(5, TimeUnit.SECONDS);
            }
            return "a".repeat(40);
        });
        rejected(() -> maintenance.run(action, id, 7L, true), "CONTROL_VERSION_CHANGED");
        var after = gates.find(id);
        assertThat(after.controlVersion()).isEqualTo(8);
        assertThat(after.generation()).isEqualTo(before.generation());
        assertThat(after.phase()).isEqualTo("PAUSED");
        assertThat(after.rebuildToken()).isNull(); assertThat(after.leaseUntil()).isNull();
        verify(store).runId(); verifyNoMoreInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings = {"Resume", "Sync"})
    void failuresAfterTicketKeepOriginalDurableIntentAndDoNotRetryAction(String action) {
        var c = paused(); long id = c.row().getId(); var before = gates.find(id);
        when(store.runId()).thenReturn("a".repeat(40));
        doThrow(new IllegalStateException("synthetic-after-ticket")).when(store).prepare(any(), anyString());
        assertThatThrownBy(() -> maintenance.run(action, id, 7L, true)).hasMessage("synthetic-after-ticket");
        var after = gates.find(id);
        assertThat(after.phase()).isEqualTo("REBUILDING");
        assertThat(after.generation()).isEqualTo(before.generation() + 1);
        assertThat(after.controlVersion()).isEqualTo(8);
        assertThat(after.resumePhase()).isEqualTo(action.equals("Resume") ? "OPEN" : "PAUSED");
        assertThat(after.rebuildToken()).isNotNull(); assertThat(after.leaseUntil()).isNotNull();
        verify(store, times(2)).runId(); verify(store).prepare(any(), anyString()); verifyNoMoreInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings = {"Resume", "Sync"})
    void oldOperatorVersionDoesNotEvenStartPreflight(String action) {
        var c = paused(); var before = gates.find(c.row().getId());
        rejected(() -> maintenance.run(action, c.row().getId(), 6L, true), "CONTROL_VERSION_CHANGED");
        assertThat(gates.find(c.row().getId())).isEqualTo(before); verifyNoInteractions(store);
    }

    @ParameterizedTest @ValueSource(strings = {"Resume", "Sync"})
    void nonPausedActivityDoesNotConnect(String action) {
        var c = paused(); long id = c.row().getId();
        jdbc.update("UPDATE coupon_dispatch_gate SET phase='OPEN' WHERE campaign_id=?", id);
        var before = gates.find(id);
        rejected(() -> maintenance.run(action, id, 7L, true), "PAUSE_FIRST");
        assertThat(gates.find(id)).isEqualTo(before); verifyNoInteractions(store);
    }

    @Test void inspectPauseSyncResumeAndModeAssignmentRemainIndependentOfConnection() {
        var c = campaign(2); long id = c.row().getId();
        var s = maintenance.run("Inspect", id, null, false);
        s = maintenance.run("Pause", id, s.controlVersion(), true);
        s = maintenance.run("Sync", id, s.controlVersion(), true);
        s = maintenance.run("Resume", id, s.controlVersion(), true);
        assertThat(s.mode()).isEqualTo("SYNC"); assertThat(s.phase()).isEqualTo("OPEN");
        s = maintenance.run("Pause", id, s.controlVersion(), true);
        s = maintenance.run("Async", id, s.controlVersion(), true);
        assertThat(s.mode()).isEqualTo("ASYNC"); assertThat(s.phase()).isEqualTo("PAUSED");
        verifyNoInteractions(store);
    }
}
