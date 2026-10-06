package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将多个来源的开关定义按优先级合并为一份配置。
 *
 * 合并规则：
 * 1. 每个来源先独立加载并逐项校验，任何非法项导致整体拒绝；
 * 2. 同名开关出现在不同来源时，优先级高（priority 数值大）的来源生效，
 *    被覆盖的一方会显式记录日志，不做静默覆盖；
 * 3. 同名开关出现在优先级相同的两个来源、且定义不一致时，视为冲突，整体拒绝；
 *    定义完全一致则去重保留一份。
 */
public final class FlagConfigMerger {

    private static final Logger log = LoggerFactory.getLogger(FlagConfigMerger.class);

    private FlagConfigMerger() {
    }

    public static Map<String, FlagDefinition> merge(List<FlagSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                    "配置来源列表为空，拒绝本次更新（不允许用空配置兜底）");
        }
        // 按优先级升序加载，后写覆盖先写，高优先级自然生效
        List<FlagSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(FlagSource::priority));

        // flagName -> (来源, 定义)，用于冲突检测
        Map<String, FlagDefinition> merged = new LinkedHashMap<>();
        Map<String, FlagSource> originOf = new LinkedHashMap<>();

        for (FlagSource source : ordered) {
            Map<String, FlagDefinition> loaded;
            try {
                loaded = source.load();
            } catch (FlagConfigException e) {
                throw e;
            } catch (Exception e) {
                throw new FlagConfigException(FlagErrorType.SOURCE_LOAD_FAILED,
                        "来源 [" + source.name() + "] 加载失败: " + e.getMessage(), e);
            }
            if (loaded == null) {
                throw new FlagConfigException(FlagErrorType.SOURCE_LOAD_FAILED,
                        "来源 [" + source.name() + "] 返回了 null 配置");
            }
            for (Map.Entry<String, FlagDefinition> entry : loaded.entrySet()) {
                FlagDefinition definition = entry.getValue();
                FlagValidator.validate(definition, source.name());
                if (!entry.getKey().equals(definition.name())) {
                    throw new FlagConfigException(FlagErrorType.INVALID_VALUE,
                            "来源 [" + source.name() + "] 中键 [" + entry.getKey()
                                    + "] 与定义名 [" + definition.name() + "] 不一致");
                }
                FlagDefinition existing = merged.get(definition.name());
                if (existing == null) {
                    merged.put(definition.name(), definition);
                    originOf.put(definition.name(), source);
                    continue;
                }
                FlagSource existingSource = originOf.get(definition.name());
                if (existingSource.priority() == source.priority()) {
                    if (existing.equals(definition)) {
                        log.info("开关 [{}] 在来源 [{}] 与 [{}] 中定义一致，去重保留一份",
                                definition.name(), existingSource.name(), source.name());
                        continue;
                    }
                    throw new FlagConfigException(FlagErrorType.DUPLICATE_CONFLICT,
                            "开关 [" + definition.name() + "] 在优先级相同的来源 [" + existingSource.name()
                                    + "] 与 [" + source.name() + "] 中定义冲突，拒绝本次更新");
                }
                // 当前来源优先级更高（升序遍历，后到的同级或更高级）
                log.info("开关 [{}] 同时定义于来源 [{}](优先级 {}) 与 [{}](优先级 {})，按优先级采用后者",
                        definition.name(), existingSource.name(), existingSource.priority(),
                        source.name(), source.priority());
                merged.put(definition.name(), definition);
                originOf.put(definition.name(), source);
            }
        }
        return Map.copyOf(merged);
    }
}
