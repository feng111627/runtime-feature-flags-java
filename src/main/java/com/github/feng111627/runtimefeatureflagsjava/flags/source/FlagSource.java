package com.github.feng111627.runtimefeatureflagsjava.flags.source;

import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;

import java.util.Map;

/** 配置来源：不同来源有不同优先级，数值越大优先级越高。 */
public interface FlagSource {

    String name();

    int priority();

    /** 加载该来源的全量开关定义，key 为开关名。 */
    Map<String, FlagDefinition> load();
}
