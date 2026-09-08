# Marketing Platform Helm chart

该 Chart 只部署 10 个 Java 服务、控制台及可选的 3 个 Flink 作业。生产必须使用外部高可用 MySQL、Kafka、Redis、S3、ClickHouse、OIDC 和 OTel；Chart 不会偷偷创建单节点数据组件。

先创建由密钥平台同步的 Secret（默认名 `marketing-platform-secrets`）。每个有数据库的服务都必须提供四个独立键：`<service>-database-username`、`<service>-database-password`、`<service>-migration-username`、`<service>-migration-password`；`<service>` 依次为 `marketing-control-service`、`rule-compiler-worker`、`audience-service`、`offer-decision-service`、`benefit-funding-service`、`event-gateway-service`、`journey-service`、`engagement-service`、`measurement-service`。运行账号只授予自身 schema 的 DML，迁移账号只在部署迁移窗口获得自身 schema 的 DDL，严禁复用 MySQL root 或跨上下文账号。

同一 Secret 还需包含 `kafka-sasl-jaas-config`、`offer-signing-key-id`、`offer-signing-private-key-base64`、`offer-signing-public-key-base64`、`release-signing-key-id`、`release-signing-private-key-base64`、`release-signing-public-key-base64`、`compiler-signing-key-id`、`compiler-signing-private-key-base64`、`compiler-signing-public-key-base64`、`runtime-ack-signing-key-id`、`runtime-ack-signing-private-key-base64`、`runtime-ack-signing-public-key-base64`、`routing-secret`、`provider-hmac-secret` 和 `risk-platform-bearer-token`。风控 token 必须属于营销服务账号、面向 risk evaluation audience，并具有 `risk.evaluate` 权限。轮换窗口可选提供 `offer-trusted-public-keys`、`release-trusted-public-keys`、`compiler-trusted-public-keys`、`runtime-ack-trusted-public-keys`，格式为逗号、分号或换行分隔的 `key-id=base64-x509-public-key`。先发布包含新旧公钥的信任集，再切换签名私钥，最后在所有旧制品与 token 过期后移除旧公钥。

签名私钥建议由 KMS/HSM adapter 替换文件型私钥；当前 Secret 接口用于 production-candidate 集成。不要把明文 values 提交到 Git。模板按职责只向 Compiler 注入 compiler 私钥、向 Control 注入 release 私钥、向 Decision 注入 offer 与 runtime ACK 私钥；Control 与 Decision 只获得 compiler 公钥，Control 只获得 runtime ACK 公钥，Benefit 只获得 offer 公钥。

```bash
helm lint deploy/helm/marketing-platform
helm template marketing deploy/helm/marketing-platform \
  -f deploy/helm/marketing-platform/values-production.example.yaml >/tmp/marketing-rendered.yaml
helm upgrade --install marketing deploy/helm/marketing-platform \
  --namespace marketing --create-namespace \
  -f values-production.yaml --atomic --timeout 15m
```

启用 `flink.enabled` 前需安装 Apache Flink Kubernetes Operator 和 S3 filesystem 插件，并为 `marketing-flink` ServiceAccount 配置工作负载身份。镜像标签必须不可变；正式发布建议在准入控制器中进一步要求 digest。

生产 Journey 固定使用 `STREAM`：`POST /api/v1/events` 接受并通过 outbox 投递 `JOURNEY_SIGNAL`，Flink 是唯一状态写入者，Journey Service 仅物化 `mk.journey.output.v1`。同步 enrollment/signal/migrate 接口只保留给 DIRECT/DEV 测试，OIDC 部署若误配 DIRECT 会拒绝启动。

生产示例会启用业务指标 HPA；集群必须先由 Prometheus Adapter 暴露
`marketing_http_server_p99_milliseconds`、`hikaricp_connections_pending`、
`marketing_outbox_pending`、`marketing_outbox_oldest_age_seconds` 和 `marketing_kafka_consumer_lag`。指标缺失时 HPA 仍可按 CPU/内存扩容，
但会阻止安全缩容，因此发布准入必须执行 `kubectl describe hpa` 并确认所有 target 都有当前值。

## 可选裂变服务（尚未生产验收）

`services.referral-service.enabled` 默认 `false`，旧服务默认渲染保持一致。显式启用才创建内部 Service/Deployment 等资源，不增加网关或公网 Ingress。新服务读取四个独立 Secret 键：`referral-service-database-username`、`referral-service-database-password`、`referral-service-migration-username`、`referral-service-migration-password`，运行与迁移账号分离。数据库/账号尚未由旧九库初始化脚本创建，不能直接以该脚本成功作为裂变库部署证据。

网络策略启用时，新服务只接受同 release 平台 Pod 的内部端口流量；旧 Ingress 放行规则排除它，避免 NetworkPolicy 叠加越权。此处是模型约束，真实集群网络、监控抓取、身份/许可/KMS及容量仍须单独验收。固定 OIDC、禁止 DEV 头，业务来源配置仍默认拒绝。

可先离线审阅 `helm template marketing deploy/helm/marketing-platform --set services.referral-service.enabled=true`。该命令只渲染，不部署。详细证据见 `docs/delivery/referral-packaging/`。
