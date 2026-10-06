package com.github.feng111627.runtimefeatureflagsjava.flags.core;

import com.github.feng111627.runtimefeatureflagsjava.flags.config.FlagConfigMerger;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.EvaluationReason;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.EvaluationResult;
import com.github.feng111627.runtimefeatureflagsjava.flags.model.FlagDefinition;
import com.github.feng111627.runtimefeatureflagsjava.flags.source.FlagSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 特性开关核心服务。
 *
 * <p>并发语义：读方通过 {@link AtomicReference} 一次性拿到整份不可变快照，
 * 写方先在锁内完成校验与合并，再一次原子替换引用，因此读方永远不会看到
 * 只更新了一半的中间状态；校验或合并失败时当前生效配置保持不变。
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final List<FlagSource> sources;
    private final AtomicReference<FlagSnapshot> current;
    private final AtomicReference<FlagSnapshot> previous;
    private final Object updateLock = new Object();

    public FeatureFlagService(List<FlagSource> sources) {
        this.sources = List.copyOf(sources);
        this.current = new AtomicReference<>(new FlagSnapshot(0, Map.of()));
        this.previous = new AtomicReference<>(null);
    }

    /** 从所有来源重新加载、校验、合并，并整批原子发布；失败抛异常且不影响当前生效配置。 */
    public FlagSnapshot reload() {
        synchronized (updateLock) {
            // 先在锁内完成全部校验与合并，任何异常都在替换引用之前抛出。
            Map<String, FlagDefinition> merged = FlagConfigMerger.merge(sources);
            FlagSnapshot before = current.get();
            FlagSnapshot next = new FlagSnapshot(before.version() + 1, merged);
            previous.set(before);
            current.set(next);
            log.info("配置发布成功: version={}, flags={}", next.version(), merged.keySet());
            return next;
        }
    }

    /** 回退到上一次有效版本；没有历史版本时返回当前版本。 */
    public FlagSnapshot rollback() {
        synchronized (updateLock) {
            FlagSnapshot prev = previous.get();
            if (prev == null) {
                log.warn("没有可回退的历史版本，保持当前 version={}", current.get().version());
                return current.get();
            }
            FlagSnapshot cur = current.get();
            // 回退也视为一次新版本发布，保证版本号单调递增。
            FlagSnapshot restored = new FlagSnapshot(cur.version() + 1, prev.flags());
            previous.set(cur);
            current.set(restored);
            log.info("配置回退成功: version={}, 恢复到 version={} 的内容", restored.version(), prev.version());
            return restored;
        }
    }

    /** 对指定用户标识判定开关是否命中，结果附带命中原因与配置版本。 */
    public EvaluationResult evaluate(String flagName, String userId) {
        FlagSnapshot snapshot = current.get();
        FlagDefinition definition = snapshot.flags().get(flagName);
        if (definition == null) {
            return new EvaluationResult(flagName, false, EvaluationReason.FLAG_NOT_FOUND, snapshot.version());
        }
        if (!definition.enabled()) {
            return new EvaluationResult(flagName, false, EvaluationReason.FLAG_DISABLED, snapshot.version());
        }
        if (userId != null && definition.targetedUserIds().contains(userId)) {
            return new EvaluationResult(flagName, true, EvaluationReason.TARGETED_USER, snapshot.version());
        }
        int bucket = userId == null ? -1 : Bucketer.bucketOf(flagName, userId);
        boolean hit = bucket >= 0 && bucket < definition.rolloutPercent();
        return new EvaluationResult(flagName, hit,
                hit ? EvaluationReason.ROLLOUT_HIT : EvaluationReason.ROLLOUT_MISS, snapshot.version());
    }

    public FlagSnapshot currentSnapshot() {
        return current.get();
    }
}
