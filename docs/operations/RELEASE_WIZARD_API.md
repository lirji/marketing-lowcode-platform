# 发布向导后端契约（Cursor 交接）

控制台通过 Edge Gateway 调用，业务租户来自已验证的 `tenant_id`；登录组织 `owner` 不参与发布隔离。每个写步骤首次执行生成一个独立的 `Idempotency-Key`，网络重试或用户重试同一步时必须复用该 key；进入下一步或换定义版本才生成新 key。

发布向导需要 `definition:read`、`definition:compile`、`artifact:read`、`release:write`、`release:read`、`release:activate`。OIDC 身份缺少任一权限时，控制台应禁用对应动作并在 `title` 中标出缺失权限，不能等服务端静默返回 403。

## 固定顺序

1. **编译** — `POST /api/v1/compile`，权限 `definition:compile`。
   - 传审核版本的 `definitionId`、`definitionVersion`、`format`、`namespace`、`modelName` 和完整 `graph`。
   - 只有 `valid=true` 才继续；保存返回的 `artifactId`。
2. **读取制品** — `GET /api/v1/artifacts/{artifactId}`，权限 `artifact:read`。
   - 用响应构造 stage 的 `artifacts[]`；`uri` 使用 `compiler://{artifactId}`。
   - 不要在浏览器重算 `checksum`、`sourceDigest` 或签名。
3. **暂存** — `POST /api/v1/releases`，权限 `release:write`。
   - `approvalCaseIds` 必须覆盖同一个已通过审核的定义版本。
   - 后端验证定义审核态、冻结源摘要、编译器签名及 runtime/ABI closure。
   - Offer 图中的 `benefitDefinitionVersion` 由后端从冻结图提取；每个版本必须是 ACTIVE、已绑定同租户 ACTIVE SKU，且 `policy.type` 与 SKU 类型一致。控制台不另传权益 closure。
4. **轮询并等待 Runtime ACK** — 控制台只调用 `GET /api/v1/releases`（权限 `release:read`），观察目标 manifest 的 `state`、`readyReplicas`、`readyCapacity`。
   - Decision/Journey runtime 分别通过 `PUT /api/v1/decisions/runtime/manifest` 或 `PUT /api/v1/journey-runtime/manifest` 下载、验签并预热，之后由 runtime 调用 `POST /api/v1/releases/{manifestId}:ack`（权限 `runtime:ack`）。控制台绝不调用 `:ack` 或伪造签名。
   - ACK 必须与 manifest/generation/cell、ABI、完整制品集合和允许的 runtime build 一致。
5. **激活** — `POST /api/v1/releases/{manifestId}:activate`，权限 `release:activate`。
   - 仅在可信 `READY` ACK 达到副本数和容量门槛后启用。

完整字段以 [`marketing-api.yaml`](../../marketing-contracts/src/main/resources/openapi/marketing-api.yaml) 为准。

一次向导只处理一个 runtime：`OFFER_DECISION_DAG` 对应 `decision`，`JOURNEY_STATE_MACHINE` 对应 `journey`。同一活动要发布两类 runtime 时分别运行两次向导；当前契约没有 `ReleaseBundle` HTTP，也不应由控制台拼装一个虚构的 bundle。

## 控制台必须区分的 problem

| 阶段 | code | 含义 / 处理 |
|---|---|---|
| stage | `RELEASE_NOT_APPROVED` / `APPROVAL_CASE_INVALID` | 返回审核步骤，不能绕过 |
| stage | `BENEFIT_RELEASE_UNBOUND` | Offer 未引用已绑定 SKU 的 BenefitDefinition |
| stage | `BENEFIT_RELEASE_NOT_ACTIVE` / `BENEFIT_RELEASE_NOT_FOUND` | 引用版本不可发布，回到权益定义修正 |
| stage | `SKU_NOT_ACTIVE` | SKU 已暂停、退役或不存在，刷新目录后重新选择 |
| stage | `BENEFIT_SKU_TYPE_MISMATCH` | `policy.type` 与 SKU `benefitType` 不一致 |
| stage | `ARTIFACT_DEFINITION_MISMATCH` / `ARTIFACT_SOURCE_MISMATCH` | 制品不是当前审核冻结版本，重新编译 |
| stage | `ARTIFACT_SIGNATURE_INVALID` | 编译器证明不可信，停止发布并检查密钥 |
| ACK | `ACK_*` | runtime 回执不匹配或不可信；展示后端 detail，不允许直接激活 |
| activate | `RUNTIME_NOT_READY` | 继续等待/刷新 ACK 状态 |
| activate | `ACTIVATION_TOO_EARLY` | 等到 `activationAt` 再重试同一激活命令 |

依赖校验不可达时返回可重试的 `BENEFIT_RELEASE_GATE_UNAVAILABLE`，采用 fail-closed，不创建 STAGED manifest。
