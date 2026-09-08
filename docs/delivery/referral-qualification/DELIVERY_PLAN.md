# 资格事实与账本精确切片计划

2026-09-08，实施前方案；尚无V4迁移或资格生产实现。依据本目录PREIMPLEMENTATION_REVIEW、冻结baseline/10-FINAL_PLAN与09-PRODUCTION_DESIGN，以及当前ReferralOrderEvidence/Merger/PolicyEvaluator。当前首绑DB11尚在等待Docker错峰复验，本方案编写不改变已审首绑源码。

## 完整边界

交付内部事实接收、永久业务历史、单关系资格投影、当前有效人数、观察期重评与可恢复持久任务；不开放HTTP或实际消息订阅，不发奖、不创建奖励/配额/AwardIntent、不启用默认调度。资格服务复用SPI merger/evaluator，增加runtime-spi/referral-runtime-spi依赖，不复制算法、不依赖compiler application包。外部来源、规范主体、KMS、历史制品/运行许可与会员口径全部为明确Port，生产默认拒绝。

按两个可审查子阶段实现：V4A事实账本与永久Inbox/历史/隔离及待投影触发；V4B单关系资格/人数及持久任务租约。最终合在该资格切片的一次数据库专项验收，若实现超出有界范围先保留V4A完整交付而不宣称资格已完成。V4迁移落盘前重新核对空号，所有新表/字段中文注释，不改V1–V3语义。

## Port与精度

1. TrustedReferralEvidencePort接受受限内部信封，返回已验证issuer/tenant/source/order身份、Observation及主体HMAC/版本、短有效期与完整RequestBinding。调用方不能传sourceVerified=true获得信任。canonicalSubject只在内存交由SPI，日志/toString脱敏，持久存储不得直接序列化它。
2. ProtectedReferralEvidencePort在事务外加解密完整规范状态/历史；AAD绑定tenant/source/order/resource类型及业务revision。稳定业务摘要的主体部分使用受信HMAC键，不能对canonicalSubject做普通SHA。密钥引用与索引密钥分离，默认不可用，不内置测试密钥。
3. QualificationPermitPort返回与原Participant及Relation完整冻结制品一致的ReferralPlan、权威会员绑定时新客/注册事实与修订、首单策略映射、风险/运行许可及有效期。未知口径/缺计划/缺风险保持PENDING或BLOCKED，不把evaluator候选直接计为最终有效人数。发布升级不能替换原计划。
4. Observation/State原始Instant以规范ISO或seconds+nanos在受保护内容中保存；扫描用datetime(6)仅为索引。对Port时限/事实时间/观察截止一律用原始Instant判断。绑定boundAt继续使用V3固定微秒值；不向上舍入有效窗口。

## V4持久模型

- evidence_inbox：tenant+authenticated issuer/source+eventId唯一，稳定完整摘要、原结果及原订单资源。异内容不覆盖成功回执；追加冲突审计并对原已接受资源隔离/触发重评。
- order_evidence_current：tenant+source+orderId唯一，固定HMAC/版本、组织店铺、受保护完整State、rowVersion、quarantine、投影目标水位。不能把scope全字段作为订单主键让主体漂移生成第二份合法订单。
- order_evidence_history：tenant+source+orderId+businessRevision唯一，受保护完整Snapshot、稳定摘要及最初接收锚点。不同event重复同revision查完整历史，不能只查latest。
- qualification：tenant+relation+goalType唯一，原规则hash、订单/会员/风险依据修订、state/reason/due、counted/everQualified、qualificationRevision和证据水位。
- progress：tenant+participant唯一，validCount>=0、everQualifiedCount、revision。同一participant锁保护false→true加一、true→false减一、重复不变；再次合格不重复增加历史人数。
- evaluation_task：tenant+relation唯一，desiredWatermark/reason、dueAt与retryAt分别记录、leaseOwner/until/fence及rowVersion。短事务领取租约，不能持任务锁再等待participant。
- evidence_fanout：tenant+order资源唯一的持久扫描水位/游标与租约，用于事件先于关系提交或多关系共享订单；与单关系task分离，不在事实接收事务锁所有参与者。若复用已有Outbox可完整证明上述游标/fencing，才省略此表，不用非持久列表替代。

所有引用使用tenant复合外键，索引按二进制精确比较；不新增真实配置、清理或保留Worker。审计/内部Outbox复用现有表，payload仅资源ID/修订/原因和摘要，不带主体/token或解密事实。

## V4A事实事务：事务外保护与乐观重试

先在事务外认证、读当前版本和目标历史、解密并计算merger候选，再加密待存内容；随后开始短事务按索引锚点→Inbox→单order current获取锁。锁后确认当前rowVersion、历史存在性/摘要与事务外准备完全一致，重新核对Port原始有效期；不一致则回滚，返回事务外进行有界重新准备，禁止锁内KMS或网络。新order缺失通过占位行在同事务序列化，未形成有效State的占位不得单独提交。

历史Lookup是在本次历史INSERT前查到的NotSeen/Seen/Unavailable；本次INSERT不能被误当Seen。原事件回放保持原结果，但持久投影任务仍独立可继续。更高非法修订/旧历史冲突/同订单Scope漂移使current永久quarantine，不保留旧READY继续计数；历史/current/Inbox/冲突审计/重评Outbox同事务提交。来源或保护不可用不写成功Inbox；同键异内容不覆盖原成功摘要。

订单入账不获取participant/relation/qualification锁。退款累计值与首次接收锚点只由merger推进，不做事件增量相加、不把负净额改零、不虚构settledAt。长期quarantine无自动解除入口。Inbox异内容若指向另一订单，对原回执资源的隔离也须独立事务外准备并以原current版本CAS验证；不能拿新事件State覆盖原订单。

## V4B单关系事务与无丢失触发

BOUND Outbox消费入口只接受已提交的内部事件ID，查原关系后持久enqueue；订单入账原子写fanout目标水位/Outbox，扫描按HMAC、组织店铺匹配关系并推进游标。发生Scope漂移或quarantine时，fanout还必须按qualification的订单依赖索引覆盖所有已投影关系，不能只按新事件HMAC/org/shop查找。两条持久路径互补，覆盖事件在绑定未提交时到达。入口默认不自动运行；完整集成需要显式内部调用及Outbox去重证据，不冒称真实渠道已接通。

领取task短事务提交后，事务外读取并解密所需当前证据、取得历史计划及会员/风险许可。投影事务按锚点（若需）→participant→relation→qualification→progress→order current锁定当前读，验证此前证据rowVersion/任务目标水位与原始许可时限；变化则回滚重新准备，不能用旧解密内容提交新计数。事实事务不反向取participant，因此无order→participant反序。

锁后用SPI重新evaluate，登记候选及许可完整性；只有全部权威前置完成才counted=true。资格/进度/审计/内部QUALIFICATION_CHANGED、PROGRESS_CHANGED Outbox与任务完成同事务。完成task必须同时验证lease fence和未被新desiredWatermark超越，旧worker不能抹掉新请求。返回状态明确携带证据/投影水位，不宣称事实与所有关系全局同步。

注册模式从权威绑定时会员快照取得newCustomerAtBind、registeredAt及首次收到时间；订单模式从合并状态和已确认首单口径取得Evidence。任一模式缺真实来源保持PENDING，不能把另一模式UNKNOWN变YES。due任务仅成熟观察期，retryAt处理缺来源/缺历史；二者不混用。退款/隔离撤销当前计数而不改绑，everQualified不回退。

## 验证与后续门禁

先纯测Port异常/绑定、原始纳秒、准备版本变化与短事务外I/O，结构独立审查后集中隔离MySQL。DB覆盖Inbox异内容、历史非latest冲突、退款先到/低修订不回退、Scope漂移永久隔离、跨tenant/source订单号、事件与绑定提交竞争、两关系并发计数、due与退款并发、fencing过期、Outbox及计数原子回滚、ELIGIBLE往返的当前/历史人数。

首绑DB11目前尚未完成；本方案不构成资格实现或V4已应用。后续奖励必须在同一资格事务接入reward/配额，或实现经独立审查的版本保护重算；发奖授权必须确认当前证据水位，堵住退款已入账但投影未完成的间隙。本轮不激活真实资格处理/计数驱动奖励。真实身份映射、release、风险/会员/首单口径、maxBindAge起点、KMS/隐私留存及生产参数仍待确认。

独立方案审查已确认可实施；Inbox跨订单冲突的原资源CAS隔离、quarantine按既有订单依赖fanout两项实施约束已纳入。此结论仅为方案审查，未编写或验证V4实现。

## V4B 实施合同（独立于已终审 V4A）

资格单关系唯一，目标仅由原参与固定制品 Permit 确认；首次未接通计划可落 `goal_type=NULL/PENDING`，不能计数。已有目标不可换版/换目标。C02 必须绑定 boundAt 和 HMAC，C03 必须显式选定订单与口径，不能本地排序猜首单。风险/历史许可/保护来源默认不可用。

新增 V5：qualification（含既有订单依赖）、progress（valid/ever）、evaluation_task。task 故意无外键，由入口非锁定读取真实关系核对元组，复制 org/shop；避免 fanout→task 插入的隐式父行锁成环。requested_revision 每关系递增，跨订单业务 revision 不作大小比较。新 BOUND 在原绑定仓储保存末尾同事务入队，历史原成功回放不依赖新任务；旧关系由独立内部补偿入口补队。

领取仅 task 短事务。处理事务锁序 anchor→participant→relation→qualification/progress→current→task，最后 fence/请求水位/原始 Instant 重检。外部历史许可与 KMS 解密只在事务外，真实订单 current 水位 CAS；观察期截止用秒/纳秒保留，微秒仅候选扫描。资格、valid/ever、任务完成、审计和 Outbox 同事务；退款撤回 valid，不清 ever，恢复不重复累计。没有 Worker/公开 HTTP/发奖或真实生产许可默认值。

### V5 审查补强

最终保存的 SQL/Outbox 索引也可能等待，因此 save 完成后再次读原始 Instant，检查已使用的许可与租约；过期必须整事务回滚。会员最高修订不会在缺许可时清零，低修订只 PENDING 并保持旧水位；同修订异稳定摘要或固定会员口径漂移永久隔离 REVIEW，后续高修订不自动清除。memberEvidenceDigest 由受信保护适配器对完整 C02 稳定业务事实计算独立 HMAC，不混风险与重试元数据。注册目标另有 registrationAnchorDigest，对注册事实+首次可信接收时点及绑定计算 HMAC；持久摘要锚拒绝后续 Permit 改变宽限，不新增注册明文列。真实 HMAC/KMS/来源依旧生产门禁。

V5 纯计数 8、纯应用 18 已通过（26 唯一场景）；V5 真实 SQL 与联合 fanout 仍待独立数据库专项，不能称当前树全量通过。构建初始缺隔离快照 jobs/architecture-tests、测试短密文 fixture、Mockito stubbing 的事务断言误触、补字段调用参数错误均已修正；失败日志保留，没有在共享 target 上 clean 或重复全量。
