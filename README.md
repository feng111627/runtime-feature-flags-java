# runtime-feature-flags-java

运行时特性开关与动态配置能力：开关取值、灰度比例、定向规则均可在**不重启服务**的前提下更新并立即生效，每次判定结果一致、可解释。

## 核心概念

- **FlagDefinition**：一个开关的定义，包含 `name`（开关名）、`enabled`（是否启用）、`rolloutPercent`（灰度比例 0-100）、`targetedUserIds`（定向用户标识集合）。
- **FlagSource**：配置来源接口，多个来源按 `priority()`（数值越大优先级越高）合并。
- **FlagSnapshot**：一份不可变的生效配置快照，版本号单调递增；读取方一次拿到整份快照。
- **EvaluationResult**：判定结果，包含是否命中、命中原因（`EvaluationReason`）与配置版本号。

## 配置来源与优先级

实现 `FlagSource` 接口即可接入新来源（内置 `InMemoryFlagSource`，支持 `replace()` 动态替换）：

```java
public interface FlagSource {
    String name();          // 来源名，用于错误定位
    int priority();         // 优先级，数值越大越高
    Map<String, FlagDefinition> load();
}
```

合并规则（`FlagConfigMerger`）：

1. 每个定义先经 `FlagConfigValidator` 校验，**任何一项非法即整体拒绝本次更新**；
2. 同一开关在多个来源出现且**内容完全一致**时，取高优先级来源的定义；
3. 同一开关**重复定义且内容冲突**时，拒绝合并，绝不静默覆盖或用默认值兜底。

拒绝原因通过 `FlagConfigException.errorType()` 区分：

| 错误类型 | 含义 |
| --- | --- |
| `INVALID_VALUE` | 取值非法：灰度比例越界（非 0-100）、开关名为空、定向标识为空、key 与定义内名称不一致 |
| `TYPE_MISMATCH` | 类型不匹配：来源返回 null 的定义集合或 null 定义 |
| `CONFLICTING_DEFINITION` | 同一开关在多个来源重复定义且内容冲突 |

## 灰度与定向规则的判定逻辑

判定入口：`FeatureFlagService.evaluate(flagName, userId)`，按以下优先级依次判定，命中即返回：

1. **FLAG_NOT_FOUND**：开关不存在 → 不命中；
2. **FLAG_DISABLED**：`enabled=false` → 不命中（优先级最高，定向与灰度均不生效）；
3. **TARGETED_USER**：`userId` 在 `targetedUserIds` 中 → 命中（定向优先于灰度）；
4. **ROLLOUT_HIT / ROLLOUT_MISS**：对 `(flagName + ":" + userId)` 做 SHA-256 哈希，取前 8 字节映射到 0-99 的桶，桶号 `< rolloutPercent` 则命中。

**一致性保证**：分桶只依赖开关名与用户标识的哈希，与进程、时间无关——同一标识在规则不变时每次判定结果必然一致；调整开关或比例后，新配置原子发布，立刻对后续判定生效。每次判定返回的 `reason` 与 `configVersion` 可完整解释命中原因及所依据的配置版本。

## 并发与原子性

- 写方（`reload()`）在锁内完成全部校验与合并，再通过 `AtomicReference` **一次性原子替换**整份不可变快照；读方只读引用，永远看到完整的旧版本或完整的新版本，不会看到改了一半的中间状态。
- 校验或合并失败时异常在替换引用之前抛出，**当前生效配置不受影响**。
- `rollback()` 回退到上一次有效版本；回退本身也是一次发布，版本号单调递增。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/flags/{flagName}/evaluate?userId=xxx` | 判定开关是否命中 |
| POST | `/api/flags/reload` | 从所有来源重新加载并原子发布 |
| POST | `/api/flags/rollback` | 回退到上一次有效版本 |
| GET | `/api/flags/snapshot` | 查看当前生效快照 |

## 本地验证

```bash
# 运行全部单测（日志会打印每个用例覆盖的业务点与通过情况）
mvn test

# 启动服务
mvn spring-boot:run

# 判定开关
curl "http://localhost:8080/api/flags/flag-a/evaluate?userId=user-42"

# 查看当前快照 / 重新加载 / 回退
curl http://localhost:8080/api/flags/snapshot
curl -X POST http://localhost:8080/api/flags/reload
curl -X POST http://localhost:8080/api/flags/rollback
```

单测覆盖三类核心场景：

- `FlagConfigMergerTest`：多来源合并、优先级、非法/冲突配置被拒绝（区分错误类型）；
- `FeatureFlagEvaluationTest`：分桶稳定性、灰度 0%/50%/100% 命中、定向规则、命中原因可解释、更新立即生效；
- `ConcurrentUpdateAtomicityTest`：并发读写下的整批原子性、失败更新不影响当前配置、回退到上一有效版本。
