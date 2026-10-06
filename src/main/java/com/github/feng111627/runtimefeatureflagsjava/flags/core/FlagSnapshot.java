package com.github.feng111627.runtimefeatureflagsjava.flags.core;

import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;

import java.util.Map;

/** 一份不可变的生效配置快照，版本号单调递增。 */
public record FlagSnapshot(long version, Map<String, FlagDefinition> flags) {
}
