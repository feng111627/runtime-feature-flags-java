package com.github.feng111627.runtimefeatureflagsjava.flags;

import com.github.feng111627.runtimefeatureflagsjava.flags.config.FlagConfigException;
import com.github.feng111627.runtimefeatureflagsjava.flags.config.FlagConfigMerger;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.FlagSource;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.InMemoryFlagSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 业务覆盖：多来源配置合并、优先级、非法配置被拒绝。 */
@ExtendWith(TestOutcomeLogger.class)
class FlagConfigMergerTest {

    @Test
    @DisplayName("合并：多个来源的开关定义合并为一份全量配置")
    void mergesFlagsFromMultipleSources() {
        FlagSource low = new InMemoryFlagSource("low", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 50, Set.of())));
        FlagSource high = new InMemoryFlagSource("high", 2,
                Map.of("flag-b", FlagDefinition.of("flag-b", false, 0, Set.of("u1"))));

        Map<String, FlagDefinition> merged = FlagConfigMerger.merge(List.of(low, high));

        assertEquals(2, merged.size());
        assertEquals(50, merged.get("flag-a").rolloutPercent());
        assertEquals(Set.of("u1"), merged.get("flag-b").targetedUserIds());
    }

    @Test
    @DisplayName("合并：同名开关内容一致时取高优先级来源，不视为冲突")
    void identicalDuplicateTakesHigherPriority() {
        FlagDefinition same = FlagDefinition.of("flag-a", true, 30, Set.of());
        FlagSource low = new InMemoryFlagSource("low", 1, Map.of("flag-a", same));
        FlagSource high = new InMemoryFlagSource("high", 2, Map.of("flag-a", same));

        Map<String, FlagDefinition> merged = FlagConfigMerger.merge(List.of(low, high));

        assertEquals(1, merged.size());
        assertEquals(same, merged.get("flag-a"));
    }

    @Test
    @DisplayName("拒绝：同名开关在不同来源内容冲突时抛出 CONFLICTING_DEFINITION")
    void conflictingDuplicateIsRejected() {
        FlagSource low = new InMemoryFlagSource("low", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 30, Set.of())));
        FlagSource high = new InMemoryFlagSource("high", 2,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 60, Set.of())));

        FlagConfigException ex = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(low, high)));

        assertEquals(FlagConfigException.ErrorType.CONFLICTING_DEFINITION, ex.errorType());
    }

    @Test
    @DisplayName("拒绝：灰度比例越界时抛出 INVALID_VALUE")
    void invalidRolloutPercentIsRejected() {
        FlagSource source = new InMemoryFlagSource("bad", 1,
                Map.of("flag-a", FlagDefinition.of("flag-a", true, 101, Set.of())));

        FlagConfigException ex = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagConfigException.ErrorType.INVALID_VALUE, ex.errorType());
    }

    @Test
    @DisplayName("拒绝：开关名为空时抛出 INVALID_VALUE")
    void blankFlagNameIsRejected() {
        FlagSource source = new InMemoryFlagSource("bad", 1,
                Map.of(" ", FlagDefinition.of(" ", true, 10, Set.of())));

        FlagConfigException ex = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagConfigException.ErrorType.INVALID_VALUE, ex.errorType());
    }

    @Test
    @DisplayName("拒绝：来源返回 null 定义集合时抛出 TYPE_MISMATCH")
    void nullDefinitionsAreRejected() {
        FlagSource source = new FlagSource() {
            @Override
            public String name() {
                return "null-source";
            }

            @Override
            public int priority() {
                return 1;
            }

            @Override
            public Map<String, FlagDefinition> load() {
                return null;
            }
        };

        FlagConfigException ex = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagConfigException.ErrorType.TYPE_MISMATCH, ex.errorType());
    }

    @Test
    @DisplayName("拒绝：key 与定义内开关名不一致时抛出 INVALID_VALUE")
    void mismatchedKeyIsRejected() {
        FlagSource source = new InMemoryFlagSource("bad", 1,
                Map.of("flag-x", FlagDefinition.of("flag-y", true, 10, Set.of())));

        FlagConfigException ex = assertThrows(FlagConfigException.class,
                () -> FlagConfigMerger.merge(List.of(source)));

        assertEquals(FlagConfigException.ErrorType.INVALID_VALUE, ex.errorType());
    }
}
