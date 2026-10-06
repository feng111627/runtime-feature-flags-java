package com.github.feng111627.runtimefeatureflagsjava.flags.model;

/** 一次开关判定的结果，包含是否命中与命中原因。 */
public record EvaluationResult(String flagName, boolean on, EvaluationReason reason, long configVersion) {
}
