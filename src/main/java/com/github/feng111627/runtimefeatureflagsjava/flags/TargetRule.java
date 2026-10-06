package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.List;

/**
 * 定向规则：按上下文中某个属性（如用户组、地区）匹配，命中则直接生效。
 * 规则在开关内按列表顺序排列，靠前的优先级更高。
 */
public record TargetRule(
        String attribute,
        Operator operator,
        List<String> values,
        String description
) {
    public enum Operator {
        EQUALS,
        IN
    }
}
