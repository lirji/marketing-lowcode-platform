# 可验证验收标准与生产准入 v2

状态：验收计划，所有执行状态均为NOT_RUN。设计文档完成不等于以下项目已通过；外部模拟测试不能替代双方联调、真实容量和灾备验证。

## 1. 验收层次和证据格式

| 层级 | 执行者/环境 | 交付证据 |
|---|---|---|
| L1领域规则 | 开发/本地与CI | 确定性用例、边界/属性测试、失败反例 |
| L2数据库并发 | 开发/真实MySQL测试库 | 迁移日志、并发结果、唯一键/守恒查询、锁等待 |
| L3合同 | 开发+外部系统/双方测试环境 | provider与consumer合同测试、schema digest、golden fixtures |
| L4全链路 | QA/正式测试集群 | BFF登录到真实券账户，再退款的trace、数据库和外部订单对账 |
| L5容量与故障 | SRE+QA/类生产多AZ | 压测原始数据、硬件/拓扑、负载脚本、故障时间线、恢复对账 |
| L6生产试点 | 发布负责人/获准的生产活动 | 灰度观察、实际SLO/差异、回滚演练记录 |

每条验收记录包含：testId、commit SHA、镜像digest、配置digest、合同版本、环境、数据集规模、随机种子、开始/结束时间、期望/实际、trace与报告路径、PASS/FAIL/BLOCKED。缺少外部合同标BLOCKED，不能记PASS；本轮均NOT_RUN。

预期报告目录：`docs/plans/referral-campaign-20260907/evidence/<run-id>/`（执行时新增）。报告可引用CI制品，避免把敏感payload提交Git。

## 2. 功能、并发与账本测试矩阵

| ID | 操作/故障注入 | 可机器断言的结果 |
|---|---|---|
| AC01 | 同活动A/C邀请B，1000并发不同幂等键 | relation按tenant+campaign+invitee恰好1行；角色记录一致；其他返回原关系或409，不出现两份归因 |
| AC02 | A→B和B→A同时绑定 | 角色占用规则最多允许一个方向；无死锁遗留PROCESSING |
| AC03 | 同token接口响应丢失后重试 | 24h内同键解密得到同token；token表无明文，日志不含token |
| AC04 | 同订单事件100次重放、另换100个eventId同revision | qualified仅计一次，逐人双边各一reward，配额各占1 |
| AC05 | 同revision不同payload、旧revision覆盖尝试 | 冲突隔离；新值不被旧版本覆盖 |
| AC06 | 退款rev8先到、结算rev7后到 | 净额按rev8；不误发全额达标奖励 |
| AC07 | 订单事实先到再绑定；事件时间在窗口外 | 合法窗口才能达标；消息先后不是唯一判断依据 |
| AC08 | 观察期到期时杀worker，再启动3个扫描器 | 只确认一次资格；due任务恢复完成；旧lease写入失败 |
| AC09 | 2人基础上并发3个有效邀请 | valid_count=5；3/5档各1reward；无遗漏档位 |
| AC10 | 5人→退款降至2人→再次升至5人 | 对应阶梯进入取消/追回；同milestone不新建第二reward |
| AC11 | reward同一身份在规则发布v2后重复计算 | 旧participant仍v1；不重置归因/限领/档位身份 |
| AC12 | bucket剩1名额，1000并发不同reward | 最多1个预占成功；allocated守恒，其他WAIT_QUOTA或耗尽 |
| AC13 | bucket调拨同时旧worker提交 | epoch失败后安全重试；Σallocated≤campaign限额，所有桶守恒 |
| AC14 | 用户上限1，多个桶/请求并发 | 用户quota总占用≤1；不能用换bucket绕过限领 |
| AC15 | TX1提交前后、confirm响应前后、TX3前后逐点杀进程 | 没有重复授权、丢outbox、永久不可追踪reward；恢复后全部落到明确状态 |
| AC16 | 风控REJECT、REVIEW、UNAVAILABLE | REJECT不换键绕过；REVIEW等可信结果；UNAVAILABLE退避恢复，旧4b路径仍原首次结果 |
| AC17 | 凭证过期重签、篡改SKU/受益人/租户/数量 | 同reward合法重签返回同意图；篡改100%拒绝 |
| AC18 | 确认授权与退款并发 | 取消先发生则首次确认拒绝；确认先发生则补偿可追踪；不能无记录释放 |
| AC19 | 外部已受理但提交HTTP超时 | 按同source查询/重试恰好一个外部订单；本地UNKNOWN可恢复 |
| AC20 | 外部查404时原提交仍在途，先发送取消 | 外部墓碑阻断迟到创建；不支持协议则验收BLOCKED，不当取消成功 |
| AC21 | 成功回调与取消交叉、成功回调重复/旧revision | 保留真实成功及补偿；未解决补偿不被清空 |
| AC22 | 双边一方发券失败 | 另一方成功保留；quota与两份状态各自一致 |
| AC23 | 券已核销后退款 | MANUAL_REVIEW和原因完整；不能伪记REVERSED/可用额度恢复 |
| AC24 | 从源重放全部统计事件及资格反转 | 绑定distinct数、当前有效人数、成功数与权威表重建一致，成本不双计 |
| AC25 | kill switch丢消息、旧激活指令、重启 | reconcile生效；运行许可过期停止新授权；旧sequence不生效 |
| AC26 | 旧STANDARD、OfferToken、Journey普通流程 | 原回归通过；新来源未影响旧assembler与状态语义 |
| AC27 | 越权tenant/org/shop、伪造BFF断言、客户端伪造订单源 | 越权操作0；错误响应不泄露其他用户存在性/主体 |
| AC28 | 客户主体合并且两账户有奖励记录 | 暂停受影响未完成资格并REVIEW，不自动生成新限额/第二奖励 |

所有AC01–AC28同时检查最终数据库与外部账本，不只检查HTTP状态码。race用屏障/可控时钟/故障点触发，避免靠sleep碰运气。

## 3. 数据库和注释验收

- 所有新表、新字段COMMENT覆盖率100%；空白/TODO注释失败。检查范围包括角色、配额桶、任务、授权receipt、取消墓碑、API幂等、measurement投影和source授权表。
- DATABASE_DESIGN每一表/列映射到迁移；字段单位、状态、时区和安全含义与DTO一致。示例DDL不能当全部迁移通过证据。
- 在真实MySQL跑新库完整迁移和旧库升级迁移；检查所有索引可创建、CHECK生效、NOT NULL唯一键、UTC微秒时间往返。旧迁移checksum不改变。
- SQL性能证据：绑定/主体关系/任务扫描/奖励列表/配额更新都有执行计划；无大OFFSET、无全表热路径扫描、无跨库事务。锁等待和死锁重试次数有指标。
- 代码新增类/接口/record/enum及公共方法Javadoc覆盖100%；关键并发、错误处理、状态分支中文说明。抽查能回答为何不重发、何时释放、谁是权威、超时为何UNKNOWN；只复述方法名不合格。
- 不以注释掩盖逻辑缺陷；属性测试验证资格转移、配额守恒和幂等。重要分支通过故障注入验证，测试不能只复制实现表达式。

## 4. 容量验收步骤

使用PRODUCTION_DESIGN的目标包络；不混用CDN请求量、HTTP受理量、资格处理量和真实到账量。报告各层吞吐和端到端trace。

1. 数据预装：100租户、1万活动、1亿历史关系、1千万活跃关系；至少一个百万关系活动；源事件包含重复、退款、风险和冷热分布。可另做小规模CI，不得用小数据报告替代规模验收。
2. 预热30分钟，测量JIT/缓存/连接池稳定；记录缓存命中、DB行长和索引大小。禁用把所有业务结果直接缓存为成功的测试替身。
3. 单cell容量阶梯：25%/50%/75%/100%目标各15min，输出p50/p95/p99、goodput、CPU、内存、GC、DB连接/锁/磁盘、Kafka最老消息年龄、外部限流。
4. 区域正常负载持续4h，各类负载同时施加；所有正常延迟满足生产设计，5xx+超时+包络内429≤0.05%，正确性错误0；报表水位≤60s。
5. 3倍突发10min：可控拒绝明确返回429/503，已受理事实不丢；不存在无限内存队列。恢复正常后30min内消除本次积压，最老消息年龄回到正常阈值。
6. 热点组：单token5k/s；同一invitee千并发；50%活动热点；50%邀请人热点。后两项超容量时热点允许排队/拒绝，无关租户p99恶化≤20%，错误率仍满足正常目标。
7. 外部限流降至约定吞吐50%、持续15min，验证bulkhead与积压；恢复后消费服务率大于入站，按公式推导并实测排空时间。

硬件型号/核数/内存、存储IOPS、网络、DB复制方式、Kafka分区/副本、最大连接和副本数必须记录。单cell通过不能直接乘8宣称区域通过；共享网关、外部权益、风控和基础设施可能成为瓶颈。

## 5. 高可用与故障演练

| 故障 | 注入条件 | 验收 |
|---|---|---|
| 单API/worker Pod终止 | 100%正常负载 | 自动接管；无数据错误；操作可幂等重试 |
| 一个AZ下线 | 正常负载，多AZ真实部署 | 新流量恢复目标≤120s；剩余容量足够；记录实际受影响时间而非称零中断 |
| MySQL主故障 | TX边界随机故障 | fence旧主；已确认写的RPO=0目标经校验；达不到即该目标FAIL |
| Redis全不可用10min | 私有查询+绑定同时 | 受控回源/限流；权限与资格不降为默认通过；DB不被击穿 |
| Kafka一个broker下线 | ISR仍≥2 | 事件连续可追踪；acks all正常或明确重试 |
| Kafka无法满足ISR | 发布失败持续5min | outbox保留；不能当发布成功；达到接入阈值明确拒绝 |
| 风控全超时10min | 奖励持续产生 | 暂停新许可，其他API不被拖垮，恢复无重复实发 |
| 权益中心提交/回调中断15min | 包含在途成功请求 | UNKNOWN+按source对账；未确认不释放名额 |
| 错误签名/毒消息 | 混入正常事件流 | 隔离且报警，不阻塞全部租户/无限重试 |
| 区域失效 | 真实灾备演练 | 旧主隔离、新epoch唯一写；RTO≤30min、RPO≤5min目标实测；缺口对账补齐 |
| 完整备份恢复 | 独立测试集群 | 归因/奖励唯一与配额守恒，外部订单对账后才能恢复发奖 |

演练只能在批准的测试/预生产或明确授权的生产范围执行。本轮仅提供计划，没有终止Pod、访问外部账本或变更infra。

## 6. 对账断言

每次功能/故障验收导出以下计数与差异清单，必须能追到rewardId：

1. 每个tenant+campaign+invitee归因数量≤1；每个稳定milestone奖励数量≤1。
2. progress.valid_count等于当前qualified_flag=true的关系数量；历史首次人数按first_qualified_at非空distinct关系计算。
3. 每bucket allocated=available+reserved+consumed；全bucket allocated之和≤总额度；用户reserved+consumed≤个人上限。
4. 每份有效reservation对应唯一reward；成功reward有外部成功证据和consumed；未提交取消有释放证据；UNKNOWN保持占用。
5. 外部同tenant/source/request订单≤1；本地SENT/ACCEPTED不能当SUCCEEDED；每个外部成功均有可定位本地授权。
6. INVALIDATED且已成功的reward存在已完成或待处理补偿；待处理有owner/下次重试或人工原因，不静默消失。
7. inbox/outbox归档前事件可重放且不产生额外权益；符合保留策略的旧重放被明确拒绝或摘要去重。

正常对账周期建议5min增量、每日全量抽样+按时间片完整覆盖；正确性差异>0立即报警和发奖暂停评估。测试断言用权威表及外部查询计算，不依赖可能滞后的仪表盘数字。

## 7. 发布阻断条件与交付定义

以下任一项未通过即不允许对应活动上线：C01身份未签收；首单模式C03证据未签收；C06幂等/SKU版本/最终结果未验证；退款政策需要取消栅栏但外部不支持；任何重复实发/越权/超配额；注释覆盖缺失；无可恢复的授权/UNKNOWN处理；多AZ/容量目标无实测证据。

可按活动范围发布注册模式，不启用缺少首单合同的模式；这必须在能力注册表和UI禁用体现，不能把不支持配置保存成功。

开发完成定义：新模块编译、合同/领域/并发/旧回归通过、注释门禁通过、迁移升级通过、配置文档同步、Cursor接口交付。生产完成定义另加外部合同签收、真实端到端、容量/故障/对账证据、on-call运行手册和试点观察。

## 8. 实施阶段与测试命令映射

沿用FINAL_PLAN P0–P7，但增加：P0冻结C01–C07；P1完成运行许可与能力registry；P2角色原子锁和token响应加密；P3配额桶+用户上限；P4receipt恢复与取消墓碑；P5正交终态对账；P7高容量/多AZ演练。

现有命令（未来实施验证时运行）：

```sh
./mvnw --batch-mode --no-transfer-progress verify
./scripts/verify-contracts.sh
./scripts/verify-mysql-migrations.sh
./scripts/verify-deployment.sh
```

拟新增脚本/测试：`scripts/verify-schema-comments.sh`、`scripts/verify-java-docs.sh`、`scripts/local-referral-smoke.py`、`tests/performance/referral-ingest.js`、`tests/performance/referral-hotspot.js`、`tests/performance/referral-fault-recovery.js`；CI矩阵把新模块、注释、外部contract fixtures和故障回归纳入报告。脚本尚未实现，不得在最终结果中列为“已通过”。

排期需重新估算：v2新增了分桶、角色互斥、持久授权恢复、取消栅栏、规模测试和AZ演练，FINAL_PLAN原30–45后端人日不再作为完整v2估算；完成P0、明确复用程度及外部改造后重新给出工作量。
