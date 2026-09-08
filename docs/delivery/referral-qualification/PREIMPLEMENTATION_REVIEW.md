# 资格账本实施前独立审查

日期：2026-09-08。范围：资格事实持久化、观察期重评和当前有效人数；本文件不代表实现或测试通过。

## 已核对的依据

- 交易任务冻结 `baseline/10-FINAL_PLAN.md` 第 4/6 节：证据支持先事件后绑定；qualification 唯一 tenant/relation/goal；progress 当前人数可下降；完整交付最终要求资格、配额、reward 与 outbox 原子衔接。
- `ReferralOrderEvidence` / `ReferralOrderEvidenceMerger`：累计快照、显式 HistoryLookup、历史修订完整业务比较、永久 quarantine、可恢复 historyPendingRevision；不能用增量退款累加替代。
- `ReferralPolicyEvaluator`：注册事实可在活动开始到绑定之间；订单结算必须绑定后；半开达标窗口、首次事实接收时间控制显式迟到宽限、观察期起点为事实时间、UNKNOWN 失败关闭。
- V1 participant/subject_index_anchor、V2 token、V3 relation：relation 永久固定 campaign/participant/definition/version/generation/artifact/policy/组织/店铺、boundAt/deadline。好友身份仅 HMAC/密文；失败不释放首绑。

## 本轮交付边界

只写内部应用服务、持久化和测试，不开 HTTP/Kafka 真实来源，不发奖，不签奖励凭证，不创建 reward、配额占用或 AwardIntent。Outbox 类型须明确是 QUALIFICATION_CHANGED / PROGRESS_CHANGED / EVIDENCE_REEVALUATION_REQUIRED 等内部事实，不能借现有 reward relay 发奖。

`valid_count` 是经过权威输入和冻结规则裁决后的当前有效关系数，不能叫奖励成功数。缺新客、首单口径映射、历史制品证明、风控/运行许可或真实来源认证时保持 PENDING/BLOCKED，不以测试 fixture 为生产通过。若业务风控只能在后续发奖阶段接入，应把本轮输出明确命名为规则资格候选计数，禁止冒用完整最终有效人数；推荐保留资格许可 Port 默认 unavailable，从首轮就能实现完整失败关闭状态。

完整冻结要求中的“更新人数并生成 reward 同事务”尚未完成：本轮不得激活真实资格处理或让计数触发奖励。下一轮在同一资格事务接入 reward/配额，或以受版本保护的账本重算补齐，并完成独立并发验证后才开启。

## 推荐事务拆分与锁顺序

不要在一笔订单事件事务中锁住所有活动参与者。相同订单可被不同活动关系查询，跨活动 fanout 锁会与首绑及重评产生反序。

1. **事实入账短事务**：事务外认证/验签/权威映射/解密与规范化；事务内 Inbox 唯一键 → 单个订单 current 聚合锁 → 该业务 revision 历史比较 → merger → 历史/current/Inbox/审计/待投影 Outbox 原子提交。失败不能提交成功 Inbox。不能先插入历史再把本次插入当 Seen，掩盖真实首次观察。
2. **单关系投影短事务**：事务外取得固定制品、会员绑定时新客、风险/许可等证据；事务内按既有 participant → relation → qualification → progress（progress 由 participant 锁串行）取锁；再对需要的订单 current 做锁定当前读并在提交前保持锁。订单入账事务绝不反向锁 participant/relation；两种事务因此不会互相反序。
3. **投影任务去重**：每次只处理一个 relation，任务租约短事务先领取即提交，不携带任务行锁去获取 participant；完成标记与资格/计数/审计/Outbox 在同一投影事务，带 lease fencing/version。关系触发任务与订单 fanout 任务统一幂等。
4. 资格服务不获取或修改角色/token，不倒置已有 anchor→命令→角色→participant→token→relation 顺序。若需要 HMAC anchor，必须在 participant 前取得且验证版本；不要因退款或资格失败修改角色/重绑。

上述是“两段各自原子”，不是事实入账与所有关系更新全局原子。事实提交即持久化重评任务，投影有显式水位，绝不能将处理中状态声称为全关系实时一致。下一奖励授权确认必须验证资格使用的证据水位仍为当前；否则事实已退款但投影未到的间隙可能发奖。此项为下一轮发布门禁。

## 数据模型最小集合（建议 V4，落盘前核对最新迁移序号）

| 对象 | 必要唯一性/内容 |
| --- | --- |
| evidence_inbox | tenant + 认证 issuer/source + eventId；稳定业务摘要与原处理结果；同键异内容必须留冲突审计，不更新成功回执 |
| order_evidence_current | tenant + source + orderId 唯一业务身份；固定 subject HMAC/keyVersion、组织/店铺；完整 merger State、rowVersion、原始时间精度 |
| order_evidence_history | tenant + source + orderId + businessRevision 唯一；完整稳定 Snapshot/规范摘要；firstReceivedAt 与首次结算接收锚点不能被重试更新 |
| qualification | tenant + relation + goalType 唯一；证据订单/业务修订/会员修订/规则 hash、state/reason、dueAt、qualificationRevision、counted 标志、everQualified 标志、投影水位 |
| progress | tenant + participant PK；validCount 非负、历史唯一曾达标关系数、revision；参与者同锁下 false→true 加一、true→false 减一、相同状态不变 |
| evaluation_task | tenant + relation 的可推进目标水位/原因；dueAt、状态、nextRetryAt、lease owner/until/version、rowVersion，任务不能被旧worker覆盖新水位 |

复用现有审计及 Outbox，不另造 reward 表。所有新增 SQL 仅 Mapper XML / Flyway，全部表/字段中文注释、CHECK/外键与精确二进制索引。拒绝 nullable UNIQUE 导致的重复空值业务键；禁止只用 eventId 或 orderId 全局唯一。

**订单主体漂移特别注意**：SPI Scope 包含 subject/org/shop，但数据库若直接以完整 Scope 为主键，会让“同订单同 revision 换主体”变成两套全新合法历史。数据库应以 tenant/source/orderId 锚定身份，再精确比较完整 Scope；出现变更须隔离该订单并触发已有投影复评，不得按新主体再建一份 READY。若真实来源不能保证 orderId 在 source 内唯一，额外订单命名空间必须由已确认合同显式提供，不能猜。

normalized_json 不得直接序列化 canonicalSubject 明文。索引用现有 HMAC 与持久版本锚点；确需保存精确 Scope 的受保护内容通过显式加密 Port，默认 unavailable，不加测试密钥。保护/保留期方案待确认，禁止自动清理 Inbox/历史或声称可永久保存敏感原文。日志/Outbox 仅稳定资源 ID、原因与摘要。

## 事实接入及重放规则

- 入站 sourceVerified 不接受客户端 bool。Port 返回经过 issuer/tenant/source/订单/主体/组织店铺绑定验证的不可伪造应用对象；默认拒绝。外部 I/O 必须在事务外；锁后复核其有效期限、范围、固定制品及许可序号。
- 同 eventId 同稳定内容回放原接收结果，但必须允许独立投影任务继续；换 eventId 同 businessRevision 比较完整历史，不能只比金额或只比最新修订。历史不可用保持 pending/重试，禁止当 NotSeen。
- 第一次收退款而缺订单，保留累计退款与事实锚点等待补全，不把负净额改零，不虚构 settledAt。后续完整高修订必须覆盖该退款；低修订订单不能把已知退款擦除。
- 更高可信修订非法金额/币种/状态或同修订语义冲突，保存隔离与审计并触发所有已计数关系减计数；不能丢弃异常后继续对旧 READY 发资格。quarantine 不随新事件或定时任务自解。
- 订单 current 被隔离后不能只给“当前事件新 subject”派任务，应按订单索引找到所有已有 qualification 依赖。两个关系共享同一订单事实时各自绑定窗口/制品/组织店铺匹配独立评估，不复制一个 relation 的结论。
- 注册模式仍需绑定时权威新客快照及注册时间，不得拿订单 merger 的 UNKNOWN 新客结果冒充 YES。本轮若仅实现订单模式，注册必须明确 PENDING/未实现，完整目标不能标完成。

## 先事实后绑定与时间

首绑现有 BOUND Outbox 是补查触发点：新关系建立后按精确 invitee + 组织店铺 + 来源读取早期事实，生成同一 relation 重评任务。订单到达也生成 fanout/扫描任务。并发情况下，至少 BOUND 后补查或订单任务重扫能覆盖关系；不要依赖一次非锁定关系列表查询后将事件永久“处理完成”。用持久任务/扫描水位证明无丢失，并测试事件在绑定事务未提交时到达。

首次事实 receivedAt 由可信接入端最初接收时记录，不用本次投影时间、重试时间、退款到达时间或客户端时间。注册收到可早于绑定但不能早于注册事实；订单发生必须 >=boundAt。迟到宽限是显式计划值，缺值不猜默认。due 任务只改变观察成熟状态，不重写首次接收时间。

锁后用原始 Instant 裁决所有边界；datetime(6) 保存不应截断原始事实/截止而改变判断。推荐在规范证据/制品中保存原始精度字符串（或秒+纳秒），数据库时间列仅用于扫描索引，精确判断从原值恢复。V3 boundAt 已约定微秒，保持其原值；不要用截断后的外部有效期代替原始 Instant。

到期任务按 dueAt<=now 扫描候选，锁后重新读最新证据及资格修订；退款先于 due/并发 due 时只计当前净额。PENDING 不全是时间未到：缺源/历史/映射应使用 retryAt，不能伪造 dueAt。超过宽限为 REVIEW；保持可审计，不自改合格。已合格后退款会撤销当前有效计数，但关系和 everQualified 不删除，恢复不累计第二名“历史新客”。

## 最小文件范围

- application：ReferralEvidenceIntakeService、ReferralQualificationService、ReferralQualificationRepository、TrustedReferralEvidencePort、ReferralQualificationPermitPort（包含固定制品和会员/风险依据）、ReferralQualificationDueService。
- domain：持久资格/进度/投影任务及稳定事实键小类型；复用 SPI，不复制 evaluator/merger 算法。
- infrastructure：Unavailable 默认 Ports、MybatisReferralQualificationRepository、Mapper interface + XML、现有配置增加 beans；V4 新表迁移。
- 测试：纯边界与真实 MySQL 资格专项；只增加必要依赖。Delivery plan/status/QA/source digest/独立审查证据；不新增实际公网路由、调度默认启动、奖励调用。

## 必须覆盖的专项矩阵

| 场景 | 断言 |
| --- | --- |
| event 同键同内容/异内容、换 event 同 revision | 不重复写计数/Outbox；异内容隔离且保留原事实 |
| 低 revision 重放与历史非 latest 冲突 | 不回退累计退款/锚点；历史冲突仍隔离 |
| 更高非法 revision 后旧 READY 重放 | quarantine 不自解，原 counted 撤销一次 |
| 退款先到、pending refund、未知映射/新客/首单 | PENDING，零有效人数，无奖励写入 |
| 同 order 同 revision 换主体/组织/店铺 | 拒绝或隔离同一业务订单，不能成为新 Scope READY |
| 两活动关系共享主体/订单，不同 tenant/source 复用订单号 | 分别窗口校验，范围无串扰，不重复一个关系人数 |
| 证据在绑定前、绑定事务提交竞争、BOUND 重放 | 最终必有重评；receivedAt 不漂移 |
| 观察前/恰好成熟/达标截止/迟到宽限截止/纳秒 | 半开边界，due 可重放，原始精度判断 |
| due 与退款竞争、两关系同时合格、同关系双worker | participant 串行，计数精确且永不负，资格只一行 |
| ELIGIBLE→PENDING/INELIGIBLE→ELIGIBLE | 当前计数 -1/+1，everQualified 不重复，未生成 reward |
| Outbox/历史/计数写故障 | 相应短事务全部回滚，消息能重试，不留下成功 Inbox 或任务完成假象 |
| 任务租约过期/旧worker落后/较新revision到达 | fencing 拒旧完成；新水位不丢失 |
| 权威 Port 不可用/异常/锁后过期 | 默认关闭且脱敏，事务内零网络调用 |

先写纯测再集中一次真实 MySQL 专项；当前文档不构成 DB 已通过、共享 V4 已应用或生产资格验收。
