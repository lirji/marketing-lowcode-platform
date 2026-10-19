# 11 数据所有权、共享访问与生命周期

## 2026-10-19 更新

jobs SQL 位置已受门禁约束，Journey 查询已落 Mapper XML；裂变 231 项历史依赖保留基线，不能声称全部跨域耦合清零。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 所有权清单（核心表，不冒充全库ACL审计）

|Owner|权威写表/状态|外部读/复制边界|
|---|---|---|
|Control / Campaign|mk_campaign、mk_definition_version、mk_approval_case/decision、mk_terms_snapshot|Compiler经契约取得设计；不允许其他服务直接改草稿|
|Control / Release|mk_release_slot/manifest、mk_runtime_ack、mk_activation_directive|runtime安装/消费副本，不能反写control状态|
|Compiler|mk_compiled_artifact|签名制品按ID获取；不把字节当可变配置|
|Audience|mk_field_definition、mk_segment_definition、mk_audience_snapshot/member|Decision为资格投影，不是定义第二Owner|
|Decision|mk_runtime_slot/generation、mk_decision_command（JDBC模式）|本地运行store；Redis模式幂等另有持久策略|
|Journey runtime store|mk_journey_runtime_slot/generation/activation/kill_switch|Flink resolver直接SQL只读；是需治理的共享runtime schema|
|Flink Journey|ValueState与timer为STREAM执行权威|mk_enrollment是运营投影，不能反向任意更新|
|Journey projection/dispatch|mk_journey_output_receipt、mk_node_effect_intent、mk_journey_dispatch_outbox/position|下游收到业务命令，不能直接写这些表|
|Benefit/Funding|mk_resource_account、mk_promotion_application、mk_reservation_item、账本、mk_award_intent_outbox|对外通过命令、查询、终态/对账合同|
|Engagement|contact、command、回执相关表|渠道提供外部事实，不直接写DB|
|Measurement|事实/归因/聚合投影|指标可更正，不反写交易账本|
|Referral|participant/relation/reward、inbox/current/history、runtime cursor|真实启用仍被门禁阻止|

表名精确DDL索引复用上一阶段12章；新增重点证据：[JdbcJourneyPlanResolver.java:112](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L112)、[JourneyOutputProjector.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L1)、[V1__benefit_funding.sql:1](../../services/benefit-funding-service/src/main/resources/db/migration/V1__benefit_funding.sql#L1)。

## 四种数据层

Configuration是追加设计版本；Release是可审计的安装/激活事实；Runtime是实例、定时器、幂等、命令与业务权威；Audit是解释和责任证据。可同库分表，不必为了四个词拆四个数据库。删除草稿不能级联删活跃制品；清理业务投影不能丢幂等；删除用户数据须覆盖副本/导出/备份保留，期限UNKNOWN需要业务依据。

## A10：现有静态护栏覆盖有边界

- Problem：SQL集中检查只扫描services/platform-web，不含jobs；已存在Journey resolver内联SQL。领域层规则也不是每张表写Owner的证明。
- Evidence：CONFIRMED，[ArchitectureRulesTest.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L39)、[JdbcJourneyPlanResolver.java:113](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L113)。
- Impact：INFERRED，误以为现有ArchUnit全绿就代表runtime数据边界全受控；未来改表可能破坏作业。
- Root Cause：原护栏按Web服务结构设计，作业数据适配没有进入同一覆盖策略。
- Current Workaround：resolver集中而非散落，使用参数绑定且当前只读。
- Recommended Direction：显式为jobs建立read-only store port/适配允许列表，补表Owner和SQL检查覆盖；不强迫Flink为了XML框架引入整个Web持久化栈。
- Trade-off：例外要有Owner/到期复评；粗暴禁止所有jobs SQL会阻断合法读取，却不改善故障语义。
- Priority：P2，T08/T12。

本轮没有确认跨领域直接写 其他领域的表或共享ORM实体；这不是全仓不存在违规的证明。静态检查范围SCOPED，数据库用户实际写权限、动态SQL、反射、跨schema调用仍UNKNOWN。跨表JOIN若只在同Owner runtime表内，不自动算跨域侵入。

数据迁移原则：expand→双读/影子验证→切权威→收缩；每表单Owner和唯一写入口，禁止无协调双写。当前计划不搬库、不执行DDL；只有T04/T05/T06/T07在合同签收后可能增量加字段/投影，需新迁移文件和注释，禁止改旧Flyway历史。

## 继承证据

- [上一阶段 12-data-model](../marketing-platform-analysis/12-data-model.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 11-event-consistency-idempotency](../marketing-platform-analysis/11-event-consistency-idempotency.md)：作为发现输入；本章新增判断以定向源码复核为准。
