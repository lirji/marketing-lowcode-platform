# 告警处置索引

## MarketingServiceDown

确认是 scrape 网络问题还是 readiness/liveness 真失败。查看最近发布、Pod events、OOM/GC 和下游连接。单 Pod 故障由副本接管；多 Pod 同时失败时冻结发布并回滚最近 deployment/generation。恢复条件：所有期望副本 ready，5xx 和队列正常至少 15 分钟。

## MarketingHighErrorBurn

按 route、status、tenant、generation 拆分 5xx。客户端契约错误不应计入服务端 error budget；真实 5xx 先隔离新 generation/noisy tenant，再检查数据库池、Kafka 和线程池。错误预算快速燃烧时暂停非紧急变更。

## DecisionLatencySloBreach

检查 p50/p95/p99、候选数、artifact digest、CPU throttle、GC、HPA pending 和 Redis fallback。Decision 正常路径不应同步访问控制库或画像。若仅新 artifact 退化，停止灰度并回滚；不可扩大 solver deadline 造成级联排队。

## MarketingJvmHeapPressure

检查 live set、分配速率、缓存/制品 generation 数和 heap dump 安全位置。不要在生产日志或未加密工单上传含 PII 的 heap dump。先减流/扩容，再以受控方式获取诊断；连续 OOM 必须摘流。

## TelemetryExporterDroppingData

业务请求应继续。检查 Collector memory limiter、batch queue、Tempo/Loki 可用性和出口限速。确认 drop 指标可见且应用线程没有 exporter backpressure；不得无限扩大内存队列。

## FlinkJobRestarting

查看 Flink exception、最近成功 checkpoint、Kafka transaction timeout、schema 和 state serializer compatibility。禁止反复无状态重提作业。选择从 checkpoint 自动恢复或已验证 savepoint 回滚，恢复后观察 checkpoint、lag、DLQ、重复 effect 和 projection watermark。

## MarketingOutboxDeadLettered

立即定位 `job` 与 `outbox`，冻结对应外部副作用或发布推进。先核对 Kafka 可用性、ACL、目标 Topic 和 schema，再检查 dead-letter 记录的 payload hash、聚合序号与最后错误。修复后只能通过带审计的 replay 恢复；发券和触达 replay 前必须再次验证 commandId 幂等记录，禁止直接改 `published_at`。

## MarketingOutboxFailureBurst

检查 broker、DNS/TLS/SASL、producer timeout 和目标 Topic 分区。短暂故障由有界指数退避吸收；持续失败时限制入口并观察数据库 pending rows，避免 outbox 积压占满磁盘。不要通过无限增加重试或连接数掩盖故障。
