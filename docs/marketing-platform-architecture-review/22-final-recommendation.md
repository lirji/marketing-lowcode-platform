# 22 最终建议、范围覆盖与交接

## 2026-10-19 更新

原立即修复 T01/T03 的代码部分已交付；后续优先补业务合同、存量制品/外部终态及容量恢复证据，CI 配置阻塞仍保留。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 决策结论

**Final Status: ARCHITECTURE_AT_RISK**。主要依据是A01已确认的跨层语义不一致及A02内存探针确认的发布前校验缺口。它不是“代码已经造成生产事故”的断言，也不否定已有资金/版本保护。评审和演进规划完成；实施、部署和容量验收尚未进行。

立即优先的候选动作是T01/T03：收窄错误DSL、补完整编译验证；受影响存量制品需只读盘点和Owner决策，不自行停用生产。并行T00恢复工程门禁。随后T02/T04/T05补语义与领域合同，再推进发布留存、运行恢复、metadata扩展。详见[执行路线](19-evolution-roadmap.md)。

## 风险→决定→任务→验证闭环

|问题|优先级/证据|目标决定|任务|验收|
|---|---|---|---|---|
|A01 Offer语义丢失|P0预防性；静态确认，线上影响UNKNOWN|D01同源IR|T01/T02|F01差分与支持子集|
|A02 Journey缺路由|P1；临时探针确认|D02编译语义|T03|F02必需路由|
|A03 生命周期预算|P1合同/P2容量；代码确认，事故推断|D07预算与留存|T08/T09|F10/F12/F13|
|A04 runtime DB耦合|P1条件性；同步IO确认，性能未知|D07资源/故障边界|T08|F11慢DB/kill|
|A05 Campaign多状态|P1边界前置；持久接线缺口|D04意图与观察分开|T05|F04部分发布|
|A06 Audience类型|P1；校验/执行不一致|D03类型矩阵|T04|F03|
|A07 Audience身份|P1补证阻塞；外部映射UNKNOWN|D03稳定引用|T04|F03真实撤销|
|A08 组合发布/保留|P1若要求强原子，否则P2|D05按需Bundle|T06|F05/F06/F07|
|A09 Schema往返|P1升级前置；固定版本/清空变量确认|D08保真迁移|T10可先切兼容修复|F14/F15|
|A10 静态护栏覆盖|P2；jobs不在SQL检查范围|D09边界治理|T08/T12|F16/F17|
|A11 外部终态|P1补证/接线阻塞|D10真实合同/原身份恢复|T09/T11|F08/F12/F18|
|A12 解释分散|P1恢复/P2体验|D06因果关联|T07/T09|F09|
|既有R05/R06/R09|P2 DEV缓存/Lint/DEMO差距|局部治理，不扩架构|T00后独立切片|租户切换、零警告、API种子数据|

P0/P1都在04–13对应章节给出Problem/Evidence/Impact/Root Cause/Current Workaround/Recommended Direction/Trade-off/Priority。严重度口径与上一轮不同已注明；不能把UNKNOWN当确认漏洞。

## 不建议现在做

不新建通用Rule微服务；不把所有runtime合成统一服务；不更换Journey引擎；不引入ES/Bitmap/另一套MQ/分布式事务；不热加载第三方插件；不机械拆Benefit资金事务。原因是现有核心阻碍先来自语义、合同与恢复证据，新增设施不能替代这些工作。将来触发条件见17章，不把本次“不做”当永久禁令。

## Evidence Gaps与进入下一阶段条件

|Gap|需要的输入|影响任务|缺失时行为|
|---|---|---|---|
|G01|身份/SKU/许可/风控/权益终态合同和sandbox|T11/T09|保持裂变拒绝，不能宣称真实闭环|
|G02|脱敏snapshot→segment→membership→decision链|T04|类型治理可推进，身份迁移HOLD|
|G03|明确隔离测试环境、配置摘要、E2E权限|T06/T09|只交验证计划，不伪造通过|
|G04|QPS/活跃旅程/群体/偏斜/延迟目标和硬件|T08|不给容量承诺，不直接选ES/Bitmap|
|G05|寿命/保留/重放/RTO/RPO及隔离恢复目标|T08/T09|不得删除旧制品/去重或做破坏恢复|
|G06|候选版本兼容/许可/漏洞专项|涉及新依赖的后续变更|本轮能力比较不构成选型定稿|
|G07|个人贡献、设计历史、线上收益|面试材料|不把系统能力包装为个人成绩|
|G08新增|活动业务状态、支持DSL、跨slot原子需求签收；存量错误制品曝光|T01/T02/T05/T06|做候选方案/只读清单，不代做关键业务取舍|

## 提示词70节覆盖映射

|节号|交付位置|
|---|---|
|1–3 执行/Evidence/旧产物|00、01、19、本文；无产品/Git变化|
|4 当前地图|01、08|
|5 控制/运行|02|
|6 Activity|03|
|7–11 Rule分层/Context/operator/业务边界|04|
|12–14 Audience计算/Who-Whether|05|
|15–17 Journey运行/版本/Node|06|
|18 节点扩展|10|
|19–20 图治理/Delay|06、15|
|21–25 Release/原子/immutable/rollback/隔离|07|
|26–29 Trigger/Action/执行/同步异步|08|
|30–32 事件/幂等/Execution|12|
|33–34 解释与运营debug|13|
|35–39 Low-code/Schema/Validation/Compiler/Metadata|09|
|40 Plugin矩阵|10|
|41–43 Domain/单体微服务/Extraction scorecard|16、17|
|44–45 数据Owner/分层|11|
|46–47 性能/三档规模|14|
|48 故障模式|15|
|49 Fitness|20|
|50–51 目标与分层|18|
|52–56 增量/Phase/Exit/Priority/Complexity|18、19、本文|
|57–59 Build-Buy-Integrate/规则工作流选型|04、06、18|
|60–63 中台/活动类型/规则边界/核心抽象|10、18|
|64–65 面试3/10/30分钟与≥30问|21，共32问|
|66 23文件目录|00–22完整命名|
|67 Final Review|00的25维报告|
|68 Executive 15问|00|
|69 建议证据链|04–13问题格式、18决定、19任务、20验证|
|70 停止条件|本文与19交接；止于文档评审|

## Skill Handoff

采用architecture-reviewer的事故风险/十倍流量/故障/Day-0边界要求，输出路径按用户提示词覆盖默认.cursor路径；不生成重复权威报告。服务scorecard是本次定性评审，不冒充已运行decomposition机器评分；迁移候选计划也不冒充evolution-planner的正式PLAN_READY。

```yaml
SKILL_HANDOFF:
  status: COMPLETED
  gate: REVIEW_COMPLETE_IMPLEMENTATION_HOLD
  produced:
    - docs/marketing-platform-architecture-review/00-executive-architecture-review.md
    - docs/marketing-platform-architecture-review/19-evolution-roadmap.md
    - docs/marketing-platform-architecture-review/20-architecture-fitness-functions.md
    - docs/marketing-platform-architecture-review/22-final-recommendation.md
  unresolved: [G01, G02, G03, G04, G05, G06, G07, G08]
  downstream_requirements: [explicit_execution_instruction, scoped_plan_and_contracts, verification_environment]
  recommended_next: stop
```

**ARCHITECTURE REVIEW COMPLETE**。等待用户明确下一阶段 Architecture Change Execution & Verification，不自动修复、不提交、不Push。

## 继承证据

- [上一阶段 00-executive-summary](../marketing-platform-analysis/00-executive-summary.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 15-architecture-risks](../marketing-platform-analysis/15-architecture-risks.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 16-architecture-evolution](../marketing-platform-analysis/16-architecture-evolution.md)：作为发现输入；本章新增判断以定向源码复核为准。

## 产物验证记录

23个文件名称与提示词一致；231处相对文件引用及行号检查通过，40个本阶段自动索引源码文件指纹未漂移；既有被跟踪/未跟踪任务文件及上一阶段分析报告指纹保持不变。9个Mermaid图做围栏/类型基本结构检查，未渲染；不声明视觉校验已通过。纯内存Journey探针负例失败点与正例COMPLETED符合预期。没有新增产品测试或运行真实业务。
