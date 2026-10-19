# 21 架构讲述与32个深挖问题

## 2026-10-19 更新

讲述时应纳入后续正确性整改与明确验证范围；个人经历、规模、收益和生产结果不从系统能力自动推导。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 使用边界

下面是基于仓库的讲述素材，不证明个人作者身份、团队决策历史或生产成绩。讲述时将“系统采用”与“我负责”分开；没有本人贡献信息不代填。现状CONFIRMED、动机和建议INFERRED、规模与线上效果UNKNOWN。不能在面试中把规划讲成已交付。

## 3分钟版本

这个项目是领域专用的营销低代码平台，核心不是把活动表做成可配置CRUD，而是让营销设计经过审核和编译，成为可追溯、可回滚的运行制品。运营端用图配置优惠或Journey，控制面保存定义版本、做结构校验和审批，编译器把配置变成OfferPolicy或JourneyPlan，再签名发布。在线运行读取已安装制品，不直接执行最新草稿。

业务边界有四个重点。Campaign管理目标和归属；Audience管理群体资格和新鲜度；Rule判断当前交易可享受什么；Journey处理下一步动作和等待。报价可以直接进入Decision，不必绕Journey。Journey本身是单游标事件状态机，由Flink托管状态与processing-time timer，实例固定计划版本；触达和奖励通过持久命令交给各自领域，不在状态机里直接调用渠道。

一致性按边界设计。发布slot内用事务、CAS和Outbox，跨实例靠单调指令收敛。generation标识程序代次，activationSequence标识指令顺序，所以回滚到旧程序也使用新序号。资金则依靠数据库约束、版本校验、账本和稳定业务幂等键；Kafka事务不替代外部发券幂等。

评审发现最应优先修的是Offer仿真与编译并非同源，图条件可能被编译忽略；还用纯内存探针确认Journey缺false分支仍能编译、运行才失败。因此下一步先修编译语义和发布前检查，再做类型化事实、解释视图和恢复演练。没有真实容量数据，不能说已支持千万用户；没有外部终态验收，也不能把本地SENT等同发券成功。

## 10分钟版本

**第1–2分钟，业务模型。** 营销要解决的是“谁、在什么条件下、得到什么、何时执行，以及谁批准和谁承担资源”。Activity对应Campaign，属于业务目标和范围容器。不同方言的DefinitionVersion归属它；Audience不是当前交易规则，而是可复用群体资格；Rule关心本次购物车、渠道、时间、人群和优惠组合；Journey处理用户事件与等待。订单、支付、会员和外部权益仍各自是权威，营销不应该直接写它们的表。

**第3–4分钟，设计与运行。** GraphDefinition包含类型、语义版本、config、边和变量，控制面校验端口、副作用、环和成本，审批后编译生成制品。运行模型与画布模型分开，Offer变成Policy，Journey变成Plan。发布先stage、预热并验证ACK，再activate；签名绑定制品字节与来源，但不保证编译语义正确。本仓恰好出现仿真沿分支运行、Offer compiler却提取全部优惠节点的问题，所以设计安全不能只看签名和审批。正确改进是明确支持的DSL子集，未支持语义拒绝，再复用同源IR和求值器。

**第5–6分钟，运行状态。** 同步Decision完成资格过滤、优惠互斥选择和精确金额分摊，报价需要低延迟，不适合为了“解耦”全部异步化。Journey使用另一个事件入口，可信START携带激活计划引用，Flink单key持有实例与timer。每次advance产生新状态和命令，不执行外部副作用；投影消费以收据、effect和dispatch Outbox形成数据库提交边界，再分发到Engagement或Benefit。单游标支持分支与等待，不是通用并行join；计划和serializer兼容是恢复的重要约束。

**第7分钟，一致性。** 我会明确局部原子范围：事件接入收据和Outbox一起提交；Flink状态和Kafka输出受checkpoint/事务sink协调；Kafka到MySQL投影有收据；外部请求仍存在响应丢失窗口。每个边界必须有原业务身份重试、异内容冲突检测和未知结果处理。不能一句exactly-once概括整条发券链路，也不能把traceId当所有动作的幂等键。

**第8分钟，版本和回滚。** generation是制品代次，activationSequence单调向前，合法回滚是新指令指向留存旧代次。旧Journey实例继续固定旧计划，新START受当前激活约束。配置回退、新流量切换、实例迁移和业务补偿四者独立。发布回滚不会撤回短信，不会自动撤销人群成员更新，也不会把已经发的券变没。历史制品、签名信任和checkpoint留存必须覆盖在途实例。

**第9分钟，容量与故障。** 总用户数不是吞吐。需要测报价候选组合、membership复制大小、活跃旅程数、累计信号ID、整点timer、渠道配额以及恢复速度。当前热人群集合与去重增长值得关注；同步resolver JDBC可能把DB慢变成算子积压，但还没有实测瓶颈排序。故障验证优先覆盖消息重复、提交后崩溃、外部成功但响应丢失、发布重启和timer恢复；以业务守恒和无重复效果验收。

**第10分钟，演进选择。** 保留纯SPI、资金事务、签名发布、版本轴分离和副作用隔离。先补语义与合同，再做解释/恢复和适度registry。已有多服务，不再因为Engine命名继续拆。是否换Temporal/Flowable或加ES，必须由当前模型无法满足的实际需求和代表性PoC决定。现阶段报告完成，不代表这些改造已实施。

## 30分钟深挖路线（按证据展开）

|时间|讲述与白板内容|必须承认的边界|
|---|---|---|
|0–4分钟|用“用户未命中优惠/等待后领券”还原02与08章两条路径；说明Who/Whether/What Next不是强制流水线|外部权威合同未全核验|
|4–8分钟|画Definition→Compile→Artifact→Manifest→Runtime；拿VIP条件被丢弃作为语义验证反例|签名不是业务正确性；同源IR仍是规划|
|8–12分钟|解释候选互斥、14可选上限、分阶段目标、最大余数分摊；金额守恒和性能上界分开|没有P99或节省金额业绩|
|12–17分钟|画Journey state/timer/checkpoint/effect/dispatch；展示缺false探针；讨论30天默认TTL与长等待|不是BPMN，REPEAT入口未闭环，恢复SLA未知|
|17–21分钟|以“generation5回3，sequence10→11”解释合法回滚和旧消息；老实例继续、新START当前|跨runtime slot不是全局事务|
|21–25分钟|外部券成功后超时，原sourceRequestId查询/重试；receipt/fence/ledger与UNKNOWN职责|下游真实幂等还需验收|
|25–28分钟|性能变量λ/W/M/k、热点和冷启动；说出先测什么再选组件|千万用户只是推演，无benchmark|
|28–30分钟|D01–D10取舍和T01/T03优先、分阶段exit/rollback；如何形成fitness|个人贡献和作者最初动机UNKNOWN|

演示源码优先04、06、07、12章；不用大段背诵类名。若面试官问“为什么当初这样设计”，回答可推导的优缺点，并说明缺历史决策记录，不能编造某次事故迫使重构。

## 32个高频问题、回答锚点与追问

|#|问题|回答锚点|进一步追问|
|---|---|---|---|
|1|为什么自研规则引擎？|保留受限优惠/金额内核，已有DRL/DMN adapter，未完全统一图语义|什么需求会触发替换？|
|2|为什么不直接用Drools？|它执行自身规则；不能修复错误Graph→Policy转换；领域金额不变量仍归本仓|如何验证转换等价？|
|3|RuleContext怎么设计？|typed FactRequirement→有界resolver→不可变快照；敏感来源受控|缺字段/陈旧事实如何处理？|
|4|复杂规则如何组合？|Audience平面ALL/ANY、Offer互斥选择、Journey分支各有边界|递归表达式与求值预算？|
|5|新增BETWEEN改哪些地方？|enum、类型/arity、evaluate、UI/合同/测试；优先descriptor registry|为何不直接热加载class？|
|6|规则如何版本化？|定义版本、artifact checksum/ABI、generation各管一层|历史reader何时能删？|
|7|哪些逻辑不能进规则？|资源守恒、权限、幂等、合法迁移不能由运营任意配置|怎样阻止跨域副作用？|
|8|Audience与Rule为何分开？|群体身份/新鲜度与单次交易决策生命周期不同|能否共享predicate基础设施？|
|9|Snapshot是否冻结成员？|当前定义可固定而member可更新；必须声明LIVE/FROZEN模式|回滚是否撤销成员变化？|
|10|当前人群实时计算做了什么？|上游segmentIds集合差分，不是全量画像规则引擎|snapshotId怎么进入decision？未知须补证|
|11|为什么Journey用DAG？|不能照类名回答；当前是单游标事件状态机，图是设计表达|并行join需要何种token模型？|
|12|Journey与实例隔离吗？|Plan与Enrollment分开，版本固定，START校验当前激活|过期manifest如何支持老实例？|
|13|Delay如何恢复？|Flink timer和state checkpoint；投影DB不是执行权威|停机超过due/TTL怎么办？|
|14|如何避免Journey重复执行？|signal去重、checkpoint重放、稳定effect key、外部幂等分段|去重窗小于重放窗会怎样？|
|15|节点幂等键是什么？|tenant/enrollment/version/node/execution摘要|同节点循环第2次与重试如何区分？|
|16|Journey完成等于发券成功吗？|不是，END/command投递/外部履约三层状态|UNKNOWN怎么给运营展示？|
|17|为什么不直接用Flowable？|BPMN适合的需求未证明；现有营销单游标模型与部署迁移有成本|人工任务和复杂流程增加时如何PoC？|
|18|Temporal能解决什么？|持久编排与重放候选，仍需营销DSL和Activity幂等|旧Flink实例如何共存退出？|
|19|活动发布怎么保证一致？|slot内CAS+事务+Outbox，多runtime最终收敛|跨slot硬原子业务如何路由？|
|20|为何要Release Snapshot？|绑定制品和依赖；当前已有Manifest，不能重复造轮子|哪些动态事实不能冻结？|
|21|回滚怎么做？|新sequence指旧generation；配置/流量/实例/补偿分开|已发券怎么办？|
|22|为什么Runtime不能读Draft？|会造成未审核变化、不可复现和版本漂移|如何自动检查SQL访问？|
|23|灰度怎么做？|现有按tenant/slot/subject稳定分桶|同一用户跨服务需同bundle吗？|
|24|如何解释未命中？|实际版本、资格/事实新鲜度、reason/source map；UNKNOWN与NO_MATCH区分|事实包含PII怎样留存？|
|25|1000万用户怎么办？|先区分总量/活跃/群体复制/启动率和W，做偏斜容量测试|哪项资源最可能先饱和，证据？|
|26|是否需要微服务？|已有多部署；再拆需Owner、负载、事务、契约和隔离收益|哪些保持库或Control内部模块？|
|27|新增节点怎么扩展？|metadata/compiler/typed executor/state/观测，至少多个边界|旧版本checkpoint如何兼容？|
|28|新增活动类型如何设计？|复用组合优先；拼团/抽奖等独有不变量需领域模型|何时ActivityType插件反而隐藏风险？|
|29|外部券接口超时怎么处理？|原身份查询/重试，保留UNKNOWN，不盲目释放/换ID|下游不支持幂等怎么办？|
|30|Redis掉了能查DB兜底吗？|此处Redis是可选幂等权威，不是透明L2缓存|双存储切换怎样避免重复claim？|
|31|Kafka exactly-once是否足够？|只覆盖指定事务边界，不包含外部短信/DB随意副作用|提交后ACK丢失怎么证明一次效果？|
|32|最先改哪三件？|Offer语义、Journey编译完整性、Audience类型/身份；工程门禁并行|怎么定义exit和兼容回滚？|

第三方候选能力依据见[规则复审](04-rule-engine-deep-review.md)与[Journey复审](06-journey-engine-deep-review.md)的官方链接；回答中的适配建议均为推断，不是迁移结论。

## 继承证据

- [上一阶段 17-core-technical-challenges](../marketing-platform-analysis/17-core-technical-challenges.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 18-interview-assets](../marketing-platform-analysis/18-interview-assets.md)：作为发现输入；本章新增判断以定向源码复核为准。
