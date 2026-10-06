package com.github.feng111627.runtimefeatureflagsjava.flags;

/**
 * 配置更新被拒绝时抛出，携带可区分的错误类型。
 */
public class FlagConfigException extends RuntimeException {

    private final FlagErrorType errorType;

    public FlagConfigException(FlagErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public FlagConfigException(FlagErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public FlagErrorType getErrorType() {
        return errorType;
    }
}
