package com.github.feng111627.runtimefeatureflagsjava.flags.config;

/** 配置加载/合并失败时抛出，携带可区分原因的错误类型。 */
public class FlagConfigException extends RuntimeException {

    public enum ErrorType {
        /** 取值非法（如灰度比例越界、开关名为空）。 */
        INVALID_VALUE,
        /** 类型不匹配（如来源返回了无法解析的定义）。 */
        TYPE_MISMATCH,
        /** 同一开关在多个来源重复定义且内容互相冲突。 */
        CONFLICTING_DEFINITION
    }

    private final ErrorType errorType;

    public FlagConfigException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ErrorType errorType() {
        return errorType;
    }
}
