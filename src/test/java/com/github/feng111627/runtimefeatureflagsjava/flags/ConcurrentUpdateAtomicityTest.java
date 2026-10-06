package com.github.feng111627.runtimefeatureflagsjava.flags;

import com.github.feng111627.runtimefeatureflagsjava.flags.config.FlagConfigException;
import com.github.feng111627.runtimefeatureflagsjava.flags.core.FeatureFlagService;
import com.github.feng111627.runtimefeatureflagsjava.flags.core.FlagSnapshot;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.InMemoryFlagSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务覆盖：并发更新下整批原子生效、失败更新不影响当前配置、可回退到上一有效版本。 */
@ExtendWith(TestOutcomeLogger.class)
class ConcurrentUpdateAtomicityTest {

    @Test
    @DisplayName("原子性：并发读写时读方只能看到完整的旧版本或完整的新版本")
    void readersNeverSeePartialState() throws InterruptedException {
        InMemoryFlagSource source = new InMemoryFlagSource("test", 1, flagsFor(0));
        FeatureFlagService service = new FeatureFlagService(List.of(source));
        service.reload();

        int writerCount = 4;
        int readerCount = 8;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger anomalies = new AtomicInteger();

        for (int w = 0; w < writerCount; w++) {
            int writerId = w;
            pool.submit(() -> {
                await(start);
                for (int r = 1; r <= rounds; r++) {
                    source.replace(flagsFor(writerId * 1000 + r));
                    service.reload();
                }
            });
        }
        for (int i = 0; i < readerCount; i++) {
            pool.submit(() -> {
                await(start);
                for (int r = 0; r < rounds * writerCount; r++) {
                    FlagSnapshot snapshot = service.currentSnapshot();
                    // 每份快照由 flagsFor(n) 整体生成：flag-x 与 flag-y 的比例必须相等，
                    // 若读到一新一旧拼出来的中间状态则比例必然不等。
                    FlagDefinition x = snapshot.flags().get("flag-x");
                    FlagDefinition y = snapshot.flags().get("flag-y");
                    if (x == null || y == null || x.rolloutPercent() != y.rolloutPercent()) {
                        anomalies.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(0, anomalies.get(), "读方绝不能看到只更新了一半的中间状态");
    }

    @Test
    @DisplayName("失败隔离：非法更新被拒绝后，当前生效配置保持不变")
    void failedUpdateKeepsCurrentConfig() {
        InMemoryFlagSource source = new InMemoryFlagSource("test", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 50, Set.of())));
        FeatureFlagService service = new FeatureFlagService(List.of(source));
        FlagSnapshot good = service.reload();

        source.replace(Map.of("flag-a", FlagDefinition.of("flag-a", true, 999, Set.of())));
        FlagConfigException ex = assertThrows(FlagConfigException.class, service::reload);

        assertEquals(FlagConfigException.ErrorType.INVALID_VALUE, ex.errorType());
        assertEquals(good, service.currentSnapshot(), "失败的更新不得影响当前生效配置");
        assertEquals(50, service.currentSnapshot().flags().get("flag-a").rolloutPercent());
    }

    @Test
    @DisplayName("回退：发布后能回退到上一次有效版本，且版本号单调递增")
    void rollbackRestoresPreviousVersion() {
        InMemoryFlagSource source = new InMemoryFlagSource("test", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 10, Set.of())));
        FeatureFlagService service = new FeatureFlagService(List.of(source));
        FlagSnapshot v1 = service.reload();

        source.replace(Map.of("flag-a", FlagDefinition.of("flag-a", true, 90, Set.of())));
        FlagSnapshot v2 = service.reload();
        assertEquals(90, service.currentSnapshot().flags().get("flag-a").rolloutPercent());

        FlagSnapshot restored = service.rollback();
        assertEquals(v1.flags(), restored.flags(), "回退后内容必须等于上一有效版本");
        assertTrue(restored.version() > v2.version(), "回退也是一次发布，版本号必须递增");
        assertEquals(10, service.currentSnapshot().flags().get("flag-a").rolloutPercent());
    }

    /** 生成一份整体配置：flag-x 与 flag-y 的比例相同，用于检测中间状态。 */
    private static Map<String, FlagDefinition> flagsFor(int n) {
        int percent = n % 101;
        return Map.of(
                "flag-x", FlagDefinition.of("flag-x", true, percent, Set.of()),
                "flag-y", FlagDefinition.of("flag-y", true, percent, Set.of()));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
