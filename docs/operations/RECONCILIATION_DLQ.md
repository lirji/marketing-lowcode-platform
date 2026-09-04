# 对账、DLQ 与安全回放

## 对账层次

1. 聚合内部：ResourceAccount balance 与 ledger 流水守恒。
2. 业务链：Decision quote → PromotionApplication → reservation → order confirm/refund → Grant/Contact。
3. 事件链：outbox 已发布、Kafka 已消费、ClickHouse projection 和 watermark。
4. 外部链：provider receipt、订单支付/退款、财务成本中心。

对账记录含 tenant、window、source watermarks、counts/amounts、difference、policy/version、started/completedAt 和 repair reference。只有“双方水位覆盖同一窗口”时差异才有意义。

## 差异分类

- `PROJECTION_LAG`：权威账本正确，投影未追平；补消费/重建。
- `DUPLICATE_SUPPRESSED`：重复事件被幂等层拒绝；记录但无需资金修复。
- `MISSING_EVENT`：账本有状态但 outbox/fact 缺失；从权威状态发 repair fact，保留原业务引用。
- `EXTERNAL_UNKNOWN`：provider/订单超时；query-before-retry，人工升级。
- `INVARIANT_BREACH`：权威余额/状态矛盾；立即冻结并按 SEV-1/2 处理。

修复必须是领域命令和成对修复流水，禁止直接 SQL 改最终余额或删除坏事件。

## DLQ 项目

每条 DLQ 保留原 envelope/payload（按原敏感等级加密）、topic/partition/offset、consumer/version、schema、tenant、error class、retryable、attempts、first/last failure、trace 和 checksum。查看默认脱敏且有 TTL。认证/tenant/signature 错误进入 quarantine，不允许普通 replay。

## Replay 流程

1. 创建工单，定义 tenant、原因、offset/eventId 范围、目的、owner、速率和停止阈值。
2. 修复代码/schema/config 并用原消息做单测；确认消费者幂等键未过期。
3. dry-run 到 shadow sink，比较预期状态/金额/副作用命令。
4. 独立审批后限速 replay；保留原 eventId、occurredAt 和业务幂等键。
5. 观察 lag、错误、外部副作用、资金 invariant 和水位；异常立即停止。
6. 完成后关联 replay manifest、处理结果和剩余项，不直接清空 DLQ。

营销触达和权益 replay 默认不重新执行已成功副作用；若无法证明 provider 是否成功，状态保持 UNKNOWN 并走外部查询/人工核对。
