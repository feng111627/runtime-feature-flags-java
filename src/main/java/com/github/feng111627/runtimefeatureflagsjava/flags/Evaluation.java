package com.github.feng111627.runtimefeatureflagsjava.flags;

/**
 * 一次开关判定的结果：取值、原因、分桶值与命中的规则，全部可解释。
 */
public record Evaluation(
        String flagName,
        boolean value,
        Reason reason,
        int bucket,
        String matchedRule,
        long configVersion
) {
}
