# 低代码语言、规则制品与运行时

## 为什么不是万能画布

平台共享类型系统、版本信封、节点注册表、画布交互和编译协议，但提供五种有边界的语言。这样运营获得低代码体验，运行时仍能做静态检查、成本预算和安全隔离。

| Dialect | 允许语义 | 禁止语义 | Runtime |
| --- | --- | --- | --- |
| `OFFER_DECISION_DAG` | 固定/比例候选、内建 scope、组合/互斥、Terms；无边策略集合或 start → 候选 → end 单链 | 任意事实条件节点、分支、孤立节点、环、Wait、Send | 同步纯函数 Decision |
| `AUDIENCE_EXPRESSION` | typed predicate、集合运算、as-of/freshness | 任意 SQL、未注册 PII、外部网络 | batch + Flink incremental |
| `JOURNEY_STATE_MACHINE` | trigger、branch、wait、bounded repeat、Send/Grant/Webhook | parallel/join、直接修改价格、无界循环、未声明副作用 | Flink keyed state/timer |
| `DMN_DECISION_TABLE` | typed input/output、hit policy、gap/overlap | 动态类加载、网络/文件 I/O | KIE DMN adapter |
| `BENEFIT_FUNDING_FORM` | resource、bucket、funding/return policy | 任意脚本或跨账本更新 | Benefit application service |

## Definition envelope

图契约在 `marketing-contracts/.../graph-definition.schema.json`。每份定义携带 tenant、dialect、definitionId/version、schemaVersion、registryVersion、nodes/edges、terms 与 canonical content digest。保存草稿可变；提交时生成不可变 DefinitionVersion。

NodeDefinition 必须声明：

- `type@semanticVersion` 与兼容的 ABI range；
- 输入/输出端口和 JSON type；
- required/missing/null semantics；
- 估算 CPU、内存、lookup 和 fan-out cost；
- side effect 分类（NONE、CONTACT、BENEFIT、WEBHOOK）；
- 可用 dialect、权限、敏感数据目的和 migrator。

客户端校验只改善体验，服务端 `GraphValidator` 才是权威门禁。canonical hash 对 map key、节点和边排序，不能依赖前端坐标或 JSON 属性顺序。

当前优惠运行时 `marketing-offer-policy/1` 不包含任意事实谓词。`OfferGraphInputGuard` 在校验、提交、编译、试算和发布入口拒绝 `offer.condition` 等不支持的图，不能把条件丢掉后发布。比例规范字段为 `discountBasisPoints`，兼容 `basisPoints`；两者同时出现时必须相等，范围 0–10000。金额试算和编译共用候选构建及 `PricingEngine`，覆盖门槛、封顶和组合选择；试算不证明生效时间、库存或领取资格。商品/渠道/人群/行范围或运费策略需要完整上下文，当前总金额 API 返回 `OFFER_PREVIEW_CONTEXT_REQUIRED`。

Journey 编译要求唯一触发节点、全部节点可达、合法且不重复的路由。condition 必须有 true/false，wait-event 必须有 matched，wait-timer 必须有 elapsed，repeat 必须有 repeat/exit；普通执行节点使用 next，end 无出口。画布以具名端口接线，并保留导入图的节点 ID、语义版本、方言版本、variables 和 annotations。

Audience 已注册字段的 DECIMAL/INSTANT 支持 EQ/NE/IN 与有序比较；STRING/BOOLEAN 只支持 EQ/NE/IN。保存和预览都检查字面量及算子组合；日期按时间点比较，数值按数值比较。缺失或非法事实不授予成员资格，条件 JSON 不变。

## 编译管线

```mermaid
flowchart LR
  D[Immutable DefinitionVersion] --> S[Schema/type validation]
  S --> G[Dialect graph validation]
  G --> P[Policy/PII/funding gates]
  P --> C[Cost and complexity budget]
  C --> B[Sandboxed compiler]
  B --> T[Golden/static analysis]
  T --> A[Signed ArtifactBundle]
```

Compiler 输入只允许已登记 schema 和 vendored dependency，不允许解析远程 `$ref`。生产 worker 应使用独立 namespace/runtimeClass，禁网、只读 rootfs、临时目录配额、CPU/内存/PID/时限和 seccomp；编译结果只通过 content-addressed ArtifactStore 输出。代码中的 compiler/service adapter 展示完整协议，本地默认内存存储；生产必须接 S3/KMS adapter。

ArtifactBundle 包含 dialect、engine/ABI、definition digest、registry snapshot、compiled payload、golden results、build metadata、SBOM reference 和签名。Runtime 验证签名、digest、tenant、engine/ABI 和 generation 后才加载；验签失败不允许降级到未签名内容。

## Drools 与 DMN 的使用边界

Drools executable model 只做受控条件匹配和候选推理；Money 计算、组合优化、分摊和资金改变保留在可 property-test 的纯 Java 代码。DMN 用于适合表格表达的 eligibility/classification，发布前检查类型、hit policy、gap/overlap 和测试行。DRL/DMN 不能调用网络、反射、系统时间或随机数；时间和随机桶必须作为显式 facts 输入。

## 兼容与迁移

平台支持当前 N 和 N-1 DSL/Artifact ABI：

1. 注册新节点版本，但不改旧版本语义。
2. 提供纯函数 migrator，输入旧 JSON，输出新 JSON + migration report。
3. 对存量定义 dry-run，比较 canonical semantics/golden corpus。
4. 用户确认后创建新的 Draft version；Published version 不原地覆盖。
5. 只有无旧定义、运行实例和 rollback generation 后才移除旧 runtime。

无 migrator 的 breaking change 必须换 major type/version 并保持旧实现。Journey migration 还必须检查当前节点映射、等待 deadline、并行 token 和已执行副作用；失败实例继续固定旧 version。

2026-10 正确性修复保持现有 Offer payload ABI 和签名字段不变，新优惠制品 ID 为 `offer_policy-v2-<digest>`，ID 本身受编译器签名保护。仅含旧 `basisPoints` 的百分比图，旧编译器可能生成零比例；其旧制品在 stage、ACK、activate、rollback 时被 `OFFER_RECOMPILE_REQUIRED` 拒绝，必须重新编译。不会原地重签、覆盖或自动停用当前线上制品；运维应盘点此类活跃版本，按正常审批/发布流程迁移，回滚目标也要满足新护栏。

## 插件开发约束

扩展点是 schema、compiler adapter 或 provider connector，不允许上传任意 jar 后直接进入生产 JVM。插件必须具备源码审查、签名、SBOM、许可证/漏洞扫描、ABI compatibility test、恶意样例、资源配额和 canary。新增副作用节点必须定义业务幂等键、timeout/UNKNOWN 语义、retry classification、compensation 和审计字段。

## 测试层次

- 示例测试：每个节点/模板最小成功与失败样例。
- Property/metamorphic：金额守恒、顺序无关、稳定 hash、重复事件不重复副作用。
- Golden corpus：definition → artifact → evaluation 字节级快照。
- Fuzz/negative：环、炸弹图、深嵌套、无效 schema ref、NaN/溢出、未知节点、伪造签名。
- Differential/shadow：新旧 runtime 对同一录制事实比较，差异需分类并批准。
