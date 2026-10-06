# runtime-feature-flags-java

运行时的特性开关与动态配置能力：开关取值、灰度比例、定向规则的更新**不重启服务即可生效**，
更新**整批原子生效**、失败可拒绝、可回滚，每次判定的结果**一致且可解释**。

## 配置来源与优先级

所有配置来源实现 `FlagSource` 接口，通过 `priority()` 声明优先级（数值越大优先级越高）：

| 来源 | 实现 | 默认优先级 | 说明 |
| --- | --- | --- | --- |
| 本地 JSON 文件 | `JsonFileFlagSource` | 10 | 由 `flags.file` 指定路径，服务启动/刷新时读取 |
| 运行时推送 | `InMemoryFlagSource`（REST `/flags/push`） | 20 | 通过 REST 推送，优先级高于文件 |

合并规则（`FlagConfigMerger`）：

1. 每个来源先独立加载并逐项校验（`FlagValidator`），**任何一项非法，整次更新被拒绝**；
2. 同名开关出现在不同来源时，**优先级高的来源生效**，被覆盖的一方会显式打日志，不做静默覆盖；
3. 同名开关出现在**优先级相同**的来源且定义不一致时，视为冲突，整次更新被拒绝
   （错误类型 `DUPLICATE_CONFLICT`）；定义完全一致则去重保留一份；
4. 来源列表为空也会被拒绝——不允许用空配置兜底。

### 非法配置的拒绝与错误分类

所有拒绝都抛出 `FlagConfigException`，携带可区分的 `FlagErrorType`：

| 错误类型 | 触发场景 |
| --- | --- |
| `INVALID_VALUE` | 灰度比例超出 0-100、名称为空、规则取值为空、键与定义名不一致、EQUALS 规则多值等 |
| `TYPE_MISMATCH` | JSON 中字段类型不符（如比例写成字符串、rules 不是数组） |
| `DUPLICATE_CONFLICT` | 同优先级来源定义冲突、同一开关内对同一属性的重复规则、文件内重复定义 |
| `SOURCE_LOAD_FAILED` | 来源加载抛异常或返回 null |
| `NO_ROLLBACK_VERSION` | 没有历史版本时调用回滚 |

## 灰度与定向规则的判定逻辑

判定入口：`FeatureFlagService.evaluate(flagName, identifier, attributes)`，
返回 `Evaluation(flagName, value, reason, bucket, matchedRule, configVersion)`，判定原因完全可解释。

判定顺序（命中即返回）：

1. **开关不存在** → `FLAG_NOT_FOUND`，返回 `false`；
2. **开关未启用** → `FLAG_DISABLED`，返回 `false`；
3. **定向规则**：按定义顺序逐条匹配（靠前优先级高），上下文属性满足规则 → `RULE_HIT`，
   返回 `true`，`matchedRule` 记录命中的规则。支持操作符：
   - `EQUALS`：属性值等于唯一指定值；
   - `IN`：属性值在取值集合中；
4. **灰度分桶**：规则均未命中时，计算 `bucket = SHA-256("开关名:标识") 的前 8 字节 mod 100`，
   `bucket < rolloutPercentage` → `ROLLOUT_HIT`，否则 `ROLLOUT_MISS`。

**一致性保证**：分桶只依赖 `(开关名, 标识)` 与配置内容，同一版本快照下同标识的判定结果必然一致；
调整开关或比例后，新版本快照立即对后续判定生效（`configVersion` 字段可验证判定所用版本）。

## 原子更新与回滚

- 当前配置保存在 `AtomicReference<FlagSnapshot>` 中。更新时先在写锁内完成
  **加载 → 校验 → 合并**，全部通过后一次引用切换整批生效；读方无锁，永远看到完整的某一版快照，
  不会看到"改了一半"的中间状态；
- 校验或合并失败直接抛异常，引用不被替换，**当前生效配置不受影响，版本号不前进**；
- 每次成功更新把旧快照压入历史栈（保留最近 16 版），`rollback()` 以**单调递增的新版本号**
  重新发布上一版内容，便于审计与解释。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/flags/{flag}/evaluate?id={标识}&{属性}={值}` | 判定开关，返回 value/reason/bucket/matchedRule/configVersion |
| POST | `/flags/push` | 推送一批开关定义（JSON body: `{name: FlagDefinition}`）并整批生效 |
| POST | `/flags/refresh` | 从所有来源重新加载并整批生效；非法时返回 422 与错误类型 |
| POST | `/flags/rollback` | 回退到上一次有效版本 |
| GET | `/flags` | 查看当前版本与全部生效定义 |

## 本地验证

### 运行单元测试

```bash
mvn test
```

测试日志会打印每个用例覆盖的业务场景与通过情况，覆盖四类场景：

- `FlagConfigMergerTest`：多来源合并、优先级覆盖、非法取值/类型/冲突被拒绝；
- `JsonFileFlagSourceTest`：JSON 来源加载、类型不匹配拒绝、文件内重复定义拒绝；
- `FlagEvaluationTest`：分桶稳定性（同标识 1000 次判定一致）、30% 灰度命中分布、
  边界（0%/100%/bucket==比例）、定向规则命中与优先级、判定原因可解释、调整后立即生效；
- `FeatureFlagServiceConcurrencyTest`：4 写线程 × 8 读线程下的整批原子性（撕裂读为 0）、
  非法更新不影响当前配置、回滚恢复上一有效版本。

### 启动服务并手工验证

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--flags.file=config/flags.json --server.port=8080"

# 触发一次加载
curl -X POST localhost:8080/flags/refresh

# 判定：命中定向规则（group=beta）
curl "localhost:8080/flags/new-checkout/evaluate?id=user-1&group=beta"

# 判定：走灰度分桶（同一 id 多次请求结果一致）
curl "localhost:8080/flags/new-checkout/evaluate?id=user-12345"

# 推送新配置（比例调到 100%，立即生效）
curl -X POST localhost:8080/flags/push -H 'Content-Type: application/json' -d '{
  "new-checkout": {"name":"new-checkout","enabled":true,"rolloutPercentage":100,"rules":[]}
}'

# 推送非法配置（比例 150，被拒绝且当前配置不变）
curl -X POST localhost:8080/flags/push -H 'Content-Type: application/json' -d '{
  "new-checkout": {"name":"new-checkout","enabled":true,"rolloutPercentage":150,"rules":[]}
}'

# 回滚到上一有效版本
curl -X POST localhost:8080/flags/rollback
```
