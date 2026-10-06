package com.github.feng111627.runtimefeatureflagsjava.flags.config;

import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;

/** 校验单个开关定义的合法性，非法时抛出 FlagConfigException。 */
public final class FlagConfigValidator {

    private FlagConfigValidator() {
    }

    public static void validate(FlagDefinition definition, String sourceName) {
        if (definition == null) {
            throw new FlagConfigException(FlagConfigException.ErrorType.TYPE_MISMATCH,
                    "来源 [" + sourceName + "] 返回了 null 的开关定义，类型不匹配");
        }
        String name = definition.name();
        if (name == null || name.isBlank()) {
            throw new FlagConfigException(FlagConfigException.ErrorType.INVALID_VALUE,
                    "来源 [" + sourceName + "] 存在开关名为空的定义");
        }
        if (definition.rolloutPercent() < 0 || definition.rolloutPercent() > 100) {
            throw new FlagConfigException(FlagConfigException.ErrorType.INVALID_VALUE,
                    "开关 [" + name + "]（来源 " + sourceName + "）灰度比例非法: "
                            + definition.rolloutPercent() + "，必须在 0-100 之间");
        }
        for (String userId : definition.targetedUserIds()) {
            if (userId == null || userId.isBlank()) {
                throw new FlagConfigException(FlagConfigException.ErrorType.INVALID_VALUE,
                        "开关 [" + name + "]（来源 " + sourceName + "）定向用户标识不能为空");
            }
        }
    }
}
