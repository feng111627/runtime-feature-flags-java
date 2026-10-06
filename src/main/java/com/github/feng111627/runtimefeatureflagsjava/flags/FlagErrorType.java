package com.github.feng111627.runtimefeatureflagsjava.flags;

/**
 * 配置更新被拒绝时的错误分类，便于调用方区分原因。
 */
public enum FlagErrorType {
    /** 取值非法（如灰度比例超出 0-100） */
    INVALID_VALUE,
    /** 类型不匹配（如 JSON 中比例写成了字符串） */
    TYPE_MISMATCH,
    /** 重复定义且互相冲突 */
    DUPLICATE_CONFLICT,
    /** 来源加载失败 */
    SOURCE_LOAD_FAILED,
    /** 没有可回退的历史版本 */
    NO_ROLLBACK_VERSION
}
