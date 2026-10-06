package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.Map;

/**
 * 一份不可变的生效配置快照。读取方永远看到完整的某一版快照，
 * 判定只依赖快照内容，因此同版本下同标识的判定结果必然一致。
 */
public final class FlagSnapshot {

    private final long version;
    private final Map<String, FlagDefinition> flags;

    public FlagSnapshot(long version, Map<String, FlagDefinition> flags) {
        this.version = version;
        this.flags = Map.copyOf(flags);
    }

    public long version() {
        return version;
    }

    public Map<String, FlagDefinition> flags() {
        return flags;
    }

    /**
     * 对某个标识判定某个开关。
     *
     * 判定顺序（命中即返回，原因可解释）：
     * 1. 开关不存在 -> FLAG_NOT_FOUND，返回 false；
     * 2. 开关未启用 -> FLAG_DISABLED，返回 false；
     * 3. 按定义顺序逐条匹配定向规则，命中 -> RULE_HIT，返回 true；
     * 4. 规则均未命中，按稳定分桶与灰度比例判定：
     *    bucket < rolloutPercentage -> ROLLOUT_HIT，否则 ROLLOUT_MISS。
     */
    public Evaluation evaluate(String flagName, String identifier, Map<String, String> attributes) {
        FlagDefinition definition = flags.get(flagName);
        if (definition == null) {
            return new Evaluation(flagName, false, Reason.FLAG_NOT_FOUND, -1, null, version);
        }
        int bucket = Bucketer.bucket(flagName, identifier);
        if (!definition.enabled()) {
            return new Evaluation(flagName, false, Reason.FLAG_DISABLED, bucket, null, version);
        }
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        for (TargetRule rule : definition.rules()) {
            String actual = attrs.get(rule.attribute());
            if (actual == null) {
                continue;
            }
            boolean hit = switch (rule.operator()) {
                case EQUALS -> actual.equals(rule.values().get(0));
                case IN -> rule.values().contains(actual);
            };
            if (hit) {
                return new Evaluation(flagName, true, Reason.RULE_HIT, bucket,
                        rule.attribute() + " " + rule.operator() + " " + rule.values(), version);
            }
        }
        boolean inRollout = bucket < definition.rolloutPercentage();
        return new Evaluation(flagName, inRollout,
                inRollout ? Reason.ROLLOUT_HIT : Reason.ROLLOUT_MISS, bucket, null, version);
    }
}
