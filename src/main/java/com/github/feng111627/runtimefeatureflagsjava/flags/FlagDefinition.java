package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.List;

/**
 * 单个开关的定义：是否启用、灰度比例（0-100）、定向规则列表。
 */
public record FlagDefinition(
        String name,
        boolean enabled,
        int rolloutPercentage,
        List<TargetRule> rules
) {
}
