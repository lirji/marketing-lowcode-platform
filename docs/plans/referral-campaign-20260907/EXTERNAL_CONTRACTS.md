# 外部合同与内部跨服务合同 v2

状态：可供双方实现和测试的目标合同，尚未获得外部系统签收。标记EXISTING的路径从当前仓库调用代码核实，PROPOSED为本次建议接口；不能据此认定外部已上线。API所有者需要确认字段、错误码、性能、保留期和版本后，生成OpenAPI/AsyncAPI/JSON Schema并锁定版本。

跨仓库核验补充见[CROSS_PROJECT_CAPABILITY_AUDIT.md](CROSS_PROJECT_CAPABILITY_AUDIT.md)：权益中心按来源查询、履约事件和按item补偿已有代码，复用真实路径；SKU提交版本约束、订单不存在时的取消栅栏仍属新增。所有运行时合同测试/生产签收仍待完成。

## 1. 通用规则

HTTP JSON，UTF-8；时间RFC3339 UTC（最多6位小数），金额int64最小货币单位，币种ISO三字码。ID最大64或128字符按表格定义，不允许空白/控制符；revision int64正整数。schemaVersion=`1.0`；新增可选字段兼容，移除/改意义用新major和新topic。拒绝未知枚举并隔离，不能回退成ALLOW/SUCCEEDED。

`Authorization`认证调用服务；`X-Tenant-Id`仅用于受信机器的租户路由，必须校验机器可访问租户，不能覆盖任意JWT租户。用户断言单独传`X-Subject-Assertion`。`traceparent`透传；本次新增业务写要求`Idempotency-Key`，同键不同业务内容409；既有events继续使用eventId等事件身份去重。body不允许直接改受益人、SKU、金额的入口必须使用`additionalProperties:false`。

响应错误结构：`{type,title,status,code,detail,traceId,retryable}`；复用项目Problem风格。禁止暴露内部SQL、token、主体原文。202只表示持久受理；200已存在结果或查询；201新建；400合同错误；401/403身份/范围错误；404不可见或不存在；409竞争/版本冲突；422永久业务不满足；429流控（Retry-After）；503依赖不可用。超时未收到响应是UNKNOWN，不等于服务端未执行。

## 2. 身份/BFF合同 C01（PROPOSED）

接口所有者：商城/会员BFF；营销验证adapter。BFF机器身份与用户断言必须同时通过。普通用户JWT由BFF验证，canonicalSubject来自会员映射，不能从前端body取subjectRef。

用户断言必需字段：`iss,aud='marketing-referral',sub,tenantId,organizationId,shopId,iat,exp,jti,method,path,bodyDigest`。sub最长256 Unicode字符；exp-iat≤60s；bodyDigest为规范JSON摘要；密钥JWKS/KMS分发且有轮换/撤销合同。相同jti只能重放同operation+业务幂等键+bodyDigest；未完成网络重试不因jti重复被误判新的恶意请求。

参与API body：

```json
{"campaignId":"cmp-referral-01"}
```

POST `/internal/v1/referral/participants` →201 `{participantId,definitionVersion,generation,state}`；已有同主体参与→200原固定版本。POST `/internal/v1/referral/invite-tokens` body `{participantId}`→201 `{tokenId,inviteToken,expiresAt}`。POST `/internal/v1/referral/invites:resolve` body `{inviteToken}`→200 `{campaignId,title,publicTerms,termsVersion,maskedInviter,expiresAt}`，仅机器身份，无用户写操作。POST `/internal/v1/referral/bindings` body `{inviteToken,consentVersion}`→201 `{relationId,state,boundAt,deadlineAt,reasonCode}`。

GET `/internal/v1/referral/me?campaignId=...&cursor=...&limit=20` →200 `{participant?,invitedBy?,progress,rewards,nextCursor,asOf,revision}`；由断言sub决定范围。limit=1..100，响应不含好友完整subject。没有加入返回空业务状态，不假装错误发奖。

绑定错误：TOKEN_INVALID/TOKEN_EXPIRED/TOKEN_REVOKED、CAMPAIGN_NOT_ACTIVE、TERMS_VERSION_MISMATCH、SELF_INVITATION、SUBJECT_ROLE_CONFLICT、REFERRAL_ALREADY_BOUND、TENANT_SCOPE_MISMATCH。resolve响应不得用于资格认证；匿名访问及分享点击只能进入分析，不产生奖励。

## 3. 会员证据 C02（PROPOSED）

目标POST `/internal/v1/customer-eligibility:query`，使用请求体传主体以避免敏感主体进入URL/访问日志；本操作只读，不产生新客资格，adapter统一`CustomerEvidenceGateway`。现有外部服务若采用其他路径，在adapter映射并以双方签收版本为准。

请求：认证tenant、canonicalSubject、asOf、policyCode、policyVersion。响应必需：`canonicalSubject,registeredAt,customerRevision,eligibilityPolicyVersion,isNewCustomer,evaluatedAsOf,evidenceId,status`；status=READY/PENDING/MERGED/NOT_FOUND。若READY，isNewCustomer是会员系统按指定口径的权威结果；PENDING不带伪false默认值。

CUSTOMER_REGISTERED事实包含注册时间和修订；客户合并事件包含oldSubject/newSubject、mergeRevision、effectiveAt。首期合并触发相关未完成关系REVIEW并暂停奖励，人工/规则化迁移完成前不自动合并两份限领额度。正常新客判断不依赖“本地查不到历史订单”。

目标延迟p99≤300ms；并发和配额按活动事实速率签收。允许按主体批量查询≤100减少N+1；不存在/超时/PENDING不能转为默认新客。

## 4. 订单与退款 C03（PROPOSED）

目标GET `/internal/v1/orders/{orderId}/referral-evidence`；另新增目标POST `/internal/v1/order-referral-evidence:query`，body为`{canonicalSubject,from,to,firstOrderPolicyVersion}`，返回`{state:READY|PENDING|NOT_FOUND,evidence?:订单快照}`，查询指定期间内的权威首笔有效订单，避免事件缺失时无法找回。两个端口使用相同快照schema，事实与查询可相互校验。

| 字段 | 类型/必需 | 语义 |
|---|---|---|
| orderId、canonicalSubject、organizationId、shopId | string/是 | 业务订单和范围，租户来自验证上下文 |
| orderRevision | int64/是 | 同订单所有结算/退款共享的单调修订 |
| isFirstEligibleOrder、firstOrderPolicyVersion | boolean/string/是 | 权威首单口径与版本 |
| settledAt | timestamp/结算时是 | 订单结算时间，不能用事件接收时间替代 |
| grossEligibleMinor、cumulativeRefundMinor | int64/是 | 可计入总额与已确认累计退款，0≤退款≤总额 |
| netEligibleMinor、currency | int64/string/是 | 净额=总额-累计退款；不一致拒绝证据 |
| state | enum/是 | PENDING/SETTLED/CANCELLED/REFUNDED |
| snapshotAt、evidenceId | timestamp/string/是 | 查询水位与审计依据 |

标准事件采用累计快照，而非不可恢复的单次金额增量。源系统若只能发refund增量，adapter必须按refundId持久去重并查询累计快照后推进资格。订单rev8退款先到、rev7结算后到时保留rev8，不能回到rev7全额资格。

事件示例：

```json
{
  "eventId":"order-100-rev-8",
  "tenantId":"tenant-a",
  "sourceSystem":"commerce",
  "schemaVersion":"1.0",
  "eventType":"ORDER_REFUNDED",
  "aggregateId":"order-100",
  "aggregateRevision":8,
  "occurredAt":"2026-09-07T10:00:00Z",
  "traceId":"trace-100",
  "payload":{
    "orderId":"order-100","canonicalSubject":"customer-b",
    "organizationId":"org-a","shopId":"shop-a",
    "orderRevision":8,"isFirstEligibleOrder":true,"firstOrderPolicyVersion":"first-order-v1",
    "settledAt":"2026-09-06T10:00:00Z","grossEligibleMinor":10000,
    "cumulativeRefundMinor":8000,"netEligibleMinor":2000,"currency":"CNY",
    "state":"SETTLED","snapshotAt":"2026-09-07T10:00:00Z","evidenceId":"ev-100-8"
  }
}
```

此时退款后净额2000，若门槛5000则资格失效；订单仍可处于部分退款后的SETTLED。同revision不同payload为EVIDENCE_REVISION_CONFLICT，进入隔离，不任选一个。

## 5. 风控 C04（PROPOSED，新场景）

新增目标POST `/internal/v1/risk/referral-evaluations`，旧交易评估接口不改。request：`evaluationAttemptId,rewardId,scene,inviterKey,inviteeKey,beneficiaryKey,campaignId,ruleVersion,skuId,skuVersion,benefitType='COUPON',quantity=1,customerEvidenceRef,orderEvidenceRef,deviceEvidenceRef?,requestedAt`。deviceEvidenceRef引用可信BFF证据，禁止客户端自报即信任。

response：`decisionId,decisionRevision,action,reasonCodes,validUntil,caseRef?,reevaluationAllowed`。action=ALLOW/REJECT/REVIEW/CHALLENGE；技术不可用由HTTP503/超时表达，客户端保存UNAVAILABLE。caseRef不是通过凭证；解除需可信案件结果revision+再次评估。

相同evaluationAttemptId同内容返回相同结果；不同内容409。重评使用新attemptId，但rewardId/sourceRequestId固定。REJECT除明确可信撤销决策外不能自动重试绕过；UNAVAILABLE可自动退避；REVIEW/CHALLENGE等待案件事实。ALLOW超过validUntil还未confirm需复评。

目标p99≤800ms；按tenant/provider限并发。风控来源、失败率、延迟独立监控，超时策略与生产设计一致。外部若不支持该场景，原account+amount接口不能冒充具备设备关联反作弊。

## 6. 奖励授权 C05（本仓库拟新增）

POST `/internal/v1/referral-award-intents`（benefit）：body `{sourceRequestId,authorizationToken}`；需要机器权限`referral:award-submit`及允许的sourceSystem绑定。sourceSystem服务端固定marketing-referral，正式发放只允许CENTER。

稳定幂等摘要字段：tenant、sourceSystem、sourceRequestId、rewardId、campaign、participant、beneficiary、role、ruleId、milestone、definitionId/version、artifactHash、SKU/version、quantity；**不包含**issuedAt/expiresAt/signature、当前evidenceRevision、evaluationAttemptId。这些可变字段仍必须被候选签名覆盖并按当前资格验证，但不能让合法重签触发业务幂等冲突。已经CONFIRMED的receipt不因后续revision重签创建第二份。

POST `/internal/v1/referral/rewards/{rewardId}:confirm-authorization`（referral）：request `{sourceRequestId,qualificationRevision,stableClaimsHash,riskDecisionId,riskDecisionRevision}`；只有benefit身份可调用。202/200响应 `{rewardId,sourceRequestId,authorizationSequence,confirmedAt,stableClaimsHash,receiptSignature,keyId,state}`。同业务确认重放同receipt；旧资格首次确认409 QUALIFICATION_REVISION_STALE；无效409 REWARD_INVALIDATED；暂停409 AWARD_PAUSED；同键不同摘要409 AWARD_IDEMPOTENCY_CONFLICT。

GET `/internal/v1/referral/runtime/proofs/{artifactId}`：返回编译制品、ReleaseManifest/签名及campaign/定义关联证明。调用端验签、sourceDigest、ABI和角色策略；并查询受信kill switch新鲜度。缓存固定制品允许长TTL，运行许可不允许随固定制品无限缓存。

新增GET `/internal/v1/referral/rewards/{rewardId}/authorization`（referral）和GET `/internal/v1/referral-award-intents/by-source?sourceRequestId=...`（benefit）用于恢复；认证tenant/source严格匹配。返回状态+receipt/intentId，不返回完整subject/私钥。调用方404只能说明当前查询无记录，不能证明在途请求未来不会创建。

新增POST `/internal/v1/referral-award-intents:cancel-by-source`（benefit），由referral补偿outbox调用，权限`referral:award-cancel`；body=`{rewardId,sourceRequestId,cancelRevision,evidenceRef,reason}`，Idempotency-Key按reward+cancelRevision确定。benefit核对来源、奖励关联与可信失效依据，在本地短事务保存cancel fence并阻止未投递意图，随后事务外执行C06外部取消。返回202 `{rewardId,cancelRevision,compensationState}`；未完成外部确认时仍为PENDING，不能承诺已撤回。先到取消、后到原奖励提交同样命中本地墓碑；即使本地relay已经在途，外部墓碑继续裁决。

## 7. 权益中心 C06

### 7.1 已存在提交路径与需签收的增量

EXISTING：当前`AwardIntentRelay`调用POST `/openapi/v1/award-orders`，期待HTTP202和awardOrderNo。当前assembler已有sourceSystem/sourceRequestId/subjectRef/items结构。新来源、SKU版本与取消/查询能力尚未核实。

增量要求：白名单允许marketing-referral；按(tenant,sourceSystem,sourceRequestId)幂等；sourceRequestId固定`referral:`+SHA256稳定身份摘要，且整串≤128字符。首期每个reward一个item且quantity=1；受益人由验证过的授权重建。SKU版本必须明确传递且外部按版本兑现，不得从“当前最新SKU”隐式替换已审批权益。

若外部现有schema无skuVersion，须升级合同或支持不可变版本SKU ID映射；不能仅把skuVersion塞trace字段就认为发放被冻结。coupon过期/暂停返回稳定业务失败，不换等价SKU（除非另有审批政策）。

### 7.2 终态查询（已有路径，响应需适配/补充）

复用真实GET `/openapi/v1/award-orders?sourceSystem=marketing-referral&sourceRequestId=...`。现有响应是`{orderNo,sourceSystem,sourceRequestId,sourceBusinessNo,recipientRef,status,homeCell,items}`，items已有skuVersion/walletEntryId等。营销adapter将其映射为内部规范视图；revision/lastUpdatedAt等目标字段须在权益中心补充后才能依赖，不把目标state枚举直接套入现有status。

404为当前查无记录；未知消息不释放配额。GET必须能按稳定source查询，即使调用方尚未取得awardOrderNo。终态记录查询期至少覆盖最长追回/对账窗口，初始要求400天或有等价归档查询。

### 7.3 取消与追回（PROPOSED，关键合同）

POST `/openapi/v1/award-orders:cancel-by-source`：`{sourceSystem,sourceRequestId,cancelRevision,reason,evidenceRef}`，幂等键`cancel:<稳定摘要>`。即使创建请求尚未到，权益中心也需持久化取消墓碑，之后相同sourceRequestId迟到创建必须拒绝；或提供等价可靠协议。

返回`{cancelRevision,state,fenceConfirmed,awardOrderNo?,reversalId?,reasonCode}`。state=CANCELLED/REVERSAL_PENDING/REVERSED/MANUAL_REVIEW。已成功可撤回券则REVERSED；已核销等不可撤回则MANUAL_REVIEW，不返回虚假的取消成功。

若取消和发放同到，外部同业务键原子裁决：取消获胜→不发；发放获胜→尝试撤回，返回实际结果。没有该协议的实现不得通过CANCEL_UNKNOWN测试，也不能用404+等待固定时间替代。

### 7.4 履约结果回流（PROPOSED）

统一事件`AWARD_FULFILLMENT_CHANGED`，必需字段：eventId、tenant、providerId、sourceSystem、sourceRequestId、awardOrderNo、itemId、skuId/version、beneficiaryDigest、deliveryState、compensationState、providerRevision、occurredAt、signature/keyId或经认证Kafka来源。

首选外部已有事件通道接入；没有则提供签名HTTP callback到benefit内部接口，主路径在P0固定，定期按source查询始终作为兜底。回调入箱/投影/outbox同事务；同eventId不同摘要409/隔离；旧revision200确认但不回退状态。不得通过任意公网请求伪造成功。

合同负载：正常2,000奖励/s区域容量需由权益中心签收；接受后到账p99≤60s为目标，不达标则调整全链路承诺。响应202不能计为真实到账吞吐。

## 8. 事件路由与测量 C07

`mk.referral.input.v1`接收C02/C03规范事实；Event Gateway认证principal→sourceId→factType白名单。消息键tenant+subjectKey；fan-out派生键tenant+relation，referral事实键tenant+participant。`mk.award.fulfillment.v1`键tenant+sourceSystem+sourceRequestId。

inbox按source+event去重，业务证据按aggregate+revision去重。DLQ带原引用、原因、首次/最后失败时间、重试次数，不暴露主体原文。Kafka分区增加会影响键落点，正常运行不依赖跨重分区顺序保证正确性，数据库revision/唯一键仍为最后裁决。

Measurement区分四类指标：链接解析次数（请求可去重）、独立绑定数（relation）、当前有效数（资格贡献可反转）、奖励成功数（reward）。链路重复发送不重复计数；退款反转引用原贡献。成本只由选定权威履约账本写入，奖励资格不是成本。

## 9. 合同验收与签收清单

每个C01–C07必须提供：owner及测试联系人、OpenAPI/AsyncAPI版本和digest、认证环境、SLA/限流、幂等范围/期限、完整状态枚举、时间/金额口径、兼容策略、错误示例、provider端测试结果、消费者合同测试结果。

必须交付的golden fixtures：正常注册、非新客、首单达标、部分退款仍达标、部分退款跌门槛、退款先到、重复/修订冲突、风控ALLOW/REJECT/REVIEW/UNAVAILABLE、权益已受理但提交超时、重复终态、取消先到、已核销不可追回、跨租户伪造。

当前签收状态全部PENDING（仅C06提交路径已在本仓库观察到调用）；待签收不会阻止编写adapter/契约测试，但会阻止宣称对应生产闭环已经通过。
