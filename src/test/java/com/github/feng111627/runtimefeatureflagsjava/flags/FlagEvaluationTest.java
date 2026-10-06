package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：开关判定。
 * 覆盖：分桶稳定性、灰度比例命中分布、定向规则命中与优先级、判定原因可解释、
 * 开关关闭/不存在时的兜底行为、配置调整后立即生效。
 */
class FlagEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(FlagEvaluationTest.class);

    private static FlagSnapshot snapshotOf(long version, FlagDefinition... defs) {
        Map<String, FlagDefinition> map = new java.util.HashMap<>();
        for (FlagDefinition def : defs) {
            map.put(def.name(), def);
        }
        return new FlagSnapshot(version, map);
    }

    @Test
    void sameIdentifierAlwaysGetsSameBucket() {
        log.info("[用例] 分桶稳定性：同一标识重复判定结果一致");
        FlagSnapshot snapshot = snapshotOf(1,
                new FlagDefinition("new-checkout", true, 50, List.of()));

        Evaluation first = snapshot.evaluate("new-checkout", "user-12345", Map.of());
        for (int i = 0; i < 1000; i++) {
            Evaluation again = snapshot.evaluate("new-checkout", "user-12345", Map.of());
            assertEquals(first.bucket(), again.bucket(), "同一标识的桶号必须稳定");
            assertEquals(first.value(), again.value(), "同一标识的判定结果必须稳定");
        }
        log.info("[通过] 1000 次重复判定结果一致，bucket={}，value={}", first.bucket(), first.value());
    }

    @Test
    void rolloutPercentageControlsHitDistribution() {
        log.info("[用例] 灰度比例：30% 灰度下命中比例应接近 30%");
        FlagSnapshot snapshot = snapshotOf(1,
                new FlagDefinition("new-checkout", true, 30, List.of()));

        int total = 100_000;
        int hits = 0;
        Set<Integer> buckets = new HashSet<>();
        for (int i = 0; i < total; i++) {
            Evaluation e = snapshot.evaluate("new-checkout", "user-" + i, Map.of());
            buckets.add(e.bucket());
            if (e.value()) {
                hits++;
                assertEquals(Reason.ROLLOUT_HIT, e.reason());
            } else {
                assertEquals(Reason.ROLLOUT_MISS, e.reason());
            }
        }
        double ratio = hits * 100.0 / total;
        log.info("灰度命中情况：total={}, hits={}, ratio={}%，桶分布数={}", total, hits, ratio, buckets.size());
        assertTrue(ratio > 27.0 && ratio < 33.0,
                "30% 灰度的实际命中比例应在 27%-33% 之间，实际 " + ratio + "%");
        assertEquals(100, buckets.size(), "桶号应覆盖 0-99");
        log.info("[通过] 灰度命中比例 {}% 符合预期", ratio);
    }

    @Test
    void rolloutBoundaryIsExact() {
        log.info("[用例] 灰度边界：bucket < 比例才命中，0% 全不命中，100% 全命中");
        FlagSnapshot zero = snapshotOf(1, new FlagDefinition("f", true, 0, List.of()));
        FlagSnapshot hundred = snapshotOf(1, new FlagDefinition("f", true, 100, List.of()));
        for (int i = 0; i < 1000; i++) {
            assertFalse(zero.evaluate("f", "u" + i, Map.of()).value(), "0% 灰度不应命中");
            assertTrue(hundred.evaluate("f", "u" + i, Map.of()).value(), "100% 灰度必须命中");
        }
        // 边界精确性：bucket == pct 时不命中
        String id = "user-12345";
        int bucket = Bucketer.bucket("f", id);
        FlagSnapshot atBoundary = snapshotOf(1, new FlagDefinition("f", true, bucket, List.of()));
        assertFalse(atBoundary.evaluate("f", id, Map.of()).value(),
                "bucket(" + bucket + ") == 比例(" + bucket + ") 时不应命中");
        FlagSnapshot justAbove = snapshotOf(1, new FlagDefinition("f", true, bucket + 1, List.of()));
        assertTrue(justAbove.evaluate("f", id, Map.of()).value(),
                "bucket(" + bucket + ") < 比例(" + (bucket + 1) + ") 时必须命中");
        log.info("[通过] 0%/100% 与 bucket==比例 的边界行为正确");
    }

    @Test
    void targetRuleHitBeatsRolloutAndIsExplainable() {
        log.info("[用例] 定向规则：命中规则直接生效，且优先于灰度，命中原因可解释");
        FlagDefinition def = new FlagDefinition("promo", true, 0, List.of(
                new TargetRule("group", TargetRule.Operator.IN, List.of("beta", "internal"), "内部与 beta 组")));
        FlagSnapshot snapshot = snapshotOf(7, def);

        Evaluation hit = snapshot.evaluate("promo", "user-1", Map.of("group", "beta"));
        assertTrue(hit.value());
        assertEquals(Reason.RULE_HIT, hit.reason());
        assertNotNull(hit.matchedRule());
        assertEquals(7, hit.configVersion());
        log.info("规则命中解释：reason={}，matchedRule={}，version={}", hit.reason(), hit.matchedRule(), hit.configVersion());

        // 灰度为 0%，未命中规则的用户必须不命中
        Evaluation miss = snapshot.evaluate("promo", "user-1", Map.of("group", "public"));
        assertFalse(miss.value());
        assertEquals(Reason.ROLLOUT_MISS, miss.reason());
        log.info("[通过] 规则命中优先于灰度，未命中原因={}", miss.reason());
    }

    @Test
    void earlierRuleHasHigherPriority() {
        log.info("[用例] 规则优先级：列表靠前的规则先匹配");
        FlagDefinition def = new FlagDefinition("promo", true, 0, List.of(
                new TargetRule("group", TargetRule.Operator.EQUALS, List.of("beta"), "规则1"),
                new TargetRule("region", TargetRule.Operator.EQUALS, List.of("cn"), "规则2")));
        FlagSnapshot snapshot = snapshotOf(1, def);

        // 两个规则都满足时，应报告命中第一条
        Evaluation e = snapshot.evaluate("promo", "user-1", Map.of("group", "beta", "region", "cn"));
        assertTrue(e.value());
        assertTrue(e.matchedRule().startsWith("group"), "应命中排在前面的 group 规则，实际: " + e.matchedRule());
        log.info("[通过] 多规则同时满足时命中靠前规则：{}", e.matchedRule());
    }

    @Test
    void disabledAndMissingFlagsAreExplainable() {
        log.info("[用例] 开关关闭与开关不存在时的判定原因");
        FlagSnapshot snapshot = snapshotOf(3,
                new FlagDefinition("off-flag", false, 100, List.of()));

        Evaluation disabled = snapshot.evaluate("off-flag", "user-1", Map.of());
        assertFalse(disabled.value());
        assertEquals(Reason.FLAG_DISABLED, disabled.reason());

        Evaluation missing = snapshot.evaluate("no-such-flag", "user-1", Map.of());
        assertFalse(missing.value());
        assertEquals(Reason.FLAG_NOT_FOUND, missing.reason());
        log.info("[通过] 关闭原因={}，不存在原因={}", disabled.reason(), missing.reason());
    }

    @Test
    void updatedSnapshotTakesEffectImmediately() {
        log.info("[用例] 调整灰度比例后，后续判定立即按新配置生效");
        String id = "user-12345";
        int bucket = Bucketer.bucket("flag-x", id);
        FlagSnapshot before = snapshotOf(1, new FlagDefinition("flag-x", true, bucket, List.of()));
        FlagSnapshot after = snapshotOf(2, new FlagDefinition("flag-x", true, bucket + 1, List.of()));

        assertFalse(before.evaluate("flag-x", id, Map.of()).value(), "旧比例下不应命中");
        Evaluation e = after.evaluate("flag-x", id, Map.of());
        assertTrue(e.value(), "比例调大 1 后同一标识应立即命中");
        assertEquals(2, e.configVersion(), "判定结果应携带新版本号");
        log.info("[通过] 比例 {} -> {} 后同一标识判定翻转，version={}", bucket, bucket + 1, e.configVersion());
    }
}
