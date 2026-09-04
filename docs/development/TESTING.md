# 开发与测试指南

## 本地门禁

```bash
./scripts/verify.sh --fast
./scripts/verify.sh --full
./scripts/verify-deployment.sh
```

Java 使用 Maven reactor、JUnit 5、jqwik、ArchUnit；前端使用 ESLint、Vitest、Testing Library、Playwright；契约由 OpenAPI/AsyncAPI/JSON Schema 保持机器可读。测试应使用固定 Clock/seed/version，禁止依赖本机时区或 map 顺序。

## 测试金字塔

- Domain unit/property：状态机、金额守恒、稳定分桶、幂等、方言约束。
- Adapter contract：Drools/DMN、provider callback、repository/tenant、Manifest/OfferToken。
- Service integration：HTTP security、Flyway、事务/重复 command、Problem Details。
- Stream reducer：重复/乱序/迟到/timer/correction，之后做 Kafka/checkpoint recovery。
- UI component：设计器语义、validation、错误/empty/loading 和权限。
- E2E：桌面/移动关键工作流、发布/查询/恢复。
- Full stack/chaos/load：Compose 或临时集群，验证真实依赖和运行门禁。

## 新能力的 Definition of Done

领域术语/owner/invariant 明确；契约和错误码更新；tenant/permission/idempotency/PII/failure semantics 有测试；正常、边界、重复、乱序、超时和恢复路径覆盖；指标/日志/trace/runbook 更新；migration 可 N/N-1；文档和 CI 同步。只写 happy-path Controller 不算完成。

## 时间与并发

业务代码注入 Clock；事件同时保留 occurredAt/receivedAt，watermark 明确迟到策略。并发测试必须验证最终 invariant，而不仅是 HTTP 200。对资金资源进行 history/model check：随机 reserve/confirm/cancel/refund/reverse 后重算流水，重复 command 不改变结果。

## Flink

operator UID 稳定；state serializer 变更需兼容测试；source 可重放、sink transactional/idempotent 才能声称端到端 exactly-once。至少测试 checkpoint restore、kill/rebalance、poison event→DLQ、迟到 correction 和外部 effect 去重。

## 缺陷提交证据

记录最小复现、expected/actual、tenant/generation/schema、requestId/eventId（脱敏）、日志/trace、测试和修复后的回归命令。金额/身份/签名缺陷应加 property 或 adversarial case，防止只修单一样例。
