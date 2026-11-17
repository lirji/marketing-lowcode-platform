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
| stage / ACK / activate / rollback | `OFFER_RECOMPILE_REQUIRED` | 旧比例别名制品语义有误，保留冻结源，重新编译并走正常发布 |
| ACK | `ACK_*` | runtime 回执不匹配或不可信；展示后端 detail，不允许直接激活 |
| activate | `RUNTIME_NOT_READY` | 继续等待/刷新 ACK 状态 |
| activate | `ACTIVATION_TOO_EARLY` | 等到 `activationAt` 再重试同一激活命令 |

依赖校验不可达时返回可重试的 `BENEFIT_RELEASE_GATE_UNAVAILABLE`，采用 fail-closed，不创建 STAGED manifest。

## 活动组合准备（S11e）

`POST /api/v1/campaigns/{campaignId}/release-bundles` 接收 `expectedVersion` 和 2–8 个 `manifestIds`，使用 `Idempotency-Key`、`release:write` 及当前活动组织/店铺范围。仅接受同活动、同环境/cell、不同 slot、已审批且未过期的 STAGED 签名清单，不支持部分灰度。服务端冻结完整成员，返回版本、contentHash和PREPARING状态；同键相同集合换序重试返回原结果，异载荷或版本竞争返回409。

使用同路径的 `GET /latest` 或 `/versions/{version}` 读取，要求 `release:read` 和当前活动范围。具体成员登记后，原单清单激活和回滚入口返回 `RELEASE_BUNDLE_REQUIRED`，包括旧幂等回执。准备不会生成激活指令，现阶段不能从该接口整体上线。

V8 新增组合历史及成员归属，以现有活动行上的版本游标和清单行锁保证事务；审计失败全部回滚。迁移后的旧服务不能保护新成员归属，回退前须冻结发布写入口并保留数据。完整合同、后续运行端准备和整体切换验收见 [增量设计](../delivery/completion-20261116/ATOMIC_RELEASE_DESIGN.md)。本机共享库尚未执行此迁移。

### 完整组合签名导出（S11f1）

`GET /api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/manifest` 在当前权限/范围检查后返回完整签名组合。外层使用独立协议用途，覆盖完整成员内容及签名，过期或成员签名不可信时拒绝；历史准备记录仍可读取。密钥轮换可以更新外层签名，但不改变原版本或contentHash。

此签名仅证明冻结内容来源。它既不是运行端组合ACK，也不是整体激活指令；不得把导出成功展示成线上已生效。准备阶段仍不改变运行流量。

### 运行端组合准备（S11f2）

`POST /api/v1/decisions/runtime/release-bundles:prepare` 与 `POST /api/v1/journey-runtime/release-bundles:prepare` 接收 `{bundle,memberManifestId}`，要求 `runtime:warm`，原始JSON最大1MiB。先验证完整组合签名/租户/有效期，再核对本站持久化的精确清单、制品签名/校验和及可执行结构；Journey还核对已安装定义。

成功返回内外两层签名的 `ReleaseBundleAck`，绑定组合摘要和成员签名。准备和重复调用不安装制品、不激活流量；缺版本时应先通过原manifest入口预热，不能回退到latest。当前ACK容量仍来自既有运行预算配置，不代表生产容量实测结果。控制面证据收集及整体激活仍待后续切片。

### 控制面组合证据与就绪查询（S11f3）

向 `POST /api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/acks` 提交原始双层签名ACK，要求 `runtime:ack` 和当前活动范围，原始JSON最大16KiB。精确重复及仍有效的乱序证据不会重复审计或覆盖更新证据；同身份同时间的不同签名返回409。每个成员最多64个运行身份，数据库外键同时限定组合及成员归属。

`GET` 同级 `/readiness` 要求 `release:read`，每次重验当前信任、五分钟新鲜度、成员有效期、制品闭包、build及原发布策略的副本/容量条件。每个成员独立达标才返回 `runtimeReady=true`；失效或损坏证据计入 `ignoredEvidence`。该结果会随时间失效，不是激活授权，准备历史仍为PREPARING，接口不生成激活指令。V9迁移仅在隔离MySQL验证，当前共享库尚未更新。
