# 安全基线

## 信任边界

浏览器、外部订单/画像/渠道、控制面、在线运行面、数据面和构建系统分别是独立信任域。网关不是唯一安全边界：每个服务验证 JWT issuer、signature、audience、expiry 和权限；每个数据访问显式携带 tenant；每个 artifact/provider callback 独立验签。

## 身份与授权

- 用户使用 OIDC Authorization Code + PKCE；禁止 implicit/password grant。控制台不持久化 refresh token 到 localStorage，生产 CSP 不允许任意脚本。
- 接入统一能力平台时，Casdoor 是身份提供方：`sub` 为稳定用户 ID，`owner` 为租户，`permissions` 为粗粒度 scope。本地默认仍为 DEV headers。
- 服务到服务使用短期 workload identity/mTLS token；不得复用人的 token 或静态共享 API key。
- 权限是 `resource:action`，同时受 tenant → organization → shop scope 和资源 ownership/状态 ABAC 约束。
- submitter 不能审批自己的版本；release、kill switch、资金策略和 PII export 需要独立角色或双人批准。
- 客服 trace、原始事件 replay 和批量导出实施 purpose binding、工单号、结果脱敏和访问审计。

开发 `X-Dev-*` headers 只有 `MARKETING_SECURITY_MODE=DEV` 且显式 enable 才生效。Helm 模板对非 OIDC 直接失败，生产网络层还应拒绝 DEV headers。

## 租户隔离

隔离不能只靠 Controller filter：

| Layer | Control |
| --- | --- |
| HTTP | JWT claims 构造不可变 TenantScope；body tenant 仅能与 scope 比对 |
| SQL | repository 方法 tenant 为必填；唯一键/索引包含 tenant；服务账号只访问所属 schema |
| Cache | key prefix 使用规范化 tenant/cell；禁止裸业务 id |
| Kafka | envelope tenant 校验；partition/DLQ/replay 保留 tenant；consumer context 每条重建 |
| S3 | `environment/cell/tenant/type/digest` key + IAM prefix；artifact 再做签名 |
| Flink | key state 包含 tenant；timer/correction/DLQ 不丢 tenant |
| Logs/metrics | tenant 仅允许低基数 token/受控标签；禁止 PII |
| Bulk/background | job scope 固定 tenant list + purpose；失败不能退化为 all tenants |

测试必须覆盖跨 tenant id 猜测、缓存碰撞、批量导入、异步线程 context 泄露、DLQ replay 和管理员 scope 越权。

## 密钥与秘密

- 人、OIDC、OfferToken/Manifest signing、数据加密、provider HMAC 使用不同 key purpose。
- 非对称签名由 KMS/HSM 执行；应用仅获得 sign permission 或 public key。key id 进入 token/manifest，支持重叠验证轮换。
- Kubernetes Secret 只是注入接口，值由 Vault/云 Secrets Manager + workload identity 管理；严禁在 Git、Helm values、日志和镜像层写明文。
- 密钥轮换先发布新 verifier，再切 signer，最后等待最长 token/artifact/回滚窗口后 retire 旧 key。
- `.env` 仅本机、权限 0600、已忽略；本地预置密码不用于共享环境。

## 运行时加固

镜像使用固定 Java 21/Node/nginx/Flink 基线、non-root、read-only root filesystem、drop ALL capabilities、seccomp RuntimeDefault、无自动挂载 ServiceAccount token、临时卷限额、资源 requests/limits、startup/readiness/liveness 和优雅关闭。生产启用 TLS/mTLS、NetworkPolicy、Pod Security Admission、镜像签名/准入、节点与 namespace 隔离。

Compiler 风险最高：独立 worker pool、禁网、无云 metadata、只读依赖、PID/CPU/内存/文件/时间上限，禁止反射/任意脚本/动态依赖。触达 connector 有独立 egress allowlist，不允许低代码配置任意内网 URL，防 SSRF。

## 供应链门禁

CI 包含 Maven/npm lock、CodeQL、Gitleaks、Trivy vulnerability/secret/misconfig/license、CycloneDX SBOM、容器 SBOM 和 build provenance。发布镜像使用不可变 digest，部署准入校验签名/provenance/允许 registry/无 critical vulnerability 例外。例外必须有 CVE、不可利用证据、owner、expiry 和补偿控制。

```bash
./scripts/security-scan.sh
```

依赖机器人只能创建 PR，不自动发布。规则插件和 provider connector 与主代码同等走代码审查、SBOM、签名和漏洞门禁。

## 漏洞与事件响应

发现跨租户、未授权资金改变、签名绕过或 PII 暴露按 SEV-1：立即隔离身份/tenant、冻结相关发布和资源、保留审计/镜像/Manifest/offset 证据、轮换受影响 secrets，并由安全/法务决定通知。不要在公开 issue 提交真实 token、订单、手机号或利用细节。
