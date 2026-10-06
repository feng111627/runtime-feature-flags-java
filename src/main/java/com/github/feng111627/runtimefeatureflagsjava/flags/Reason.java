package com.github.feng111627.runtimefeatureflagsjava.flags;

/**
 * 一次判定的命中原因，用于解释判定结果。
 */
public enum Reason {
    /** 开关不存在 */
    FLAG_NOT_FOUND,
    /** 开关被关闭 */
    FLAG_DISABLED,
    /** 命中定向规则 */
    RULE_HIT,
    /** 未命中定向规则，按灰度比例命中 */
    ROLLOUT_HIT,
    /** 未命中定向规则，灰度比例也未命中 */
    ROLLOUT_MISS
}
