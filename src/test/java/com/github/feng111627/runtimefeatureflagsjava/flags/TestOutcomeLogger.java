package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 在单测日志中打印每个用例覆盖的业务点与通过情况。 */
public class TestOutcomeLogger implements TestWatcher, BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(TestOutcomeLogger.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("[用例开始] {}#{}", context.getRequiredTestClass().getSimpleName(),
                context.getRequiredTestMethod().getName());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // 结果由 TestWatcher 回调打印
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        log.info("[用例通过] {}", context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        log.error("[用例失败] {} -> {}", context.getDisplayName(), cause.getMessage());
    }
}
