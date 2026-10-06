package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.HashSet;
import java.util.Set;

/**
 * 校验单个开关定义的合法性，非法时抛出 {@link FlagConfigException}。
 * 校验失败必须拒绝整次更新，不允许静默兜底。
 */
public final class FlagValidator {

    private FlagValidator() {
    }

    public static void validate(FlagDefinition definition, String sourceName) {
        if (definition == null) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "来源 [" + sourceName + "] 存在为 null 的开关定义");
        }
        String name = definition.name();
        if (name == null || name.isBlank()) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "来源 [" + sourceName + "] 存在名称为空的开关定义");
        }
        int pct = definition.rolloutPercentage();
        if (pct < 0 || pct > 100) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "开关 [" + name + "]（来源 " + sourceName + "）灰度比例非法: " + pct + "，必须在 0-100 之间");
        }
        if (definition.rules() == null) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "开关 [" + name + "]（来源 " + sourceName + "）规则列表为 null");
        }
        Set<String> seenAttributes = new HashSet<>();
        for (int i = 0; i < definition.rules().size(); i++) {
            TargetRule rule = definition.rules().get(i);
            if (rule == null) {
                throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）第 " + i + " 条规则为 null");
            }
            if (rule.attribute() == null || rule.attribute().isBlank()) {
                throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）第 " + i + " 条规则属性名为空");
            }
            if (rule.operator() == null) {
                throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）第 " + i + " 条规则缺少操作符");
            }
            if (rule.values() == null || rule.values().isEmpty()
                    || rule.values().stream().anyMatch(v -> v == null || v.isBlank())) {
                throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）第 " + i + " 条规则的取值列表为空或含空值");
            }
            if (rule.operator() == TargetRule.Operator.EQUALS && rule.values().size() != 1) {
                throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）第 " + i
                                + " 条规则使用 EQUALS 时必须且只能有一个取值");
            }
            if (!seenAttributes.add(rule.attribute())) {
                throw new FlagConfigException(FlagErrorType.DUPLICATE_CONFLICT,
                        "开关 [" + name + "]（来源 " + sourceName + "）存在针对同一属性 ["
                                + rule.attribute() + "] 的重复规则，无法确定命中优先级");
            }
        }
    }
}
