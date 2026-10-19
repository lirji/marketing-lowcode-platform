# 09 Metadata、Schema 与 Compiler 治理

## 2026-10-19 更新

原固定版本/清空变量问题已修复，具名端口能保存；通用 Schema 迁移与插件协议仍是候选设计。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 元数据驱动到什么程度

CONFIRMED：节点目录驱动画布palette，NodeDefinition有schema引用、输入输出端口、版本、副作用和cost；Graph是结构化设计模型，不只是随意JSON。然而目录为Java列表，表单/类型适配还有硬编码，schema URI不证明动态解析执行，compiler digest占位，migrators为空。[DefaultNodeRegistry.java:16](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/DefaultNodeRegistry.java#L16)、[NodeDefinition.java:22](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/model/NodeDefinition.java#L22)。

## A09：编辑往返不保留未来版本身份

- Problem：前端 toGraphDefinition 固定 `semanticVersion` 和 `dialectVersion` 为1.0.0，fromGraphDefinition不保留这些到节点编辑数据；variables也重建为空。
- Evidence：CONFIRMED，[graph.ts:35](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L35)、[graph.ts:54](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L54)、[graph.ts:83](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/frontend/apps/console/src/features/designers/graph.ts#L83)。
- Impact：INFERRED，未来V2历史图重新保存可能被降为V1并丢失运行限额变量；当前是否已有V2已发布实例 UNKNOWN。
- Root Cause：画布适配器将初始默认值当作全部保存协议，缺少read-edit-write恒等合同。
- Current Workaround：当前目录主要V1；后端历史版本和已安装制品保护在途执行，但不能保护新保存语义。
- Recommended Direction：T10先保留版本/variables/annotations及未知受控字段，明确layout独立；migration作为显式preview/差异/新版本保存，不静默改历史。
- Trade-off：版本兼容矩阵与历史fixture增加维护；只读打开不等于允许编辑所有旧版本。
- Priority：P1（Schema升级前置），现状产品编辑丢变量需专项确认；不宣称已有V2线上事故。

## Validation Pipeline与Marketing Compiler

```mermaid
flowchart LR
 G[Graph + dialectVersion] --> S[Syntax / config schema]
 S --> T[Type / operator / required routes]
 T --> D[Dependency / permissions / facts]
 D --> N[Normalize supported semantics]
 N --> C[Dialect compiler]
 C --> A[Typed runtime artifact + source map]
 A --> V[ABI / budget / differential validation]
 V --> R[Approval and release binding]
```

建议Marketing Compiler是已有方言编译器的协议和编排，不是所有领域一个巨类或新进程。活动只编排引用；Audience先落实表达式与provider合同，别凭空制造全库编译器。ValidationIssue沿用nodeId/pointer/code，前端提示是体验，服务端是权威；发布再次验证依赖/签名/ABI/资源，不能复用过期“曾校验通过”布尔值。

同源Metadata契约建议包含kind/type/version、configSchema、端口与结果、事实需求、执行器绑定、cost/purity/权限/兼容范围。Node、Operator、Action可共享元数据头，但业务payload各有schema，不能统一成没有约束的Map。前端不能选择任意Java类名执行。

迁移：v1读写先保真→新增v2 reader→旧fixture双读→新writer按租户灰度→制品版本绑定→旧writer退出后再弃用。校验不支持版本时保留原稿并给出明确拒绝，不默认按最新版解释。T10不会改已签名历史制品。

## 继承证据

- [上一阶段 08-low-code-architecture](../marketing-platform-analysis/08-low-code-architecture.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 13-extensibility](../marketing-platform-analysis/13-extensibility.md)：作为发现输入；本章新增判断以定向源码复核为准。
