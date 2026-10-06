package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.Map;

/**
 * 内存配置来源，便于测试与运行时动态推送。
 */
public class InMemoryFlagSource implements FlagSource {

    private final String name;
    private final int priority;
    private volatile Map<String, FlagDefinition> flags = Map.of();

    public InMemoryFlagSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void setFlags(Map<String, FlagDefinition> flags) {
        this.flags = flags;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, FlagDefinition> load() {
        return flags;
    }
}
