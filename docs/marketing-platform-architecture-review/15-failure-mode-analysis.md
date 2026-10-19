# 15 故障模式与验证计划

## 2026-10-19 更新

路由和基础设施异常处理已有代码/测试证据；本章故障注入矩阵尚未全部执行，历史容器恢复记录不能覆盖真实业务效果。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 分段保证，不宣称整链天然 exactly-once

下表Current Protection为CONFIRMED源码机制；Impact和Recommended为INFERRED；真实故障演练均未在本轮执行。隔离环境、测试数据、外部sandbox与恢复权限是运行前置；演练不得覆盖共享/生产数据。

|Failure|Impact|Current Protection|Gap|Recommended Protection / 验收|
|---|---|---|---|---|
|MQ重复消息|重复推进/副作用|receipt、signalId、command唯一/hash|外部幂等合同待证|提交后ack前崩溃重放，业务效果一次，异内容冲突|
|MQ消息丢失/保留到期|实例或投影不推进|Outbox、事务sink/checkpoint|超出保留窗口不能凭空恢复|序列/收据对账；从权威outbox/源事件补发，找不到标UNKNOWN|
|Kafka不可用|outbox堆积、checkpoint失败|持久Outbox及客户端失败|磁盘/积压SLO未知|入站背压和容量阈值；恢复按原ID续发|
|Redis不可用|REDIS幂等报价失败/回放能力风险|Lua原子claim，JDBC为另一模式|不是可透明fallback缓存|故障拒绝/受控降级策略，跨存储切换先迁移幂等权威|
|DB慢查询|Flink阻塞、服务连接耗尽|局部限额与本地缓存|resolver同步连接/查询预算需验证|慢查询注入，隔离无关租户，观察积压/超时|
|MySQL主切|事务未知结果/短时读失败|事务/唯一键/hash/fence|实际主切恢复时间未知|提交响应丢失后同键查原结果，不能当未提交|
|Rule engine异常|报价失败或资源耗尽|候选限额、adapter预算/fireLimit|硬中断/沙箱未证明|异常/超预算fixture，拒绝而不回成功空优惠|
|Journey node失败|本次推进不提交或DLQ|pure transition、maxSteps、错误处理|缺路由未发布前拦截；恢复产品不足|先T03；验证相同signal修复后受控重放|
|第三方券接口超时|成功与否未知|稳定sourceRequestId、claim/fence、重试|真实查询/撤销语义未验收|模拟成功后丢响应，原身份查询，不重复发/误释放|
|发布过程服务重启|已提交期望但未通知/应用|slot CAS+Outbox、runtime reconcile|跨副本观察窗口未量测|分别在提交前/后杀进程，序号不倒退，旧消息不能覆盖|
|Delay运行节点重启|到期延迟/重放|Flink state/timer checkpoint|当前恢复/serializer兼容未演练|跨due时间停机恢复，核对command身份与终态|
|Runtime扩容|冷启动DB冲击、重平衡|有版本store与状态托管|Kafka输出分区改变被projector拒绝|分别测试副本扩容、Flink恢复扩容、topic迁移；禁止混用流程|
|版本缓存不一致|短暂混合generation|sequence、验签、过期检查/reconcile|最大收敛目标UNKNOWN|断通知但保留DB，观测skew；过期拒绝；无冷制品不切|
|单实例宕机|在途请求未知、任务接管|持久收据/状态和租约|实际HA副本部署未知|断进程与接管者fence测试，不用健康UP代替业务验收|
|多实例同时恢复|全量读/重试冲击|有界缓存/批次部分保护|人群热载入无总容量上限|限启动并发、渐进预热，DB峰值/租户公平性验收|
|长时间积压|消息过期、业务deadline错过|DLT/DLQ与重试|保留/重放/幂等窗关系未知|超过窗口时人工审计处理，禁止无限requeue|
|kill开启/状态库不可用|暂停或停止推进|resolver1秒新鲜度；timer5秒尝试|普通事件与timer异常路线不同|分别验证paused、SQL失败、TTL到期，不假设一致|
|配置中心不可用|本仓没有已确认Nacos/Apollo热路径|启动配置/已安装制品|远程配置中心依赖不适用|测试实际runtime store/信任配置可用性，不编造依赖|

源码：[FlinkKafkaJobRunner.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/flink-job-support/src/main/java/com/acme/marketing/jobs/support/FlinkKafkaJobRunner.java#L1)、[JourneyRuntimeProcessFunction.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L48)、[JourneyOutputProjector.java:33](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L33)、[DecisionRequestExecutor.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/application/DecisionRequestExecutor.java#L1)、[AwardIntentRelayMapper.xml:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/resources/mapper/AwardIntentRelayMapper.xml#L1)。

## 恢复责任与不可逆点

Journey Owner恢复实例状态，Engagement/Benefit Owner恢复副作用，Release Owner切流；三个恢复动作不能互相替代。恢复后先核对invariant与外部未知结果，再开放重试；短信已送出、资金已消费都是不可凭代码回退撤销的效果。保留操作人、原ID、scope、截止时间和结果证据。计费/资金差额无法解释时暂停相关业务，而非继续重放“碰运气”。

## 记录模板

每次记录 `caseId / source HEAD / runtime config digest / dataset / fault point / start / recover / expected / observed / invariant / evidence / residual gap`。RTO用故障至业务可用实测，RPO用恢复后丢失的已确认业务点实测；未演练不填0。

## 继承证据

- [上一阶段 11-event-consistency-idempotency](../marketing-platform-analysis/11-event-consistency-idempotency.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 14-observability-recovery](../marketing-platform-analysis/14-observability-recovery.md)：作为发现输入；本章新增判断以定向源码复核为准。
