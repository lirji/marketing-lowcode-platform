# 20 最终架构图册

## 2026-10-19 更新

服务拓扑总体保留；当前人群读取应理解为主键回源加有界缓存，图中的旧轮询实现以最新索引为准。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 读图规则

图中的实线表示本仓库确认的结构/路径，示意活动不等于已部署活动。跨服务关系是逻辑引用，不全是数据库 FK。控制提交与运行生效有最终一致性窗口。判断依据见每张图对应章节；不能从箭头推断全链路已验收。

## 系统架构

来源：[01-project-landscape.md](01-project-landscape.md)。

```mermaid
flowchart TD
 R[Repository] --> S[services: 11 deployables]
 R --> SPI[runtime-spi: 可移植模型与执行器]
 R --> J[jobs: 3 Flink pipelines + support]
 R --> F[frontend: console + contracts]
 R --> C[common / web / contracts / architecture-tests]
 S --> CT[interfaces: HTTP / 消费适配]
 CT --> AP[application: 用例和事务]
 AP --> DM[domain / runtime-spi: 规则与状态]
 AP --> P[Repository / Port]
 P --> I[infrastructure: MyBatis / Kafka / HTTP]
 I --> DB[各上下文 MySQL]
```

## 业务能力

来源：[02-business-architecture.md](02-business-architecture.md)。

```mermaid
flowchart TD
 M[营销平台] --> A[活动目标与范围]
 M --> R[优惠与规则]
 M --> U[人群与成员]
 M --> J[旅程与触达]
 M --> B[权益及资金]
 M --> P[审核 / 发布 / 回滚]
 M --> D[实验 / 归因 / 追踪]
 M --> G[身份 / 租户 / 审计 / 运行治理]
```

## Activity Lifecycle（领域方法与接线边界）

来源：[04-activity-engine.md](04-activity-engine.md)。

```mermaid
stateDiagram-v2
 [*] --> DRAFT: createCampaign 持久化
 DRAFT --> IN_REVIEW: Campaign.submit 领域方法
 IN_REVIEW --> APPROVED: Campaign.approve 领域方法
 APPROVED --> ACTIVE: Campaign.activate 领域方法
 ACTIVE --> PAUSED: Campaign.pause 领域方法
 note right of DRAFT
   其余迁移未确认接入活动持久化 API
   不能把领域方法图当端到端运行图
 end note
```

## Definition 与 Release 生命周期

来源：[04-activity-engine.md](04-activity-engine.md)。

```mermaid
stateDiagram-v2
 [*] --> DRAFT: saveDefinition
 DRAFT --> VALIDATED: validate
 VALIDATED --> IN_REVIEW: submit
 IN_REVIEW --> APPROVED: 满足审核角色
 IN_REVIEW --> DRAFT: reject
 APPROVED --> STAGED: Release 子流程
 STAGED --> ACTIVE: ACK 与 activate
```

## Rule Engine

来源：[05-rule-engine.md](05-rule-engine.md)。

```mermaid
flowchart LR
 G[Graph JSON] --> V[控制面校验]
 V --> C[OFFER_POLICY 编译]
 C --> P[OfferPolicyArtifact]
 P --> S[签名与发布]
 S --> W[运行时验签并预热]
 W --> R[过滤资格 / 人群 / 时间]
 R --> E[分阶段 PricingEngine]
 E --> T[分摊结果与 OfferToken]
 G --> SIM[GraphSimulationService 图遍历]
 SIM -.当前不是同一执行器.-> E
```

## Audience Pipeline

来源：[06-audience-engine.md](06-audience-engine.md)。

```mermaid
flowchart TD
 F[字段目录] --> R[SegmentRule 定义版本]
 R --> P[请求样本 preview]
 P --> S[提交成员 createSnapshot]
 S --> DB[快照及成员表]
 U[上游已计算 segmentIds] --> E[PROFILE_CHANGED]
 E --> K[Kafka profile change]
 K --> D[Flink 新旧集合差]
 D --> M[Membership delta]
 M --> DP[Decision MySQL + 本地投影]
 DP --> Q[Offer 人群资格判断]
```

## Journey Graph（单路径分支）

来源：[07-journey-engine.md](07-journey-engine.md)。

```mermaid
flowchart LR
 T[journey.trigger] --> C[journey.condition]
 C -->|true| G[journey.grant]
 C -->|false| W[journey.wait-timer]
 W -->|elapsed| S[journey.send]
 G --> E[journey.end]
 S --> E
```

## 低代码设计到运行

来源：[08-low-code-architecture.md](08-low-code-architecture.md)。

```mermaid
flowchart LR
 U[React Flow 画布] --> G[toGraphDefinition]
 G --> J[JSON GraphDefinition]
 J --> V[节点注册 / 端口 / 环 / 治理校验]
 V --> D[定义版本 + semanticHash]
 D --> C[方言编译器]
 C --> P[运行 IR + ABI + 签名]
 P --> R[验签安装与执行]
```

## Publish Pipeline

来源：[09-publish-version-rollback.md](09-publish-version-rollback.md)。

```mermaid
flowchart TD
 D[定义 DRAFT] --> V[结构与治理校验]
 V --> A[多角色审批与条款快照]
 A --> C[签名制品]
 C --> S[stage: 锁 slot 分配 generation]
 S --> M[STAGED ReleaseManifest]
 M --> W[各 runtime warm 并验签]
 W --> ACK[签名 ACK: ABI / 闭包 / 时间 / 容量]
 ACK --> X[activate: 锁 + CAS]
 X --> O[同事务 activation directive + Outbox]
 O --> K[Kafka 通知]
 K --> R[运行时持久化与本地切换]
 R --> RC[定期 reconcile]
```

## Rollback Pipeline

来源：[09-publish-version-rollback.md](09-publish-version-rollback.md)。

```mermaid
flowchart LR
 C[当前 ACTIVE manifest] --> V[检查留存目标且曾 ACTIVE / 未过期]
 V --> L[锁定当前 slot]
 L --> D[新 activationSequence 指向旧 generation]
 D --> T[CAS + 指令 + Outbox 同事务]
 T --> R[运行时验签切换]
 R --> N[新请求采用旧制品]
 R -.不自动撤销.-> B[已经发生的资金 / 短信 / 奖励]
```

## Runtime Architecture

来源：[10-runtime-architecture.md](10-runtime-architecture.md)。

```mermaid
flowchart TB
 subgraph CP[Control Plane]
 UI[Console] --> C[定义 / 审批 / Release]
 C --> COMP[Compiler + artifact store]
 end
 subgraph RP[Runtime Plane]
 D[Decision: installed OfferPolicy]
 F[Flink: pinned JourneyPlan + ValueState]
 J[Journey projection + dispatch]
 E[Engagement]
 B[Benefit / Funding]
 end
 COMP -->|签名安装| D
 COMP -->|签名安装| F
 C -->|激活指令| D
 C -->|激活指令| F
 F --> J
 J --> E
 J --> B
```

## Event Flow

来源：[11-event-consistency-idempotency.md](11-event-consistency-idempotency.md)。

```mermaid
flowchart LR
 API[受信事件接入] --> DB[receipt / quarantine / outbox]
 DB --> P[PROFILE_CHANGED]
 DB --> J[JOURNEY_SIGNAL]
 DB --> F[MARKETING_FACT]
 P --> A[Flink membership 差分]
 A --> D[Decision projection]
 J --> JR[Flink Journey]
 JR --> JO[Journey output projector]
 JO --> O[dispatch outbox]
 O --> E[engagement.command]
 O --> B[benefit.command]
 F --> MF[Flink measurement]
 MF --> MP[measurement projection]
 C[Control activate / kill] --> CO[control outbox]
 CO --> R[各 Runtime 消费与 reconcile]
```

## 核心数据模型

来源：[12-data-model.md](12-data-model.md)。

```mermaid
erDiagram
 CAMPAIGN ||--o{ DEFINITION_VERSION : owns
 DEFINITION_VERSION ||--o{ APPROVAL_CASE : reviewed_by
 APPROVAL_CASE ||--o{ APPROVAL_DECISION : has
 DEFINITION_VERSION ||--o{ COMPILED_ARTIFACT : compiles
 RELEASE_MANIFEST }o--o{ COMPILED_ARTIFACT : references
 RELEASE_SLOT ||--o{ RELEASE_MANIFEST : versions
 RELEASE_MANIFEST ||--o{ RUNTIME_ACK : warmed_by
 RELEASE_SLOT ||--o{ ACTIVATION_DIRECTIVE : advances
 SEGMENT_DEFINITION ||--o{ AUDIENCE_SNAPSHOT : snapshots
 AUDIENCE_SNAPSHOT ||--o{ AUDIENCE_MEMBER : contains
 JOURNEY_DEFINITION ||--o{ ENROLLMENT : pins
 ENROLLMENT ||--o{ NODE_EFFECT_INTENT : emits
 NODE_EFFECT_INTENT ||--o| DISPATCH_OUTBOX : delivers
 RESOURCE_ACCOUNT ||--o{ RESERVATION_ITEM : reserves
 PROMOTION_APPLICATION ||--o{ RESERVATION_ITEM : contains
```

## Journey Runtime

```mermaid
flowchart TD
 E[START / EVENT] --> K[tenant + enrollment keyed state]
 K --> P[核验固定版本计划]
 P --> A[JourneyRuntime.advance]
 A --> S[新 Snapshot]
 A --> T[一个 active processing-time timer]
 T --> W[onTimer / 有界重试]
 W --> A
 S --> CP[checkpoint + Kafka 事务输出]
 CP --> DB[MySQL 投影 receipt]
 DB --> O[dispatch outbox]
 O --> EF[触达 / 权益消费者]
```

证据：[JourneyJobReducer.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyJobReducer.java#L1)、[JourneyRuntimeProcessFunction.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L1)、[JourneyOutputProjector.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L1)。

## 更准确的核心模型

```mermaid
flowchart TD
 C[Campaign 组织目标与范围] --> D[DefinitionVersion 按方言版本化]
 D --> O[OfferPolicy]
 D --> J[JourneyPlan]
 D --> R[ReferralPlan 内部切片]
 AU[Audience 定义与成员] --> O
 D --> ART[签名 Artifact]
 ART --> REL[按 runtime slot 的 ReleaseManifest]
 REL --> ACT[ActivationDirective]
 ACT --> OD[在线报价]
 ACT --> JR[旅程新实例]
 J --> JR
 JR --> EV[状态与命令事件]
 EV --> B[Benefit / Engagement]
 OD --> TOK[OfferToken]
 TOK --> F[预占 / 结算 / 账本]
 B --> M[衡量与诊断]
 F --> M
```

这比“所有 Activity/Rule/Audience 必经 Journey 才运行”的统一漏斗更准确：在线报价与旅程是不同运行路径，发布也按 runtime slot 分离。Referral 真实开放仍被门禁阻止。
