package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.Map;

/**
 * 配置来源。不同来源通过 {@link #priority()} 区分优先级，数值越大优先级越高。
 */
public interface FlagSource {

    /** 来源名称，用于错误信息与日志 */
    String name();

    /** 优先级，数值越大优先级越高 */
    int priority();

    /** 加载该来源的全量开关定义 */
    Map<String, FlagDefinition> load();
}
