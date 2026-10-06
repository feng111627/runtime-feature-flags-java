package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：运行时动态更新。
 * 覆盖：并发读写下的整批原子性、非法更新不影响当前配置、回退到上一有效版本、
 * 更新后判定立即生效、无历史版本时回滚被拒绝。
 */
class FeatureFlagServiceConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagServiceConcurrencyTest.class);

    private static InMemoryFlagSource sourceWith(String name, int pctA, int pctB) {
        InMemoryFlagSource source = new InMemoryFlagSource(name, 10);
        source.setFlags(Map.of(
                "flag-a", new FlagDefinition("flag-a", true, pctA, List.of()),
                "flag-b", new FlagDefinition("flag-b", true, pctB, List.of())));
        return source;
    }

    @Test
    void concurrentReadersNeverSeePartialUpdate() throws InterruptedException {
        log.info("[用例] 并发原子性：写线程在 0%% 与 100%% 间反复整批切换，"
                + "读方每次判定的结果必须与其所报版本的配置一致");
        DefaultFeatureFlagService service = new DefaultFeatureFlagService();
        InMemoryFlagSource source = sourceWith("dynamic", 0, 0);
        service.refresh(List.of(source));

        // 版本号 -> 该版本 flag-a 的灰度比例，由写线程在每次刷新成功后登记
        Map<Long, Integer> pctByVersion = new ConcurrentHashMap<>();
        pctByVersion.put(service.currentVersion(), 0);

        int writers = 4;
        int readers = 8;
        int iterations = 500;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger tornReads = new AtomicInteger();
        AtomicInteger totalReads = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            // 每个写线程持有独立的配置源，保证"登记的版本->比例"与实际刷入的配置一致
            InMemoryFlagSource writerSource = sourceWith("writer-" + w, 0, 0);
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < iterations; i++) {
                    int pct = (i % 2 == 0) ? 100 : 0;
                    writerSource.setFlags(Map.of(
                            "flag-a", new FlagDefinition("flag-a", true, pct, List.of()),
                            "flag-b", new FlagDefinition("flag-b", true, pct, List.of())));
                    long version = service.refresh(List.of(writerSource));
                    pctByVersion.put(version, pct);
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                await(start);
                for (int i = 0; i < iterations * writers; i++) {
                    Evaluation e = service.evaluate("flag-a", "user-" + (i % 100), Map.of());
                    totalReads.incrementAndGet();
                    Integer pct = pctByVersion.get(e.configVersion());
                    if (pct == null) {
                        continue; // 该版本的登记尚未可见，跳过本次校验
                    }
                    // 0% 必不命中、100% 必命中；不一致说明读到了"改了一半"的中间状态
                    boolean expected = pct == 100;
                    if (e.value() != expected) {
                        tornReads.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试超时");

        assertEquals(0, tornReads.get(), "读方不应看到与版本号不一致的判定结果（撕裂读）");
        log.info("[通过] {} 写线程 x {} 读线程，共 {} 次判定，撕裂读次数=0，最终版本={}",
                writers, readers, totalReads.get(), service.currentVersion());
    }

    @Test
    void failedUpdateKeepsCurrentConfigUntouched() {
        log.info("[用例] 失败隔离：非法更新被拒绝后，当前生效配置保持不变");
        DefaultFeatureFlagService service = new DefaultFeatureFlagService();
        service.refresh(List.of(sourceWith("v1", 50, 50)));
        long versionBefore = service.currentVersion();
        Evaluation before = service.evaluate("flag-a", "user-1", Map.of());

        InMemoryFlagSource bad = new InMemoryFlagSource("bad", 10);
        bad.setFlags(Map.of("flag-a", new FlagDefinition("flag-a", true, 999, List.of())));
        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> service.refresh(List.of(bad)));

        assertEquals(FlagErrorType.INVALID_VALUE, e.getErrorType());
        assertEquals(versionBefore, service.currentVersion(), "失败的更新不得推进版本号");
        Evaluation after = service.evaluate("flag-a", "user-1", Map.of());
        assertEquals(before.value(), after.value(), "失败的更新不得改变判定结果");
        assertEquals(before.configVersion(), after.configVersion());
        log.info("[通过] 非法更新被拒绝（{}），版本仍为 {}，判定结果不变", e.getErrorType(), versionBefore);
    }

    @Test
    void rollbackRestoresPreviousValidVersion() {
        log.info("[用例] 回滚：内容回退到上一次有效版本，版本号单调递增以便审计");
        DefaultFeatureFlagService service = new DefaultFeatureFlagService();
        service.refresh(List.of(sourceWith("v1", 0, 0)));
        long v1 = service.currentVersion();
        Evaluation before = service.evaluate("flag-a", "user-42", Map.of());

        service.refresh(List.of(sourceWith("v2", 100, 100)));
        long v2 = service.currentVersion();
        assertTrue(service.evaluate("flag-a", "user-42", Map.of()).value(), "100% 灰度下必须命中");

        service.rollback();

        long v3 = service.currentVersion();
        assertTrue(v3 > v2 && v2 > v1, "版本号应单调递增，实际 v1=%d v2=%d v3=%d".formatted(v1, v2, v3));
        Evaluation restored = service.evaluate("flag-a", "user-42", Map.of());
        assertEquals(before.value(), restored.value(), "回滚后判定结果应恢复到上一有效版本");
        assertEquals(v3, restored.configVersion());
        log.info("[通过] 版本 {} -> {} -> 回滚发布为 {}，判定结果恢复", v1, v2, v3);
    }

    @Test
    void rollbackWithoutHistoryIsRejected() {
        log.info("[用例] 无历史版本时回滚：拒绝并给出 NO_ROLLBACK_VERSION");
        DefaultFeatureFlagService service = new DefaultFeatureFlagService();
        FlagConfigException e = assertThrows(FlagConfigException.class, service::rollback);
        assertEquals(FlagErrorType.NO_ROLLBACK_VERSION, e.getErrorType());
        log.info("[通过] 空历史回滚被拒绝，错误类型={}", e.getErrorType());
    }

    @Test
    void updateTakesEffectImmediatelyForSubsequentEvaluations() {
        log.info("[用例] 动态生效：不重启服务，刷新后判定立即按新配置执行");
        DefaultFeatureFlagService service = new DefaultFeatureFlagService();
        service.refresh(List.of(sourceWith("v1", 0, 0)));
        String id = "user-7";
        assertEquals(false, service.evaluate("flag-a", id, Map.of()).value());

        service.refresh(List.of(sourceWith("v2", 100, 100)));

        Evaluation e = service.evaluate("flag-a", id, Map.of());
        assertTrue(e.value(), "刷新后同一标识应立即命中");
        assertEquals(service.currentVersion(), e.configVersion());
        log.info("[通过] 刷新后判定立即生效，version={}", e.configVersion());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
