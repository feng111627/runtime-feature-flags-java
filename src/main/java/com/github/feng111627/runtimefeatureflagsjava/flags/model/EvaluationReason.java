package com.github.feng111627.runtimefeatureflagsjava.flags.model;

/** 开关判定命中原因，用于解释每次判定结果。 */
public enum EvaluationReason {
    FLAG_NOT_FOUND,
    FLAG_DISABLED,
    TARGETED_USER,
    ROLLOUT_HIT,
    ROLLOUT_MISS
}
