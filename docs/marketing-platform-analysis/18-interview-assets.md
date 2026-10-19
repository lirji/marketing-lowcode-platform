# 18 高级 Java 面试故事与 STAR 素材

## 2026-10-19 更新

可引用已交付的修复与验证说明技术方案；个人职责、线上收益和规模仍需独立事实，不把代码修复写成生产成功。

当前源码核对基线 `6b9092f`；[完整状态与证据](CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

- 仓库：`marketing-lowcode-platform`；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加当前未提交工作区。
- 分析日期：2026-09-22；模式：`READ_ONLY_ANALYSIS`。
- `CONFIRMED`＝源码/配置/测试直接证据；`INFERRED`＝据证据推断；`UNKNOWN`＝尚缺证据。代码存在不等于已部署或已通过真实链路验收。
- 本章遵循用户提示词；产品代码、数据库、运行配置、Git 提交与推送均不变。

## 使用边界

以下是基于代码的叙述素材。**UNKNOWN**：用户个人负责范围、团队角色、最初方案和线上业绩；不能自动把仓库能力写成“我主导并已上线”。所有“最初方案”均列为对照方案，不伪造历史。性能数字、业务金额、用户量不编造；83/130 测试数只代表本次选定范围。

## 故事 1：设计态到运行态（S）

- 业务背景：运营改配置必须经过治理才能影响优惠。
- 问题：运行直接读草稿会绕过审核，预览/执行语义可能偏离。
- 为什么难：模型、编译字节、签名及安装版本要同时绑定。
- 最初方案：UNKNOWN；对照方案是运行读最新 JSON。
- 当前方案：定义版本→图校验→编译 IR→签名制品→预热/激活。
- 核心技术/代码：[RuleCompilerService.java:141](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/rule-compiler-worker/src/main/java/com/acme/marketing/compiler/application/RuleCompilerService.java#L141)、[RuntimeManifestRegistry.java:69](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/RuntimeManifestRegistry.java#L69)。
- 为什么这样设计：INFERRED，隔离运营修改与在线流量，能定位执行来源。
- 替代方案：解释执行受限 DSL，可减少编译环节但需运行时严格版本绑定。
- Trade-off：合同/版本管理复杂；R01 显示安全签名与语义正确性必须分别验证。
- 面试追问：签名到底证明什么？如何证明仿真与线上一致？节点升级如何兼容旧制品？

## 故事 2：版本回滚不回退指令时序（S）

- 背景/问题：营销误发布需要恢复旧优惠，消息可能重放或乱序。
- 为什么难：generation 下降是合法回滚，不能用它直接判断消息新旧。
- 最初方案：UNKNOWN；对照是修改一个 version 列。
- 当前方案：slot 锁、CAS、更高 activationSequence 指向旧留存 generation、签名 Outbox。
- 核心技术/代码：[ReleaseApplicationService.java:226](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/ReleaseApplicationService.java#L226)。
- 设计理由：INFERRED，用两个版本轴表达“运行什么”和“谁是最新命令”。
- 替代：全局发布协调器；需要更强一致时可以评估，但增加可用性依赖。
- Trade-off：最终收敛而非瞬时切流，需要保留旧制品与观察状态差。
- 追问：旧 ACK 是否仍有效？回滚后旧 Journey 实例怎么办？发券如何补偿？

## 故事 3：旅程延迟任务恢复（S）

- 背景/问题：用户等待后触达，重启不能丢任务或重复动作。
- 为什么难：state/timer/消息/外部渠道跨多个提交边界。
- 最初方案：UNKNOWN；对照是应用内 ScheduledFuture。
- 当前方案：Flink keyed state+processing timer/checkpoint，固定计划版本，命令 ID 确定性，下游 Outbox/receipt。
- 核心技术/代码：[JourneyRuntimeProcessFunction.java:56](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L56)、[JourneyOutputProjector.java:67](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L67)。
- 设计理由：INFERRED，将状态推进与外部动作分离，恢复可重放。
- 替代：数据库 due-task 轮询+领取/fence，运维更简单但扫描/精度/容量需另算。
- Trade-off：流平台运维复杂；没有并行 join，不能吹成全能 BPMN。
- 追问：checkpoint 丢失怎么办？业务幂等保留多久？COMPLETED 是否等于渠道送达？

## 故事 4：优惠组合与分摊守恒（S）

- 背景/问题：多优惠、多商品、多出资方，选择与金额结果要可复现。
- 为什么难：组合搜索和舍入余数，局部最优可能错。
- 最初方案：UNKNOWN；对照为贪心或简单逐规则扣减。
- 当前方案：分阶段定价、FIRST/SUM/BEST/MAX_N、有界精确子集、最大余数与稳定排序。
- 核心技术/代码：[OfferSelector.java:16](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/OfferSelector.java#L16)、[LargestRemainderAllocator.java:39](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/LargestRemainderAllocator.java#L39)。
- 设计理由：INFERRED，选择可审计的有限能力而非无限组合。
- 替代：整数规划/动态规划/近似算法，需约束匹配和误差政策。
- Trade-off：候选数上限；不能宣称已有大促性能数字。
- 追问：为什么 14？分摊剩下一分钱给谁？退款能否沿原分摊反向记账？

## 故事 5：Outbox 与消费者幂等（S）

- 背景/问题：数据库事务成功后 Kafka 故障，或投递成功后回写失败。
- 为什么难：业务数据与 broker 不在同一事务。
- 最初方案：UNKNOWN；对照为事务提交后直接 send。
- 当前方案：业务+outbox 同库事务，重试允许重复，消费坐标/业务键与内容摘要防重。
- 核心技术/代码：[EventIngestionService.java:64](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/event-gateway-service/src/main/java/com/acme/marketing/eventgateway/application/EventIngestionService.java#L64)、[JourneyOutputProjector.java:44](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/journey-service/src/main/java/com/acme/marketing/journeyservice/application/JourneyOutputProjector.java#L44)。
- 设计理由：INFERRED，将丢消息风险转为可恢复重复，而非声称不会重复。
- 替代：CDC/事务消息，需引入额外运维与消费契约。
- Trade-off：消息表增长、积压、清理/重放治理。
- 追问：数据库提交后 offset 未提交？扩分区后同一业务键怎么办？DLT 怎么安全重放？

## 故事 6：未知发奖结果与旧 owner（S）

- 背景/问题：权益中台可能已受理，本地超时并发生租约接管。
- 为什么难：fence 限制本地写，不自动撤销远端成功。
- 最初方案：UNKNOWN；对照为超时后换 requestId 重发。
- 当前方案：稳定 sourceRequestId、leaseVersion、原确认恢复、UNKNOWN/HELD 保留、账本对账。
- 核心技术/代码：[AwardIntentRelayMapper.xml:31](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/resources/mapper/AwardIntentRelayMapper.xml#L31)、[ReferralAwardPreparation.java:117](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/domain/ReferralAwardPreparation.java#L117)。
- 设计理由：INFERRED，将“没有响应”与“业务失败”分离。
- 替代：同步长事务不可覆盖远端提交；全局事务需双方支持且成本大。
- Trade-off：可能需要人工介入；裂变真实闭环尚未接通，必须主动说明。
- 追问：过期 token 能否恢复原确认？取消晚于确认怎么办？哪个系统是终态权威？

## 故事 7：缓存新鲜度与授权边界（A）

- 背景/问题：SKU 列表要快，但暂停后的权益不能继续通过发布门禁。
- 为什么难：缓存读与实时权威状态冲突。
- 最初方案：UNKNOWN；对照为所有路径共用长 TTL。
- 当前方案：tenant/status 的有界 Caffeine 列表，requireActiveSku 绕过缓存；运行人群有版本和过期检查。
- 核心技术/代码：[HttpBenefitSkuCatalog.java:63](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/infrastructure/HttpBenefitSkuCatalog.java#L63)。
- 设计理由：INFERRED，按决策风险选择新鲜度。
- 替代：可靠失效通知/版本校验，需处理丢通知与乱序。
- Trade-off：发布依赖实时目录，且全页扫描有成本。
- 追问：绕过缓存后目录崩溃？L1 全实例怎么失效？tenant key 与前端缓存一致吗？

## 故事 8：可更正的营销衡量（A）

- 背景/问题：退款、迟到、更正不能简单追加正向收益。
- 为什么难：重复/乱序更正和原事实身份必须稳定。
- 最初方案：UNKNOWN；对照为按事件累加报表。
- 当前方案：payload hash、correction root/current、先反转旧贡献再应用新值，水位表达完整度。
- 核心技术/代码：[MeasurementService.java:114](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/measurement-service/src/main/java/com/acme/marketing/measurement/application/MeasurementService.java#L114)。
- 设计理由：INFERRED，保留事实与投影可重建关系。
- 替代：离线重算全部窗口，简单但延迟与成本高。
- Trade-off：事实链和投影协议更复杂；未验证规模性能。
- 追问：两个更正同时指向旧事实？曝光为什么不等于分桶？ROI 水位落后如何展示？

## 三条 STAR 简历素材（个人归属待确认）

1. **场景**：运营需要从配置到发布可追溯；**挑战**：草稿修改与运行版本容易混用；**设计**：定义、制品、代次分层；**实现**：图校验、签名闭包、ACK 与单调激活；**结果**：代码形成可追溯的安装/切流/回滚机制。可改写为：“参与营销规则发布体系建设，覆盖定义版本、审核、制品编译与运行切换，落地签名校验、预热 ACK 和代次回滚。”不得隐去现存语义缺陷。
2. **场景**：营销旅程跨事件与延迟执行；**挑战**：重启和重复消息可能重发动作；**设计**：固定计划与状态推进/副作用分离；**实现**：keyed timer、checkpoint、命令幂等与持久分发；**结果**：形成可恢复的运行机制，真实环境恢复验收待补。可改写为：“参与有状态营销旅程运行时建设，覆盖事件推进、等待唤醒与触达命令分发，落地版本固定、计时器恢复和副作用幂等边界。”
3. **场景**：多优惠与资源结算；**挑战**：组合冲突、舍入和并发；**设计**：有界选择、精确分摊、预占账本；**实现**：整数余数分配、版本/fence、唯一键与对账；**结果**：代码提供金额守恒与命令回放能力。可改写为：“参与优惠定价与资金权益链路建设，覆盖优惠选择、商品行及出资方分摊、预占结算和退款，落地确定性计算与账本校验。”

五个技术难点与五个架构亮点见 [17章](17-core-technical-challenges.md)；十个分层追问见 [19章](19-interview-questions.md)。未提供个人负责范围前，不使用“主导团队”“实现千万 QPS”等履历措辞。
