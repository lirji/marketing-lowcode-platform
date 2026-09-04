# 发布、灰度、回滚与变更治理

## 发布对象

应用镜像、数据库 migration、Definition/Artifact/Manifest 是不同版本轴，必须在 release record 中同时固定。镜像以 digest 或不可变 tag 部署；ReleaseManifest 固定 definition/artifact/benefit/audience/experiment versions 和 cell weights。回滚前先确认旧应用能读当前数据库，旧 runtime 仍支持目标 Artifact ABI。

## 标准流程

1. CI：Java/frontend/contracts/architecture/E2E、SBOM、SAST、secret/license/vulnerability 和镜像 provenance 全绿。
2. 数据库 expand migration，确保 N/N-1 应用均可运行；migration 账号与 runtime 账号分离。
3. 部署新应用副本但不接流，startup/readiness、JWK、依赖和稳定 artifact 冷加载通过。
4. 编译 Definition，校验 golden/cost/合规/Terms/funding；独立审批完成。
5. 写入签名 immutable ArtifactBundle 与 desired Manifest，等待每个目标 cell ACK。
6. 按 1% → 5% → 25% → 50% → 100% 推进；每步至少覆盖统计样本和业务延迟窗口。
7. 比较错误、p99、价格差异、reserve failure、资金差异、complaint、SRM、Flink lag 和 OTel drop。
8. 100% 后保留前 generation 和代码 revision，跨过最大 OfferToken TTL/退款关键窗口后再清理。

推进是显式操作，超时不得自动当成功。ACK 不全、观测水位落后、对账非零、SRM 或合规门禁失败都阻止推进。

## 回滚决策

| 故障 | 优先动作 | 注意 |
| --- | --- | --- |
| 新规则结果错误 | generation weight → 0，切回保留 Manifest | 已签 OfferToken 仍按原 generation 履约 |
| 新应用错误、数据库兼容 | 回滚 Deployment | 只有 expand-compatible 才安全 |
| Provider 重复/错误发送 | 暂停 connector/campaign kill switch | 不重置 contact idempotency key |
| 资金守恒异常 | 冻结 resource/campaign，停止新 reserve | 不直接改余额；对账修复 |
| Flink 新版本异常 | 从升级前 savepoint 回滚 jar/state | serializer/UID 必须兼容 |
| 安全泄露 | 封禁身份/tenant、收紧流量、轮换密钥 | 保护取证，走事件响应 |

Kill switch 只能关闭 campaign、benefit、connector、tenant 或 generation，不能创建更大优惠。所有开关有 owner、reason、expiry 和 audit；过期不自动恢复危险行为。

## Kubernetes

```bash
helm diff upgrade marketing deploy/helm/marketing-platform -n marketing -f values-production.yaml
helm upgrade --install marketing deploy/helm/marketing-platform -n marketing \
  -f values-production.yaml --atomic --timeout 15m
kubectl -n marketing rollout status deploy/edge-gateway --timeout=10m
```

Helm `--atomic` 只处理 Kubernetes 资源，不回滚外部数据库 migration、Definition generation 或 provider effect。变更单必须分别列出这些补偿。

## 数据库 contract

只有在所有 cell 都运行新代码、backfill 校验完成、CDC/outbox/分析 consumer 已兼容且回滚窗口关闭后，才执行 drop/rename/constraint tightening。大表 migration 限速并监控 replication lag/lock；失败停止而不是反复重试。

## 签名密钥轮换

Release、Compiler、Runtime ACK 和 OfferToken 是四套独立 Ed25519 信任域。生产私钥必须由 KMS/HSM 或具备等价审计、轮换和最小授权能力的签名服务持有，应用配置只注入 key id、签名句柄与信任公钥，不把私钥写入 Helm values、Secret 明文文件或镜像。

轮换按“先扩信任、再切签名、观察、最后缩信任”执行：

1. 将 `oldKeyId:oldPublicKey,newKeyId:newPublicKey` 同时发布到所有 verifier 的 trusted key ring，并确认所有 cell readiness。
2. 切换 signer 的 active key id；新 Artifact/Manifest/ACK/Token 只用新 key，禁止复用旧 key id 对应不同公钥。
3. 覆盖最长 Manifest 保留期、OfferToken TTL、Kafka replay 窗口和灾备恢复演练，确认旧 generation 与在途 token 仍可验证。
4. 停止旧私钥签名并吊销调用权限；只有当旧制品和 token 全部越过验证窗口后才移除旧公钥。

紧急泄露不等待普通观察窗口：先启用 kill switch/停止新签名，扩散新公钥并切 signer，保留证据，然后按影响范围撤销旧 generation。删除旧公钥前必须确认不会让已承诺但仍有效的 OfferToken 无法履约。

本地 `.env` 中的私钥只用于单机 DEV 验收；生产示例 values 使用 External Secret 占位，部署清单门禁拒绝 OIDC 关闭、缺失 secret 引用或可变镜像标签。

## R1 黑盒验收

`scripts/smoke.sh` 会调用 `scripts/acceptance-r1.py`，通过 Gateway 验证一条全新的、不可依赖种子数据的业务链：低代码图保存/校验/仿真、独立角色审批、签名编译、runtime warm/ACK/activate、实时受众、决策幂等、库存与预算 reserve/confirm/对账、STREAM 单写 Journey、触达副作用、直接与 Flink 衡量投影。任何一步失败都使启动和发布验收失败。

## 发布完成证据

保存 CI run、SBOM/provenance、镜像 digest、migration version、Manifest/artifact digest、审批、cell ACK、灰度曲线、对账结果、watermark 和回滚演练。任何一项缺失，生产状态保持 conditional。
