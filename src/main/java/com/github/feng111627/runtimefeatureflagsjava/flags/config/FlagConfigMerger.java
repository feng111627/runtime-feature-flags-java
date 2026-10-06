package com.github.feng111627.runtimefeatureflagsjava.flags.config;

import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.FlagSource;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按来源优先级合并多个来源的开关定义。
 *
 * <p>规则：
 * <ul>
 *   <li>每个定义先经过 {@link FlagConfigValidator} 校验，非法即整体拒绝；</li>
 *   <li>同一开关在多个来源出现且内容完全一致时，取优先级最高来源的定义；</li>
 *   <li>同一开关重复定义但内容互相冲突时，抛出 CONFLICTING_DEFINITION，拒绝本次合并，
 *       不做静默覆盖、不用默认值兜底。</li>
 * </ul>
 */
public final class FlagConfigMerger {

    private FlagConfigMerger() {
    }

    public static Map<String, FlagDefinition> merge(List<FlagSource> sources) {
        // 优先级从低到高处理，高优先级来源的同名定义最后写入。
        List<FlagSource> ordered = sources.stream()
                .sorted(Comparator.comparingInt(FlagSource::priority))
                .toList();

        Map<String, FlagDefinition> merged = new LinkedHashMap<>();
        Map<String, String> originByFlag = new LinkedHashMap<>();

        for (FlagSource source : ordered) {
            Map<String, FlagDefinition> loaded = source.load();
            if (loaded == null) {
                throw new FlagConfigException(FlagConfigException.ErrorType.TYPE_MISMATCH,
                        "来源 [" + source.name() + "] 返回了 null 的定义集合，类型不匹配");
            }
            for (Map.Entry<String, FlagDefinition> entry : loaded.entrySet()) {
                FlagDefinition definition = entry.getValue();
                FlagConfigValidator.validate(definition, source.name());
                String flagName = definition.name();
                if (!flagName.equals(entry.getKey())) {
                    throw new FlagConfigException(FlagConfigException.ErrorType.INVALID_VALUE,
                            "来源 [" + source.name() + "] 中 key [" + entry.getKey()
                                    + "] 与定义内开关名 [" + flagName + "] 不一致");
                }
                FlagDefinition existing = merged.get(flagName);
                if (existing != null && !existing.equals(definition)) {
                    throw new FlagConfigException(FlagConfigException.ErrorType.CONFLICTING_DEFINITION,
                            "开关 [" + flagName + "] 在来源 [" + originByFlag.get(flagName) + "] 与来源 ["
                                    + source.name() + "] 重复定义且内容冲突，拒绝合并");
                }
                merged.put(flagName, definition);
                originByFlag.put(flagName, source.name());
            }
        }
        return Map.copyOf(merged);
    }
}
