# 14 性能模型与容量推演

## 2026-10-19 更新

原无界人群投影已改为 10000 条/最长一秒缓存及主键回源；本章容量数字仍是模型假设，不是实测成绩。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 先定义模型，后决定优化

以下均为INFERRED架构模型；实际QPS/峰值/并发/硬件/利用率、P95/P99、SLO/RTO/RPO均UNKNOWN。不能从用户总数推导每秒请求或活动实例数。

|路径|变量与测量方式|成本模型/限制|
|---|---|---|
|Rule QPS|q报价/秒、L车行数、k可选优惠、分阶段数s|候选搜索上界与2^k、每次定价成本相关；k<=14，不直接推断毫秒|
|Audience evaluation|M投影成员项、每项字节b、复制数r、更新率u|全量热内存约M×b×r，另有map/字符串开销；不是用户数×1bit|
|Trigger throughput|e事件/秒、有效比例、payload字节p|接入写库/Outbox/Kafka约e×p，加收据索引和复制|
|Journey instance count|λ启动率、W平均存活时间|稳态约N=λW；不稳态按峰值进入和退出曲线积分|
|Active task / Delay task|N_wait、每实例timer数、到期分布|到期集中会突发，平均QPS无法表示整点峰值|
|Journey state|信号累计h、variables大小v、节点计数|总状态约Σ(snapshot+IDs(h)+v)，checkpoint与恢复成本随之变|
|Action throughput|λ_action、渠道耗时w、并发c、成功率|稳定服务率必须大于到达率；外部配额可能先饱和|
|Release frequency|f发布/秒、artifact大小a、runtime副本r|预热带宽/验签/DB读取约f×a×r；与报价负载分测|

证据：[OfferSelector.java:16](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/decision-runtime-spi/src/main/java/com/acme/marketing/decision/pricing/OfferSelector.java#L16)、[JourneyRuntime.java:23](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L23)、[JdbcJourneyPlanResolver.java:20](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L20)、[membership投影](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/services/offer-decision-service/src/main/java/com/acme/marketing/decision/runtime/AudienceMembershipProjection.java#L1)。

## 10万 / 100万 / 1000万用户：条件推演

|用户规模假设|更先关注什么|不能直接推出什么|下一份证据|
|---|---|---|---|
|10万|活动实例存活时间、每人群体数、热资源行、候选组合|不代表当前配置必定轻松承载|真实活跃/到达/车行分布与小规模基线|
|100万|若全量membership复制每副本，内存与启动扫描显著增长；长等待state累积|不等于必须ES/分库|成员字节、复制放大、启动耗时、checkpoint大小|
|1000万|若同规模活跃实例/群体，state/恢复窗口、整点timer、渠道配额成为突出约束|不能声称现有Flink一定扛住或一定崩溃|偏斜与冷启动/故障工况压测、容量预算|

没有实际数据无法确定“哪个先炸”。可证伪的优先假设是：大群体全量复制时先看Decision内存；长旅程时先看checkpoint与恢复；高优惠组合先看CPU；外部慢则先看Action积压。

## 十倍流量各资源推演

|资源|潜在事故|最小充分措施（先测量）|
|---|---|---|
|DB|热门账户/slot/审计头锁等待，投影追赶慢|锁时长/等待与慢SQL证据，缩事务、查询预算/索引；守恒不能拆开|
|Redis|幂等claim延迟/不可用影响报价|明确权威持久/故障策略与限流，不能自动降到独立DB键空间|
|MQ|积压/保留窗耗尽/重复重放|测消费速率与oldest age，保留和恢复预算，禁止盲目增分区|
|JVM|membership/ID集合/目录大值撑内存|数据字节与容量预算，有界装载和生命周期；不只调堆|
|线程/执行器|同步JDBC阻塞、DRL不可中断待核验|deadline与隔离，测试线程关闭；不靠无限虚拟线程解决|
|RPC|目录fetchAll、风险/渠道延迟放大|按SKU查询合同、总截止时间、受控并发|
|下游|优惠券/短信配额耗尽|租户/渠道公平队列与退避，UNKNOWN不新ID重发|
|网络|artifact分发/checkpoint/事件复制峰值|量测带宽与压缩/批次预算，控制同时冷启动|
|存储|outbox/审计/历史制品/checkpoint持续增长|保留治理、归档与恢复演练，不清空幂等/在途依赖|

## 验证协议

选脱敏代表性数据，记录版本、机器、租户偏斜、冷/热状态；分开报价极限、长时state、整点timer、投影追赶和外部故障。指标必须包含业务守恒、重复效果=0和丢失证据检查，而非只有响应码。验收延迟/吞吐目标由业务签收后填写；本轮未跑压测、不承诺提升比例。已有容量脚本只作可复用入口，不能继承旧结果为当前证明。

## 继承证据

- [上一阶段 10-runtime-architecture](../marketing-platform-analysis/10-runtime-architecture.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 16-architecture-evolution](../marketing-platform-analysis/16-architecture-evolution.md)：作为发现输入；本章新增判断以定向源码复核为准。
