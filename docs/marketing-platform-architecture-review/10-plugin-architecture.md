# 10 插件候选与扩展成本

## 2026-10-19 更新

没有新增通用插件平台；应先遵守当前可编译节点和输入护栏，不将候选能力比较当作技术选型定稿。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## Plugin Candidate Matrix

Change Frequency未有Git共变统计/需求台账，以下均UNKNOWN；收益为INFERRED，不以猜测频率制造抽象。

|Capability|Current Mechanism|Change Frequency|Core Coupling|Plugin Benefit|Plugin Cost|Recommendation|
|---|---|---|---|---|---|---|
|Rule Operator|Audience enum/switch|UNKNOWN|类型/解析/校验/执行|避免语义多份|版本/类型矩阵|进程内descriptor registry，T04后|
|Journey Node|目录+编译类型转换+runtime switch|UNKNOWN|路由、状态、ABI|局部纯executor扩展|旧状态与命令兼容|先typed node spec，再registry|
|Trigger|事件router分类+显式START|UNKNOWN|租户/身份/目标版本|多源复用|重放、路由更新|需求证明后模块化，不建独立服务|
|Action|command路由+ProviderConnector|UNKNOWN|幂等/外部结果|供应商替换|未知结果/重试/验签|保留适配器，用统一envelope|
|Reward|Benefit资金/目录/意图|UNKNOWN|高，账本不变量|差异化履约|资损与对账|只插件化渠道履约，不插件化守恒|
|Audience Provider|字段元数据+外部delta|UNKNOWN|身份/时效/用途|复用权威来源|权限与同步协议|先合同映射，后provider port|
|Activity Type|Campaign.Type与方言|UNKNOWN|多域组合|复用成熟能力|新不变量被隐藏|配置组合优先；独有状态才新domain|

证据：[DefaultNodeRegistry.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/marketing-control-service/src/main/java/com/acme/marketing/control/application/DefaultNodeRegistry.java#L1)、[JourneyCompiler.java:28](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L28)、[JourneyRuntime.java:38](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L38)、[EventPayloadRouter.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/event-gateway-service/src/main/java/com/acme/marketing/eventgateway/application/EventPayloadRouter.java#L1)、[AwardIntentService.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/benefit-funding-service/src/main/java/com/acme/marketing/benefit/application/AwardIntentService.java#L1)。当前没有证据支持远程热插件市场。

## Journey Node Extension Matrix

以新增“HTTP节点”作为成本分析，不是新增需求或授权。

|边界|当前需动什么|目标与验收|
|---|---|---|
|Frontend|palette类型/表单/画布保存/错误展示|可消费受控metadata；不丢版本|
|Schema|config/权限/endpoint/response/timeout合同|schema和服务器验证一致|
|Backend DTO|目录与Graph config到typed spec映射|禁止任意Map直传外网|
|Validator|URL允许范围、必需路由、无界循环限制|错误在发布前定位|
|Compiler|稳定type映射、结果变量类型、source map|不依赖截取类型名猜enum|
|Executor|纯节点生成command，外部适配执行|Flink线程不直接远程HTTP|
|Persistence|若需等待结果，实例/命令/回执版本|保留旧serializer/幂等键；不能假设零表变更|
|Runtime|超时/未知/取消/重试/重复响应|旧实例回放仍同身份|
|Observability|节点结果/拒绝码/因果关联|可查一次超时是否仍未知|

至少9个逻辑边界，不是已测9个文件或9人日。SEND派生SMS配置可能复用既有channel，成本远低于新增带结果的HTTP节点；不能一概统一。

## ActivityType Plugin还是Composition

优惠券、买赠、召回/会员营销优先组合已验收的Rule/Audience/Journey/Action；没有真实实现的公式/权益类型不得因枚举存在就宣称可配置。购车返现要订单证据、观察期与资金确认；拼团有成员/成团/超时退款状态；秒杀有库存并发不变量；抽奖有概率/库存/公平审计。后几类先做领域建模，活动类型插件最多负责配置与合同绑定，不能隐藏新的资源权威。

复杂度预算：本阶段不新增中间件、不热加载第三方代码。registry也有版本冲突/重复注册风险，启动时验证唯一(type,version)及ABI；插件不能改变核心租户/幂等/账本规则。只有实际两个以上实现需要独立迭代且成本下降可验收才引入扩展点。

## 继承证据

- [上一阶段 13-extensibility](../marketing-platform-analysis/13-extensibility.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 05-rule-engine](../marketing-platform-analysis/05-rule-engine.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 07-journey-engine](../marketing-platform-analysis/07-journey-engine.md)：作为发现输入；本章新增判断以定向源码复核为准。
