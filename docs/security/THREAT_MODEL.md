# 威胁模型

## 资产与攻击者

关键资产包括营销预算/库存/券、价格结果、发布签名 key、个人画像和 consent、Campaign/Terms、订单关联、provider credentials、审计与恢复数据。攻击者可能是未登录外部用户、越权商家、恶意运营、被攻陷 connector、供应链依赖、跨租户邻居或能重放网络消息的参与者。

## 主要威胁与控制

| Threat / abuse case | Prevent | Detect / recover |
| --- | --- | --- |
| 伪造 tenant/org/shop | JWT claim + scope ABAC；全层 tenant key | tenant escape tests、审计、异常 403 指标 |
| 盗用/重放 OfferToken | Ed25519/KMS 签名、context digest、expiry、nonce | nonce/command unique、拒绝原因、账本对账 |
| 越权提高优惠或资金 | 审批分离、risk gate、kill switch 只收紧 | definition/manifest diff、审计、异常补贴告警 |
| 规则炸弹/RCE | 方言白名单、静态 cost、禁反射脚本、沙箱禁网 | compile timeout/quota、恶意 corpus、worker recycle |
| Artifact/Manifest 篡改 | content address + signature + ABI/tenant verify | signature failure page；继续 last-known-good |
| SSRF/内网扫描 | connector type registry、egress allowlist、DNS/IP revalidation | 网络流量告警、connector audit |
| Provider callback 伪造 | HMAC/mTLS、timestamp/nonce、body digest | receipt dedupe、UNKNOWN reconciliation |
| 重复发券/触达 | 稳定 effect idempotency key、provider query-before-retry | duplicate counters、ledger/contact reconciliation |
| 预算并发超发 | DB conditional update、fencing/escrow、不可变 ledger | 高频 reconciliation；非零即冻结 |
| Kafka poison/replay | schema/size/rate auth、eventId dedupe、tenant DLQ | quarantine、受审批限速 replay |
| PII 从日志/trace 泄露 | schema redaction、tokenization、attribute allowlist | DLP scan、访问审计、TTL/delete jobs |
| 恶意依赖/镜像 | lockfile、SBOM、SAST/SCA、provenance、digest admission | 定期重扫、快速 rebuild/rollback |
| OTel 后端拖垮业务 | async bounded queue、sampling、memory limiter | drop/exporter metrics；业务线程隔离 |
| Region split-brain | 单 writer lease + fencing epoch、Benefit fail closed | epoch/ledger anomaly、人工受控 failover |
| 备份勒索/篡改 | 跨账号 immutable encrypted backup | 定期隔离 restore + checksum/signature verify |

## 滥用场景

运营可以合法创建规则但恶意圈选敏感人群、隐藏个性化、把平台资金转给自有店、在静默时段轰炸用户，或通过大量高成本图造成资源耗尽。因此授权不能只看“可编辑”：字段目的限制、商家 opt-in、Funding owner、TermsSnapshot、个性化标识/通用路径、cost quota 和独立审批都在发布门禁。

商家可以猜测其他店铺 Campaign ID 或借共享 Audience 泄漏规模。所有列表/详情/导出都按 scope 查询，count 使用阈值/差分隐私策略（由合规配置），小群体抽样默认不展示原始成员。

## Fail-safe 决策

- 无法验签、未知 key/ABI/tenant：拒绝加载/履约。
- Audience 陈旧：按字段 policy 拒绝或明确走 non-personalized/default，不自行假设 matched。
- Provider timeout：UNKNOWN，不当失败盲重试，也不当成功。
- Benefit writer/fencing 不确定：停止新 reserve；已 confirm/refund 按事故策略处理。
- 分析/OTel 故障：降级查询与遥测，不阻断已验证在线决策。
- 控制面/S3 故障：继续 last-known-good，不发布新 generation。

## 验证计划

每次 major release 执行 tenant escape、JWT claim confusion、OfferToken mutation/replay、compiler fuzz/rule bomb、SSRF、callback replay、concurrent oversell、Kafka poison、Flink recovery、PII log scan、dependency compromise 和 DR split-brain tabletop。生产准入需要安全 owner 签署残余风险，本文不是一次性完成的证明。
