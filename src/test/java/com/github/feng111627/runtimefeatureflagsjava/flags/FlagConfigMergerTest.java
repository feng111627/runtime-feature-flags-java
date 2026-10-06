package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：多来源配置合并。
 * 覆盖：优先级合并、非法取值拒绝、同优先级冲突拒绝、同定义去重、空来源列表拒绝。
 */
class FlagConfigMergerTest {

    private static final Logger log = LoggerFactory.getLogger(FlagConfigMergerTest.class);

    private static FlagDefinition flag(String name, boolean enabled, int pct) {
        return new FlagDefinition(name, enabled, pct, List.of());
    }

    @Test
    void higherPrioritySourceWins() {
        log.info("[用例] 多来源合并：高优先级来源覆盖低优先级来源的同名开关");
        InMemoryFlagSource file = new InMemoryFlagSource("file", 10);
        file.setFlags(Map.of(
                "new-checkout", flag("new-checkout", true, 10),
                "search-v2", flag("search-v2", false, 0)));
        InMemoryFlagSource remote = new InMemoryFlagSource("remote", 20);
        remote.setFlags(Map.of("new-checkout", flag("new-checkout", true, 80)));

        Map<String, FlagDefinition> merged = FlagConfigMerger.merge(List.of(file, remote));

        assertEquals(80, merged.get("new-checkout").rolloutPercentage(),
                "同名开关应采用高优先级来源的定义");
        assertEquals(0, merged.get("search-v2").rolloutPercentage(),
                "仅存在于低优先级来源的开关应保留");
        assertEquals(2, merged.size());
        log.info("[通过] 高优先级来源生效，低优先级独有开关保留");
    }

    @Test
    void identicalDefinitionAcrossSamePrioritySourcesIsDeduplicated() {
        log.info("[用例] 同优先级来源定义完全一致：去重保留，不视为冲突");
        InMemoryFlagSource a = new InMemoryFlagSource("dc-a", 10);
        a.setFlags(Map.of("new-checkout", flag("new-checkout", true, 50)));
        InMemoryFlagSource b = new InMemoryFlagSource("dc-b", 10);
        b.setFlags(Map.of("new-checkout", flag("new-checkout", true, 50)));

        Map<String, FlagDefinition> merged = FlagConfigMerger.merge(List.of(a, b));

        assertEquals(1, merged.size());
        log.info("[通过] 一致定义去重成功");
    }

    @Test
    void conflictingDefinitionAcrossSamePrioritySourcesIsRejected() {
        log.info("[用例] 同优先级来源定义冲突：拒绝合并并给出 DUPLICATE_CONFLICT");
        InMemoryFlagSource a = new InMemoryFlagSource("dc-a", 10);
        a.setFlags(Map.of("new-checkout", flag("new-checkout", true, 50)));
        InMemoryFlagSource b = new InMemoryFlagSource("dc-b", 10);
        b.setFlags(Map.of("new-checkout", flag("new-checkout", true, 70)));

        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(a, b)));

        assertEquals(FlagErrorType.DUPLICATE_CONFLICT, e.getErrorType());
        assertTrue(e.getMessage().contains("new-checkout"));
        log.info("[通过] 冲突被拒绝，错误类型={}，信息={}", e.getErrorType(), e.getMessage());
    }

    @Test
    void invalidRolloutPercentageIsRejected() {
        log.info("[用例] 非法取值：灰度比例超出 0-100，拒绝并给出 INVALID_VALUE");
        InMemoryFlagSource source = new InMemoryFlagSource("file", 10);
        source.setFlags(Map.of("bad-flag", flag("bad-flag", true, 150)));

        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagErrorType.INVALID_VALUE, e.getErrorType());
        assertTrue(e.getMessage().contains("bad-flag"));
        log.info("[通过] 非法比例被拒绝，错误类型={}，信息={}", e.getErrorType(), e.getMessage());
    }

    @Test
    void duplicateRuleOnSameAttributeIsRejected() {
        log.info("[用例] 同一开关内对同一属性定义重复规则：拒绝并给出 DUPLICATE_CONFLICT");
        FlagDefinition def = new FlagDefinition("promo", true, 100, List.of(
                new TargetRule("region", TargetRule.Operator.EQUALS, List.of("cn"), "r1"),
                new TargetRule("region", TargetRule.Operator.IN, List.of("us"), "r2")));
        InMemoryFlagSource source = new InMemoryFlagSource("file", 10);
        source.setFlags(Map.of("promo", def));

        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagErrorType.DUPLICATE_CONFLICT, e.getErrorType());
        log.info("[通过] 重复规则被拒绝，错误类型={}，信息={}", e.getErrorType(), e.getMessage());
    }

    @Test
    void emptySourceListIsRejected() {
        log.info("[用例] 来源列表为空：拒绝，不允许用空配置兜底");
        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of()));

        assertEquals(FlagErrorType.INVALID_VALUE, e.getErrorType());
        log.info("[通过] 空来源被拒绝，错误类型={}", e.getErrorType());
    }

    @Test
    void keyMismatchWithDefinitionNameIsRejected() {
        log.info("[用例] 来源中键与定义名不一致：拒绝并给出 INVALID_VALUE");
        InMemoryFlagSource source = new InMemoryFlagSource("file", 10);
        source.setFlags(Map.of("key-a", flag("name-b", true, 10)));

        FlagConfigException e = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagErrorType.INVALID_VALUE, e.getErrorType());
        log.info("[通过] 键名不一致被拒绝，信息={}", e.getMessage());
    }
}
