package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务场景：JSON 文件配置来源。
 * 覆盖：正常加载、类型不匹配拒绝（TYPE_MISMATCH）、文件内重复定义拒绝。
 */
class JsonFileFlagSourceTest {

    private static final Logger log = LoggerFactory.getLogger(JsonFileFlagSourceTest.class);

    @TempDir
    Path dir;

    private JsonFileFlagSource sourceOf(String json) throws Exception {
        Path file = dir.resolve("flags.json");
        Files.writeString(file, json);
        return new JsonFileFlagSource("json-file", 10, file);
    }

    @Test
    void loadsValidDefinitions() throws Exception {
        log.info("[用例] JSON 来源：加载合法定义（含定向规则）");
        JsonFileFlagSource source = sourceOf("""
                {"flags": [
                  {"name": "new-checkout", "enabled": true, "rolloutPercentage": 30,
                   "rules": [{"attribute": "group", "operator": "IN",
                              "values": ["beta", "internal"], "description": "beta 组"}]}
                ]}""");

        Map<String, FlagDefinition> flags = source.load();

        FlagDefinition def = flags.get("new-checkout");
        assertEquals(30, def.rolloutPercentage());
        assertEquals(1, def.rules().size());
        assertEquals(TargetRule.Operator.IN, def.rules().get(0).operator());
        log.info("[通过] 加载成功：{}", def);
    }

    @Test
    void typeMismatchIsRejected() throws Exception {
        log.info("[用例] JSON 来源：rolloutPercentage 写成字符串，拒绝并给出 TYPE_MISMATCH");
        JsonFileFlagSource source = sourceOf("""
                {"flags": [{"name": "f", "enabled": true, "rolloutPercentage": "thirty"}]}""");

        FlagConfigException e = assertThrows(FlagConfigException.class, source::load);

        assertEquals(FlagErrorType.TYPE_MISMATCH, e.getErrorType());
        log.info("[通过] 类型不匹配被拒绝，信息={}", e.getMessage());
    }

    @Test
    void duplicateDefinitionInOneFileIsRejected() throws Exception {
        log.info("[用例] JSON 来源：同一文件内重复定义同名开关，拒绝并给出 DUPLICATE_CONFLICT");
        JsonFileFlagSource source = sourceOf("""
                {"flags": [
                  {"name": "f", "enabled": true, "rolloutPercentage": 10},
                  {"name": "f", "enabled": true, "rolloutPercentage": 20}
                ]}""");

        FlagConfigException e = assertThrows(FlagConfigException.class, source::load);

        assertEquals(FlagErrorType.DUPLICATE_CONFLICT, e.getErrorType());
        assertTrue(e.getMessage().contains("f"));
        log.info("[通过] 文件内重复定义被拒绝，信息={}", e.getMessage());
    }
}
