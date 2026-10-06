package com.github.feng111627.runtimefeatureflagsjava.flags;

import com.github.feng111627.runtimefeatureflagsjava.flags.core.FeatureFlagService;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.EvaluationReason;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.EvaluationResult;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.InMemoryFlagSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 业务覆盖：稳定分桶、灰度命中、定向规则、命中原因可解释、更新立即生效。 */
@ExtendWith(TestOutcomeLogger.class)
class FeatureFlagEvaluationTest {

    private FeatureFlagService serviceWith(Map<String, FlagDefinition> flags) {
        InMemoryFlagSource source = new InMemoryFlagSource("test", 1, flags);
        FeatureFlagService service = new FeatureFlagService(List.of(source));
        service.reload();
        return service;
    }

    @Test
    @DisplayName("分桶：同一用户标识在规则不变时多次判定结果一致")
    void sameUserGetsStableResult() {
        FeatureFlagService service = serviceWith(Map.of(
                "flag-a", FlagDefinition.of("flag-a", true, 50, Set.of())));

        EvaluationResult first = service.evaluate("flag-a", "user-42");
        for (int i = 0; i < 100; i++) {
            EvaluationResult again = service.evaluate("flag-a", "user-42");
            assertEquals(first.on(), again.on(), "同一用户在同一配置下判定必须稳定");
            assertEquals(first.reason(), again.reason());
        }
    }

    @Test
    @DisplayName("灰度：比例 0 全部不命中，比例 100 全部命中")
    void rolloutZeroAndHundred() {
        FeatureFlagService service = serviceWith(Map.of(
                "off-rollout", FlagDefinition.of("off-rollout", true, 0, Set.of()),
                "full-rollout", FlagDefinition.of("full-rollout", true, 100, Set.of())));

        for (int i = 0; i < 200; i++) {
            String userId = "user-" + i;
            EvaluationResult miss = service.evaluate("off-rollout", userId);
            assertFalse(miss.on());
            assertEquals(EvaluationReason.ROLLOUT_MISS, miss.reason());

            EvaluationResult hit = service.evaluate("full-rollout", userId);
            assertTrue(hit.on());
            assertEquals(EvaluationReason.ROLLOUT_HIT, hit.reason());
        }
    }

    @Test
    @DisplayName("灰度：50% 比例下命中数落在合理区间，体现按桶灰度")
    void rolloutFiftyPercentRoughlySplitsUsers() {
        FeatureFlagService service = serviceWith(Map.of(
                "flag-a", FlagDefinition.of("flag-a", true, 50, Set.of())));

        int hits = 0;
        int total = 2000;
        for (int i = 0; i < total; i++) {
            if (service.evaluate("flag-a", "user-" + i).on()) {
                hits++;
            }
        }
        assertTrue(hits > total * 0.40 && hits < total * 0.60,
                "50% 灰度下命中数应接近一半，实际: " + hits + "/" + total);
    }

    @Test
    @DisplayName("定向：定向用户即使灰度比例为 0 也命中，原因是 TARGETED_USER")
    void targetedUserAlwaysHits() {
        FeatureFlagService service = serviceWith(Map.of(
                "flag-a", FlagDefinition.of("flag-a", true, 0, Set.of("vip-user"))));

        EvaluationResult vip = service.evaluate("flag-a", "vip-user");
        assertTrue(vip.on());
        assertEquals(EvaluationReason.TARGETED_USER, vip.reason());

        EvaluationResult normal = service.evaluate("flag-a", "normal-user");
        assertFalse(normal.on());
        assertEquals(EvaluationReason.ROLLOUT_MISS, normal.reason());
    }

    @Test
    @DisplayName("解释：开关停用返回 FLAG_DISABLED，开关不存在返回 FLAG_NOT_FOUND")
    void disabledAndMissingFlagsAreExplained() {
        FeatureFlagService service = serviceWith(Map.of(
                "flag-a", FlagDefinition.of("flag-a", false, 100, Set.of("vip"))));

        EvaluationResult disabled = service.evaluate("flag-a", "vip");
        assertFalse(disabled.on());
        assertEquals(EvaluationReason.FLAG_DISABLED, disabled.reason());

        EvaluationResult missing = service.evaluate("no-such-flag", "vip");
        assertFalse(missing.on());
        assertEquals(EvaluationReason.FLAG_NOT_FOUND, missing.reason());
    }

    @Test
    @DisplayName("更新：调整比例后立刻对后续判定生效，且版本号递增")
    void updateTakesEffectImmediately() {
        InMemoryFlagSource source = new InMemoryFlagSource("test", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 0, Set.of())));
        FeatureFlagService service = new FeatureFlagService(List.of(source));
        service.reload();

        EvaluationResult before = service.evaluate("flag-a", "user-1");
        assertFalse(before.on());
        long versionBefore = before.configVersion();

        source.replace(Map.of("flag-a", FlagDefinition.of("flag-a", true, 100, Set.of())));
        service.reload();

        EvaluationResult after = service.evaluate("flag-a", "user-1");
        assertTrue(after.on());
        assertEquals(EvaluationReason.ROLLOUT_HIT, after.reason());
        assertTrue(after.configVersion() > versionBefore, "发布后版本号必须递增");
    }
}
