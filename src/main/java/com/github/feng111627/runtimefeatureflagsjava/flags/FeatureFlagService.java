package com.github.feng111627.runtimefeatureflagsjava.flags;

import java.util.List;
import java.util.Map;

/**
 * 特性开关服务：整批原子更新、判定、回滚。
 */
public interface FeatureFlagService {

    /** 当前生效版本号 */
    long currentVersion();

    /** 当前生效的完整快照（只读视图） */
    FlagSnapshot snapshot();

    /** 判定开关 */
    Evaluation evaluate(String flagName, String identifier, Map<String, String> attributes);

    /**
     * 从给定来源加载并整批生效。任何一项非法则整体拒绝，当前配置不受影响。
     *
     * @return 生效后的版本号
     */
    long refresh(List<FlagSource> sources);

    /** 回退到上一次有效版本 */
    void rollback();
}
