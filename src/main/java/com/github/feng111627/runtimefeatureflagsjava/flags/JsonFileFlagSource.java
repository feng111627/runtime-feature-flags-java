package com.github.feng111627.runtimefeatureflagsjava.flags;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JSON 文件加载开关定义的配置来源。
 *
 * 文件格式：
 * <pre>{
 *   "flags": [
 *     {
 *       "name": "new-checkout",
 *       "enabled": true,
 *       "rolloutPercentage": 30,
 *       "rules": [
 *         {"attribute": "group", "operator": "IN", "values": ["beta"], "description": "beta 组"}
 *       ]
 *     }
 *   ]
 * }</pre>
 *
 * 字段类型不匹配（如 rolloutPercentage 写成字符串）会抛出
 * {@link FlagErrorType#TYPE_MISMATCH}，不做静默转换。
 */
public class JsonFileFlagSource implements FlagSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final int priority;
    private final Path path;

    public JsonFileFlagSource(String name, int priority, Path path) {
        this.name = name;
        this.priority = priority;
        this.path = path;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, FlagDefinition> load() {
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置文件失败: " + path, e);
        }
        JsonNode flagsNode = root.required("flags");
        if (!flagsNode.isArray()) {
            throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                    "来源 [" + name + "] 的 flags 字段必须是数组");
        }
        Map<String, FlagDefinition> result = new LinkedHashMap<>();
        for (JsonNode node : flagsNode) {
            FlagDefinition def = parseFlag(node);
            if (result.put(def.name(), def) != null) {
                throw new FlagConfigException(FlagErrorType.DUPLICATE_CONFLICT,
                        "来源 [" + name + "] 内开关 [" + def.name() + "] 被重复定义");
            }
        }
        return result;
    }

    private FlagDefinition parseFlag(JsonNode node) {
        String flagName = node.path("name").isTextual() ? node.get("name").asText() : "<未命名>";
        String name = requireText(node, "name", flagName);
        boolean enabled = requireBoolean(node, "enabled", flagName);
        int pct = requireInt(node, "rolloutPercentage", flagName);
        List<TargetRule> rules = new ArrayList<>();
        JsonNode rulesNode = node.path("rules");
        if (!rulesNode.isMissingNode() && !rulesNode.isNull()) {
            if (!rulesNode.isArray()) {
                throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                        "开关 [" + flagName + "] 的 rules 字段必须是数组");
            }
            for (JsonNode ruleNode : rulesNode) {
                rules.add(parseRule(ruleNode, flagName));
            }
        }
        return new FlagDefinition(name, enabled, pct, List.copyOf(rules));
    }

    private TargetRule parseRule(JsonNode node, String flagName) {
        String attribute = requireText(node, "attribute", flagName);
        String operatorText = requireText(node, "operator", flagName);
        TargetRule.Operator operator;
        try {
            operator = TargetRule.Operator.valueOf(operatorText);
        } catch (IllegalArgumentException e) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "开关 [" + flagName + "] 的规则操作符非法: " + operatorText + "，仅支持 EQUALS/IN");
        }
        JsonNode valuesNode = node.required("values");
        if (!valuesNode.isArray() || valuesNode.isEmpty()) {
            throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                    "开关 [" + flagName + "] 的规则 values 必须是非空数组");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode v : valuesNode) {
            if (!v.isTextual()) {
                throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                        "开关 [" + flagName + "] 的规则 values 元素必须是字符串，实际: " + v.getNodeType());
            }
            values.add(v.asText());
        }
        String description = node.path("description").isTextual() ? node.get("description").asText() : "";
        return new TargetRule(attribute, operator, List.copyOf(values), description);
    }

    private String requireText(JsonNode node, String field, String flagName) {
        JsonNode value = node.required(field);
        if (!value.isTextual()) {
            throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                    "开关 [" + flagName + "] 的 " + field + " 字段必须是字符串，实际: " + value.getNodeType());
        }
        return value.asText();
    }

    private boolean requireBoolean(JsonNode node, String field, String flagName) {
        JsonNode value = node.required(field);
        if (!value.isBoolean()) {
            throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                    "开关 [" + flagName + "] 的 " + field + " 字段必须是布尔值，实际: " + value.getNodeType());
        }
        return value.asBoolean();
    }

    private int requireInt(JsonNode node, String field, String flagName) {
        JsonNode value = node.required(field);
        if (!value.isIntegralNumber()) {
            throw new FlagConfigException(FlagErrorType.TYPE_MISMATCH,
                    "开关 [" + flagName + "] 的 " + field + " 字段必须是整数，实际: " + value.getNodeType());
        }
        return value.intValue();
    }
}
