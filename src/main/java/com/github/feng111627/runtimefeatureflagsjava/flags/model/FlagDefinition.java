package com.github.feng111627.runtimefeatureflagsjava.flags.model;

import java.util.Set;

/**
 * 单个特性开关的定义。
 *
 * @param name            开关名（唯一标识）
 * @param enabled         是否启用
 * @param rolloutPercent  灰度比例 0-100
 * @param targetedUserIds 定向命中的用户标识集合
 */
public record FlagDefinition(String name, boolean enabled, int rolloutPercent, Set<String> targetedUserIds) {

    public FlagDefinition {
        targetedUserIds = targetedUserIds == null ? Set.of() : Set.copyOf(targetedUserIds);
    }

    public static FlagDefinition of(String name, boolean enabled, int rolloutPercent, Set<String> targetedUserIds) {
        return new FlagDefinition(name, enabled, rolloutPercent, targetedUserIds);
    }
}
