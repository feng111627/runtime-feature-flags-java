package com.github.feng111627.runtimefeatureflagsjava.flags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认实现：
 * - 当前配置保存在 {@link AtomicReference} 中，更新时先在锁内完成加载、校验、合并，
 *   再一次引用切换整批生效，读方无锁且永远看到完整快照；
 * - 校验或合并失败时直接抛异常，引用不被替换，当前配置不受影响；
 * - 每次成功更新把旧快照压入历史栈，回滚即弹出上一版重新生效。
 */
@Service
public class DefaultFeatureFlagService implements FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(DefaultFeatureFlagService.class);
    private static final int MAX_HISTORY = 16;

    private final AtomicReference<FlagSnapshot> current =
            new AtomicReference<>(new FlagSnapshot(0, Map.of()));
    private final Deque<FlagSnapshot> history = new ArrayDeque<>();
    private final Object updateLock = new Object();

    @Override
    public long currentVersion() {
        return current.get().version();
    }

    @Override
    public FlagSnapshot snapshot() {
        return current.get();
    }

    @Override
    public Evaluation evaluate(String flagName, String identifier, Map<String, String> attributes) {
        // 读方无锁：拿到的是某一版完整快照，判定结果只依赖该快照
        return current.get().evaluate(flagName, identifier, attributes);
    }

    @Override
    public long refresh(List<FlagSource> sources) {
        synchronized (updateLock) {
            // 先完整构建新配置，任何一步失败都不会触碰当前引用
            Map<String, FlagDefinition> merged = FlagConfigMerger.merge(sources);
            FlagSnapshot previous = current.get();
            FlagSnapshot next = new FlagSnapshot(previous.version() + 1, merged);
            current.set(next);
            history.push(previous);
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
            log.info("配置已整批生效：version {} -> {}，开关数={}",
                    previous.version(), next.version(), merged.size());
            return next.version();
        }
    }

    @Override
    public void rollback() {
        synchronized (updateLock) {
            FlagSnapshot previous = history.poll();
            if (previous == null) {
                throw new FlagConfigException(FlagErrorType.NO_ROLLBACK_VERSION,
                        "没有可回退的历史版本");
            }
            FlagSnapshot currentSnapshot = current.get();
            // 以新版本号重新发布旧配置，保证版本号单调递增且可解释
            FlagSnapshot restored = new FlagSnapshot(currentSnapshot.version() + 1, previous.flags());
            current.set(restored);
            log.info("已回滚：version {} -> {}（内容来自 version {}）",
                    currentSnapshot.version(), restored.version(), previous.version());
        }
    }
}
