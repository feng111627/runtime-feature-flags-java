package com.github.feng111627.runtimefeatureflagsjava.flags.source;

import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 可动态替换内容的内存配置来源，便于运行时更新与测试。 */
public class InMemoryFlagSource implements FlagSource {

    private final String name;
    private final int priority;
    private final AtomicReference<Map<String, FlagDefinition>> flags;

    public InMemoryFlagSource(String name, int priority) {
        this(name, priority, Map.of());
    }

    public InMemoryFlagSource(String name, int priority, Map<String, FlagDefinition> initial) {
        this.name = name;
        this.priority = priority;
        this.flags = new AtomicReference<>(Map.copyOf(initial));
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
        return flags.get();
    }

    /** 整体替换该来源的定义集合，对下一次 reload 生效。 */
    public void replace(Map<String, FlagDefinition> newFlags) {
        flags.set(Map.copyOf(newFlags));
    }
}
