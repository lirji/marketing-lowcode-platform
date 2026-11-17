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

旧单清单向导一次只处理一个 runtime：`OFFER_DECISION_DAG` 对应 `decision`，`JOURNEY_STATE_MACHINE` 对应 `journey`。组合准备、签名导出与就绪接口见下文；整体激活入口尚未交付，控制台不能把依次调用单清单激活显示为整体发布成功。

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

使用同路径的 `GET /latest` 或 `/versions/{version}` 读取，要求 `release:read` 和当前活动范围。组合准备成功后，成员所在完整slot的所有清单均受保护，原单清单激活、回滚和回滚候选入口返回 `RELEASE_BUNDLE_REQUIRED`，包括未加入组合的新清单和旧幂等回执。准备不会生成激活指令，现阶段不能从该接口整体上线。

V8 新增组合历史及成员归属，以现有活动行上的版本游标和清单行锁保证事务；审计失败全部回滚。迁移后的旧服务不能保护新成员归属，回退前须冻结发布写入口并保留数据。完整合同、后续运行端准备和整体切换验收见 [增量设计](../delivery/completion-20261116/ATOMIC_RELEASE_DESIGN.md)。本机共享库尚未执行此迁移。

### 完整组合签名导出（S11f1）

`GET /api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/manifest` 在当前权限/范围检查后返回完整签名组合。外层使用独立协议用途，覆盖完整成员内容及签名，过期或成员签名不可信时拒绝；历史准备记录仍可读取。密钥轮换可以更新外层签名，但不改变原版本或contentHash。

此签名仅证明冻结内容来源。它既不是运行端组合ACK，也不是整体激活指令；不得把导出成功展示成线上已生效。准备阶段仍不改变运行流量。

### 运行端组合准备（S11f2）

`POST /api/v1/decisions/runtime/release-bundles:prepare` 与 `POST /api/v1/journey-runtime/release-bundles:prepare` 接收 `{bundle,memberManifestId}`，要求 `runtime:warm`，原始JSON最大1MiB。先验证完整组合签名/租户/有效期，再核对本站持久化的精确清单、制品签名/校验和及可执行结构；Journey还核对已安装定义。

成功返回内外两层签名的 `ReleaseBundleAck`，绑定组合摘要和成员签名。准备和重复调用不安装制品、不激活流量；缺版本时应先通过原manifest入口预热，不能回退到latest。当前ACK容量仍来自既有运行预算配置，不代表生产容量实测结果。控制面证据收集见下节，整体激活仍待后续切片。

### 控制面组合证据与就绪查询（S11f3）

向 `POST /api/v1/campaigns/{campaignId}/release-bundles/versions/{version}/acks` 提交原始双层签名ACK，要求 `runtime:ack` 和当前活动范围，原始JSON最大16KiB。精确重复及仍有效的乱序证据不会重复审计或覆盖更新证据；同身份同时间的不同签名返回409。每个成员最多64个运行身份，数据库外键同时限定组合及成员归属。

`GET` 同级 `/readiness` 要求 `release:read`，每次重验当前信任、五分钟新鲜度、成员有效期、制品闭包、build及原发布策略的副本/容量条件。每个成员独立达标才返回 `runtimeReady=true`；失效或损坏证据计入 `ignoredEvidence`。该结果会随时间失效，不是激活授权，准备历史仍为PREPARING，接口不生成激活指令。V9迁移仅在隔离MySQL验证，当前共享库尚未更新。

### 同slot保护（S11g1）

V10在原slot行增加组合管理标记，并回填历史组合成员；不改变已有desired/stable指针。标记与准备/成员/审计同事务提交，审计或其他准备步骤失败时一起回滚。单清单执行在锁定slot后检查最新标记，不能靠旧事务快照绕过；可以继续暂存和准备新组合，也可以使用原紧急停用。

组合管理开始后，不能把控制面回退到不识别该标记的可写版本；应先冻结发布写入。该保护尚未部署到共享环境，运行端防绕过和完整版本传递仍在后续S11g范围。

### 整体激活授权类型（S11g2）

共享类型`ReleaseBundleActivationDirective`绑定完整签名组合、活动组织/店铺、单调激活序号、ACTIVATE/ROLLBACK动作与授权时间/人。接收方必须验证外层授权、内层组合及各成员的原签名，并检查租户与有效期；签名准备证明或旧ACK不能充当激活授权。

当前只交付类型和验证器，没有签发/应用HTTP入口，也不写运行指针。签名成功不表示运行面已应用，后续仍需持久化序号仲裁、精确成员选择、参与版本绑定与Flink传播。

### Decision持久接收整体授权（S11g3a）

`POST /api/v1/decisions/runtime/release-bundle-directives`要求`runtime:activate`并接收最多1MiB的严格JSON整体授权。先验证当前可信签名/租户/时间、本地完整slot及已安装制品，再在同一数据库事务记录不可变历史与最高接收序号。相同序号相同内容返回原接收时间；同序号异内容或复用指令编号409，未知旧序号409，已记录旧授权重放成功但`current=false`。

`GET /api/v1/decisions/runtime/release-bundle-directives/{campaignId}/latest`在相同权限下查询最高已接收授权；跨租户和大小写不同的活动键严格隔离。返回`state=RECORDED`，`current`仅指最高接收序号；不改变旧线上指针，不是READY/APPLIED回执；接收成功会将本站slot持久标记为组合管理。过期历史仍可读，后续使用必须重新验证有效期及实时停用。V4仅在隔离MySQL验证，未部署或迁移共享库。

### Journey持久接收整体授权（S11g3b）

Journey提供对应的`POST /api/v1/journey-runtime/release-bundle-directives`与`GET .../{campaignId}/latest`，使用相同的`runtime:activate`、1MiB严格解析和`RECORDED`合同。它在Journey自己的V3表保存授权历史/head，除原签名制品外还核对真实已安装的Journey定义；缺generation沿用404，定义缺失或内容不符拒绝。

接收成功会将本站slot持久标记为组合管理；不推进Journey旧active pointer、不改enrollment/Outbox或Flink，不返回实际应用证明。后续Flink固定组合执行必须继续验证授权和参与绑定；不能把两个服务都RECORDED作为整体发布已生效。

组合签名对象的`tenantId`保持既有Java值对象的实际JSON格式，例如`{"value":"dev-tenant"}`；机器合同以共享`TenantId`对象描述，不改变已发布响应为字符串。签名规范仍使用其中的租户值，签名格式不变。

### 固定参与版本凭证（S11g4a）

共享`ReleaseBundleParticipation`记录参与编号/主体摘要、完整作用域、原授权签名与组合版本/摘要、准入时间，独立签名覆盖全部字段。校验方须从可信入口提供预期主体、参与编号和tenant/campaign/environment/cell，并取原完整授权逐项核对；Control签名与参与签名使用分开的信任集合。

老参与按准入时刻验证原清单期限，因此可以在过期后恢复；验证使用当前进程的可信密钥集合；密钥更新需通过运行配置刷新/受控重启生效，尚未验证生产动态轮换。该事实不替代实时身份/停用/风控/同意/配额或终态幂等，也不允许换参与后重用。当前交付合同与验证器，没有签发/绑定HTTP入口；持久唯一绑定、实际APPLIED准入指针及Flink执行仍待后续。

### 运行端旧发布入口保护（S11g4b）

Decision与Journey接收合法整体授权时，在同一事务把本站物理slot的`bundle_managed`置为true。该slot上的所有单清单HTTP激活/回滚及旧成功回执均返回409 `RELEASE_BUNDLE_SLOT_MANAGED`；新的Coordinator切换也受同一持久门禁保护。Decision原有已验证的旧通知无操作重放仍可确认，但不能当作组合应用回执。

Decision V5/Journey V4会回填历史接收记录：已有active指针保持原generation/sequence/签名/更新时间；没有active指针时只建立零代际的禁用slot。标记不影响安装、读取原配置或紧急停用；接收失败则连同标记回滚。所有旧写版本退出或冻结后才能启用，降级前先冻结发布写入，不能删标记恢复旧入口。迁移仅在隔离库验证，未部署到共享环境；整体APPLIED/新参与准入仍未开放。

### Flink固定参与执行（S11g5a）

`JOURNEY_SIGNAL`的START可携带`data.bundleParticipation`。事件网关保持可信tenant、subjectToken、businessKey（enrollmentId）和signalId/time，并透传凭证供Flink核验；EVENT不得改绑。enrollmentId用共享`ReleaseBundleParticipation.enrollmentId(memberManifestId)`派生，不能另选任意执行ID重放同一成员。旧事件无此字段，旧JourneyJobState及PlanReference类型保持不变。

新作业读取Journey自己的授权历史和精确安装制品，验证参与/Control/成员/编译信任、主体、派生键及引用字段，老参与在原期限后仍按admittedAt验证。当前kill-switch继续生效；可信凭证缺持久投影或已安装数据损坏会等待恢复，不当坏消息推进消费位点。不同活动/授权的缓存独立且最多128条、5分钟，命中仍校验参与和停用。

新环境参数`JOURNEY_PARTICIPATION_TRUSTED_KEY_ID`、`JOURNEY_PARTICIPATION_PUBLIC_KEY_BASE64`、`JOURNEY_PARTICIPATION_TRUSTED_PUBLIC_KEYS`为Decision参与信任，默认空只接受旧路径。信任在作业实例初始化时固定；轮换/撤销须按受控配置更新和状态恢复流程生效，未交付热更新或真实KMS验证。

`journey-bundle-participation-v1`以独立字符串受管状态保存凭证，结束/过期后保留为墓碑；不自动清理，容量/归档/保留策略仍待S13。状态写或输出故障使作业恢复checkpoint，不能变成坏消息后提交半次转换。该片测试不等于真实生产savepoint、Kafka外部副作用端到端或混合版本验收；未更新运行中的Flink。现有旧消费者会拒绝新字段，因此生产者只可在新消费能力验收后启用。持久唯一准入、整体应用指针和正式Control发布入口仍未开放。

### Decision固定参与求值（S11g5b，尚未启用准入）
- 组合专用`POST /api/v1/decisions/bundle:evaluate`和`/bundle:batch-evaluate`的每个请求必须带`bundleContext={campaignId,participationId,participation:ReleaseBundleParticipation}`。主体仍来自可信业务请求，租户来自认证。旧URL在新服务拒绝组合上下文，新URL在旧服务404，禁止调用者失败后降级回旧URL；签名必须与独立活动/参与编号、主体、本地environment/cell/namespace和g3精确历史授权一致。调用者仍需decision:evaluate及当前组织/店铺范围，来源组织必须等于请求组织，来源限定店铺时购物车不能混入其他店铺。
- 新路径从持久库核对原组合成员/制品，不使用latest或单slot active。历史清单在准入时有效即可恢复；当前策略有效期、商品/渠道/人群和kill检查照常执行。当前签名信任来自进程配置快照，换钥匙/撤销需刷新配置或滚动重启，不宣称已有热轮换。
- 可选`MARKETING_PARTICIPATION_TRUSTED_KEY_ID/PUBLIC_KEY_BASE64/TRUSTED_PUBLIC_KEYS`仅用于参与签名角色，默认空拒绝新路径；旧OfferToken、Control与compiler配置不能代替。没有新增私钥、签发端或上线开关。
- 旧请求不输出null新字段，因此原幂等摘要不变。新请求完整上下文计入摘要，同key换绑定409；JDBC和Redis成功重放返回原报价前重验当前权限、范围、kill和来源/签名/制品。报价有效期与原响应一致，不刷新OfferToken；计算结果不代表预占、支付或发奖。
- 本片没有唯一准入DB绑定、APPLIED指针、Control整体生效回执或完整旧入口封锁；它只补消费者。实际启用仍须完成上述步骤及所有Decision/Flink运行版本能力验证。无部署、共享迁移或运行中作业变更。
