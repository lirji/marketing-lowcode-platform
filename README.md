# Marketing Low-code Platform R1

面向大型零售电商营销场景的完整 R1 参考实现：Java 21、DDD、微服务、事件驱动、React 低代码工作台、Drools/DMN 规则执行、Flink 实时受众/旅程/衡量，以及生产候选级的身份隔离、发布回滚、可观测和部署资产。

> 当前结论是 **production candidate / conditional-go**。代码与部署资产可以进入企业环境验证；真实上线仍必须完成目标环境压测、灾备演练、资金不变量验证、安全扫描、密钥接入和法务合规签字。本仓库不声称已达到京东真实规模。

新增裂变能力正在分阶段实施，当前能力、专项证据和未开放门禁见[裂变后端实施导航](docs/REFERRAL_IMPLEMENTATION_STATUS.md)。完整裂变尚未达到上述R1生产候选结论。

## 本地启动

前置条件：Docker Desktop、Java 21、pnpm 9，以及同级目录的 `dev-infra`。数据库与中间件统一复用 `dev-infra`；首次启动会生成仅本机可读的 `.env`、初始化隔离资源并构建业务镜像：

完整发布验收还要求 `BENEFIT_CENTER_BASE_URL` 指向可达的权益中台，验收租户已有治理通过的 ACTIVE SKU。用 `ACCEPTANCE_TENANT_ID` / `ACCEPTANCE_ORGANIZATION_ID` / `ACCEPTANCE_SHOP_ID` / `ACCEPTANCE_BENEFIT_SKU_ID` 指定本地测试范围；目录为空时不会绕过发布门禁或伪造 SKU。未提供这些变量时仍使用参考租户 `retail-cn`。

```bash
cd ../dev-infra && make init   # 首次执行；随后替换 .env 中的 change-me
cd ../marketing-lowcode-platform
./scripts/bootstrap.sh
```

默认不启动完整可观测栈，以减少本机常驻资源。需要 Prometheus、Grafana、Tempo 和 Loki 时使用：

```bash
./scripts/bootstrap.sh --observability
```

若检测到旧版 `marketing-r1-mysql-1` 容器，启动脚本会拒绝自动切到空的共享数据库。先按 `dev-infra/docs/marketing-platform-integration.md` 备份并迁移数据；确认迁移完成或明确接受空库切换后，才可使用 `CONFIRM_DEV_INFRA_CUTOVER=marketing-r1 ./scripts/bootstrap.sh --allow-legacy-cutover`。

网关启动只等待 Decision 进程启动，首个合法制品激活之前 Decision readiness 保持 DOWN。`smoke.sh --wait` 等待 liveness 后执行审批、签名制品、READY ACK 和激活，不用空规则伪造就绪。该脚本会写本地验收数据；不要对生产环境执行。

Flink 提交器位于 `bootstrap` profile；普通 `docker compose up` 不会提交作业。`bootstrap.sh` 通过 `scripts/submit-flink-jobs.sh` 核对三个指定作业、全部子任务和最近完成的 checkpoint；健康集群直接复用。存在遗留状态时需要明确的恢复计划，见[本地 Flink 恢复](docs/operations/RUNBOOK.md#本地-flink-提交与恢复)。

9 个领域服务不再包含 H2 后端。通过 Compose 启动时，它们连接 `dev-infra` 的 `infra-mysql84:3306`；直接从 IDE 或 Maven 启动时，默认连接宿主机 `127.0.0.1:43306`。先加载项目 `.env` 中对应的应用与迁移密码，例如 `set -a; source .env; set +a`。集成测试使用临时 MySQL 8.4 Testcontainers，不写入持久开发库；Docker 较慢时允许最多 5 分钟完成首次初始化。

启动完成后：

| 入口 | 地址 | 说明 |
| --- | --- | --- |
| 营销控制台 | http://localhost:8084 | 默认 DEV 身份；门户入口为 `/login`（端口来自中央注册表 `MARKETING_UI_PORT`） |
| 能力门户 | http://localhost:5274 | 同级 `auth-platform` 的公开目录；卡片进入本控制台登录页 |
| API Gateway | http://localhost:8080 | REST 统一入口 |
| Flink Dashboard | http://localhost:8081 | 3 个流作业 |
| Keycloak | http://localhost:8180 | 本地独立 OIDC（可选）；管理员密码见 `.env` |
| Casdoor | http://localhost:8000 | 统一能力平台 SSO；`--secure` 叠加后使用 |
| Grafana | http://localhost:3001 | 可选；使用 `--observability` 启动 |
| Prometheus | http://localhost:9090 | 可选；使用 `--observability` 启动 |

开发模式的参考租户是 `retail-cn`，组织为 `retail-business`。默认 Compose 走 DEV headers，不强制 Casdoor。接入同级统一能力门户时：

```bash
# 在 auth-platform 开通组织 / 角色 / 权限（密码由调用方注入，不会写入仓库）
MARKETING_USER="${MARKETING_USER:-marketing-e2e-admin}" \
PASSWORD="${MARKETING_PASSWORD:?请先通过受控方式设置本地开通密码}" \
  bash ../auth-platform/deploy/marketing-platform-provision.sh

# 叠加 Casdoor OIDC 后重建控制台与网关
bash deploy/compose.sh --secure up -d --build
```

门户入口是 `http://localhost:8084/login?returnTo=%2F`。OIDC 只接受组织 `marketing-platform`，并校验派生 clientId；未知组织不会跳转 Casdoor。上例用户名默认 `marketing-e2e-admin`，业务货主使用 `properties.tenant_id=retail-cn`；密码由调用方注入，不在仓库保存。默认 Compose 使用 DEV 身份，Keycloak 为独立本地 OIDC 选项；门户 Casdoor 需要 `--secure`。IAM 重建后的恢复步骤见同级 `auth-platform/deploy/portal-casdoor-restore.sh`；生产 catalog 的开放仍以域名、回调登记和实际验收为准。

Keycloak realm 预置操作员 `operator` / `local-operator-change-me` 仅用于未接 Casdoor 的本地演示，绝不可用于共享或生产环境。

```bash
./scripts/smoke.sh                 # 完整黑盒业务闭环与 3 个 Flink pipeline 验收
./scripts/dev-down.sh              # 保留数据停止
CONFIRM_PURGE=marketing-r1 ./scripts/dev-down.sh --purge  # 仅删除业务侧 Flink 本地卷
```

只开发前端时：

```bash
pnpm install --frozen-lockfile
pnpm frontend:dev
```

### 本地演示数据

`bootstrap.sh` 会调用 `scripts/seed.sh`；已有本地环境也可单独执行 `./scripts/seed.sh`。脚本连接 `dev-infra` 的 `mysql84`，先写入 `marketing-platform` 场景，再写入控制台默认的 `retail-cn / retail-business` 场景，最后补充审核、发布、人群、资金、旅程、触达及看板状态。

- 重跑会刷新约定的 `mock-*`、`rcn-*` 演示记录、相对时间和演示账户余额；仅用于本地演示环境。
- 补充 SQL 会清理 `retail-cn` 下四个历史残缺的 `rcn-app-*` 演示申请；资金分桶只处理明确列出的演示租户、资源和申请。
- 三份 SQL 分别提交事务，失败时先查看报错；修复后可重跑。重复执行不新增同一主键记录，但不是保留演示操作历史的增量导入。
- 种子中的发布状态、签名、摘要及渠道回执用于页面展示，不能作为真实编译、发布、发奖或跨系统链路通过的证据。
- 演示 outbox 明确写入 `PUBLISHED` 和对应深度分桶，不进入真实发送队列；其已发布时间与终态必须一致，避免旧演示记录触发全局背压。

执行 `python3 scripts/verify-seed-data.py` 可使用本机已有 `mysql:8.4.11` 镜像在隔离临时容器内验证迁移、重复导入、资金守恒和租户隔离；不连接现有开发库。

## 一期能力

- 控制面：MarketingPlan/Campaign、五类低代码方言、版本、diff、仿真、风险检查、多级审核、ReleaseBundle、灰度、ACK/reconcile、回滚与 kill switch。
- 决策面：不可变规则制品、Drools executable model、DMN、稳定实验分桶、分层定价、兼容/互斥求解、逐行与多出资方精确分摊、签名 OfferToken。
- 履约面：PromotionApplication、ReservationGroup、预算/库存/券状态、幂等 confirm/cancel/refund/reverse 和守恒对账。
- 数据面：事件接入、受众快照与实时 membership、Flink keyed state/timer 旅程、触达同意/静默时段/频控、实验曝光、转化归因与水位。
- 平台面：OIDC JWT、租户/组织/门店范围、RFC 9457 错误、OpenAPI/AsyncAPI/JSON Schema、审计、OTel、Prometheus、Grafana、Docker Compose、Helm 和 CI 安全门禁。

### 控制台规则编写

- 创建活动时实际保存 `STANDARD` / `REFERRAL` 类型；类型尚未确认时关闭编辑入口，避免把标准规则写入裂变活动。
- Offer / Journey 支持按节点配置金额、比例、日期、权益版本、条件、等待和触达参数。通过「出口连接」或画布连线连接显式节点；不会自动补入隐藏结束节点。Offer 当前仅支持定额/比例与完整单链，不开放尚不可执行的条件分支。
- 保存、校验、试算与审核绑定同一修订；继续编辑会使原校验失效。保存成功但校验失败时保留版本和输入，可重试校验。
- Offer 金额试算使用实际输入的金额/币种，不验证库存、时间或领取资格；有商品/人群等范围时由后端拒绝缺上下文的试算。Journey 完整仿真尚待补齐，界面不再显示伪价格结果。资源发放台账不等于外部权益到账。
- 浏览器合同验收：`pnpm --filter @marketing/console exec playwright test lowcode-authoring.spec.ts`。可选真实本地验收：设置 `MARKETING_LIVE_UI=1`，使用 `--project=chromium --grep 'complete explicit Offer|Journey timer' --workers=1`；需要本机18080网关、DEV身份及真实权益目录，会在dev-tenant创建隔离活动和定义，不执行审核/发布/发奖。

### 人群数据边界

人群页面目前可保存真实字段上的圈选规则；会员画像来源尚待接入，人数预估和完整快照暂不可操作。页面不会生成虚构主体，也不会把前20条预览样本作为全量人群写库。现有样本API只计算调用方明确提交的样本，不能证明全量圈选完成。

显式快照导入限10000个不同主体，必须满足 `watermark <= asOf < expiresAt` 且创建时未过期。可携带 `Idempotency-Key` 重放同一人群版本、成员集合和时间的首次结果；无键保留旧行为。成员更新使用正版本和数据库唯一键仲裁；旧版本返回真实当前状态，同版本异状态返回409。初始成员数/校验和描述创建基线，后续成员允许更新；快照ID与segment ID尚未完成决策投影接线，保存成功不等于在线资格生效。

## 架构导航

```text
Browser -> Console -> Edge Gateway -> 9 domain deployables
                                     |-> MySQL database per bounded context
                                     |-> signed artifacts + ReleaseManifest generations
Events -> Event Gateway -> Kafka -> Audience / Journey / Measurement Flink jobs
                                     |-> Redis decision idempotency
                                     |-> MySQL materialized projections
```

- [完整 R1 方案与 44 条验收标准](docs/delivery/marketing-platform-complete/DELIVERY_PLAN.md)
- [系统架构与上下文映射](docs/architecture/README.md)
- [分析与评审的最新状态、整改证据](docs/marketing-platform-analysis/CURRENT_STATUS.md)
- [领域模型与不变量](docs/domain/MODEL.md)
- [低代码语言、制品和迁移](docs/lowcode/RUNTIME.md)
- [API、事件和兼容策略](docs/contracts/API_AND_EVENTS.md)
- [生产运行手册](docs/operations/RUNBOOK.md)
- [发布、灰度和回滚](docs/operations/RELEASE_ROLLBACK.md)
- [备份、恢复和灾备](docs/operations/DATA_DR.md)
- [安全基线与威胁模型](docs/security/SECURITY.md)

## 工程结构

| 目录 | 责任 |
| --- | --- |
| `platform-common`, `platform-web` | 无业务含义的身份、隔离、密码学、错误和 Web 安全边界 |
| `runtime-spi` | 低代码语言核、决策/旅程/连接器 SPI、Drools/DMN 适配器 |
| `services` | Edge Gateway 与 9 个限界上下文 deployable |
| `jobs` | 受众、旅程、衡量三个 Flink DataStream 作业；checkpoint + Kafka Exactly-Once Sink |
| `marketing-contracts` | OpenAPI、AsyncAPI、接入/领域事件 payload 和制品 Schema |
| `frontend/apps/console` | React 19 + TypeScript 管理控制台 |
| `deploy` | 应用层本地 Compose、生产 Helm 和业务镜像；本地基础设施配置位于同级 `dev-infra/marketing` |
| `architecture-tests` | DDD 依赖方向与框架隔离门禁 |
| `scripts` | Provider-neutral 验证、启动、压测、备份、恢复和演练入口 |

## 验证

```bash
./scripts/verify.sh --fast          # 后端测试 + 前端 lint/unit/build + manifests
./scripts/verify.sh --full          # clean verify + Playwright
./scripts/verify-deployment.sh      # Compose/Helm/JSON/Shell
./scripts/verify-mysql-migrations.sh # MySQL 8.4 方言与最小权限边界
./scripts/security-scan.sh          # 需要 gitleaks、trivy、syft
python3 scripts/capacity.py --help
```

性能脚本位于 `tests/performance`。负载结论只有在记录硬件、数据规模、遥测配置和完整报告后才有效。

## 生产部署

本地应用 Compose 配合 `dev-infra` 构成可复现开发拓扑，不是生产拓扑。生产使用 [Helm Chart](deploy/helm/marketing-platform/README.md)，强制 OIDC、非 root、只读根文件系统、健康探针、PDB/HPA 和不可变镜像策略，并连接托管高可用数据服务。密钥只能由 Vault/云密钥管理 + External Secrets 等注入，不能提交到 values 或 Git。

CI 显式使用本仓库的 [基础设施固定样例](tests/fixtures/dev-infra/README.md)，不依赖不可访问的跨仓 checkout。本地仍默认使用共享 `dev-infra`。Full stack 工作流验证空环境核心 R1，外部权益目录使用带机器认证的只读合同样例；结果不代表真实外部发奖或生产就绪。`BENEFIT_CENTER_BASE_URL` 在 Compose 中可配置，`MARKETING_COMPOSE_OVERRIDE` 可显式选择资源覆盖文件，缺失文件会失败。

## 关键边界

- 平台不会重造商品、订单、支付、会员主数据或商业短信/Push 平台；通过版本契约、防腐层和连接器对接。
- “exactly-once”只描述 Flink 状态与兼容 sink 的处理保证。任何真实发券、资金或渠道副作用仍以业务幂等键、账本和补偿实现。
- 决策只报价，不直接扣预算或发权益；资金和权益状态只由 Benefit/Funding 上下文改变。
- DEV headers 默认仅允许本地 Compose。生产 Helm 在模板阶段拒绝非 OIDC 模式。
- DEV 控制台退出或重新登录会清理旧查询、缓存和页面状态；旧会话的异步读写回执不会回填新会话。会话切换不表示服务端写操作已撤销，未知结果需回原业务范围查询。真实轨迹查询分别展示加载、失败、空结果和 API 数据，演示裁决只在显式 DEMO 模式展示。
- 触达的待发送/待重试记录在每次投递前复核当前同意、抑制、个性化与模板许可，并按实际发送时间检查静默时段；重试复用原频控名额，非待投递状态幂等返回。
- 静默时间沿用 API 的 ISO 本地时间格式，完整保留最多 9 位秒小数；Engagement V4 迁移将两列扩容至 18 字符，兼容原有时间值和旧版本读写，不截断配置。
- 触达 `contactKey` 在租户内绑定原始请求，收件人、模板、变量、时区等变化返回 `IDEMPOTENCY_PAYLOAD_CONFLICT`；JSON 对象键顺序不影响指纹。频控按服务端实际准入时间计数，`requestedAt` 仅作业务元数据。V5 后历史无指纹的待重试记录返回 `CONTACT_RETRY_UNVERIFIABLE`，必须先核查渠道结果，不能猜测原收件人自动重发；非待投递状态可继续只读查询。迟到渠道响应不能覆盖回执已推进的状态。
- HTTP 渠道连接器按单进程的租户、渠道和 endpoint 隔离限速与熔断，所有 HTTP 尝试计入令牌桶（桶容量为每秒速率）。默认连续失败阈值 5、冷却 30 秒，冷却后只放行一次探测；中断停止重试，重试保持原幂等键。该保护不表示多副本共享总限额；最多保留 10,000 个路由状态，达到上限时新路由返回 `PROVIDER_CAPACITY_LIMIT`，不会驱逐现有保护状态。

### 权益中台跨系统键

- `tenantId` 来自已验证 token owner，拒绝客户端 header 覆盖。
- `subjectRef` 是用户稳定引用，与 Casdoor `sub` 或营销 subject token 的映射由 crosswalk 管理。
- 营销版本键为 `campaignId + definitionVersion`，权益资产键为 `benefitSkuId + skuVersion`。
- `sourceRequestId` 是营销到权益中台的跨系统幂等键。
- Benefit/Funding 通过 `BENEFIT_CENTER_BASE_URL` 查询 tenant 隔离的 SKU 目录；发布 ACTIVE 权益时实时校验模板仍为 ACTIVE。
- AwardIntent 使用服务端签名 OfferToken 重建资格、SKU 与金额；内部触发接口不接受金额字段，运营侧通过 `GET /api/v1/award-intents?campaignId=` 查看投递状态，并可单独查看履约观察。
- AwardIntent 在落 outbox 前以 `sourceId=MARKETING_AWARD` 调用 risk-platform；CASH 使用服务端组装后的最小单位金额，非 CASH/LEGACY 使用 `amount=1,currency=XXX`。业务拦截写 `mk_award_intent_block` 并返回 202，超时、5xx 或降级挑战写 `UNAVAILABLE` 后返回可重试的 `RISK_UNAVAILABLE` 503，均不会写 outbox 或应发事实。
- 发放路由默认 `LEGACY`（也可设 `SHADOW`）；只有 `MARKETING_AWARD_TENANT_MODES=tenant-a=CENTER` 显式命中的租户才进入中台 outbox，且还需开启 `MARKETING_AWARD_RELAY_ENABLED`。模式在首次入队时固化，切换配置不会让旧请求改道。
- 迁移期营销与 drools 都使用稳定的 `sourceSystem=drools-activity` 配合相同 `sourceRequestId`，即便交接误重叠，权益中台仍会按同一幂等身份拒绝第二份不同 payload；正常切流仍必须先关旧租户 authority，再开 CENTER。
- CENTER 入队与 `marketing.award-expected.v1` 应发事实同事务提交；Relay 用租约版本 fencing、单租户批量上限、熔断和原幂等键重试，成功后回填 `benefitOrderNo`。回滚时先清空对应租户的 CENTER 映射，再关闭 Relay，保留 PENDING/DEAD 行供审计和恢复。

### 普通营销发奖的 SKU 版本

新组装的 `drools-activity` 发奖项携带权益目录当前返回的 `expectedSkuVersion`，并随原始 outbox 载荷持久化。重试不会重新选取新版 SKU；若首次受理前目录切版，由权益中心返回版本冲突，营销侧保留失败结果。历史已持久化载荷保持原字节，旧 DTO 未指定版本时仍不输出该字段。该约束固定的是意图组装时的目录版本，活动发布时的 SKU 冻结和最终到账确认仍需后续链路验收。

### 普通营销发奖的履约观察

运营发放列表的“已送达”只代表权益中心受理。选择“查看发放结果”后读取 `GET /api/v1/award-intents/{intentId}/fulfillment`，展示最近成功查询的订单/项状态及北京时间；`POST /api/v1/award-intents/{intentId}/fulfillment:refresh` 仅排队查询，202不表示发放成功。两个接口都要求 `trace:read`，租户来自当前认证；LEGACY/SHADOW不支持，风控拦截无履约订单。

- `MARKETING_AWARD_FULFILLMENT_ENABLED=false` 默认关闭，关闭刷新返回503；独立 `BENEFIT_CENTER_FULFILLMENT_BEARER_TOKEN` 需包含 `benefit.award.read`，且签名租户必须匹配目标租户。上游GET没有POST/目录使用的机器委派白名单，不能把一枚租户令牌当作所有租户的读取凭据；当前本机未验证真实GET权限，保持关闭。
- V13每条CENTER意图同事务建立一条任务。历史意图不会自动扫描补建，首次明确刷新时按原来源补建；不会改写原始出站载荷、投递状态或发起新发奖。查询前核对来源、请求、主体和项身份/数量/金额/SKU版本；不匹配和未知状态均拒绝入库。
- 复用现有耐久任务轮询模式，独立单线程、默认每批5条/每租户2条；30秒租约、查询总时限默认3秒、正文上限128KiB。查询在事务外执行，代次和有效租约共同隔离迟到结果；人工刷新推进代次，不能抢占有效租约。成功默认300秒后再查；失败指数退避与抖动，连续10次后PAUSED，人工刷新可恢复。
- 上游未提供单调业务版本，因此本表保存“最新查询观察”而非永久终态栅栏。404、403、超时不会变成业务失败或取消，也不会覆盖上次成功观察；补偿/撤销后下一次成功查询可更新结果。按来源取消墓碑仍缺上游合同，本功能不执行取消、补发、对账记账或Journey新发奖接线。
- `marketing.award.fulfillment.check` 的 `result` 区分observed/unavailable/lease_lost。监控PAUSED和查询错误后先检查读权限、上游和身份合同，再刷新；不要删原意图或通过改状态绕过校验。观察表不保留主体/完整上游响应，每条意图仅一行；保留期限随父意图治理，在未确认期限前不自动删除。清理须先归档/处理观察子行再处理父意图；容量、长期保留和真实凭据验收仍属于S13。
- 回退时先关闭履约观察开关，旧版本忽略新增表；保留V13和原始记录，不回滚数据库或清空任务。当前实现已用隔离MySQL和本机HTTP合同替身验证，未以真实权益发放代替测试。


### 自动决策轨迹

每次首次成功决策会保存真实决策时间、quoteId、generation、耗时、有界候选结果与报价汇总。`GET /api/v1/decisions/traces/requests/{requestId}` 或 `/orders/{orderId}` 要求当前 `trace:read`，按租户返回决策时间最新、quoteId稳定排序的摘要；不代表订单确认或权益到账。控制台先读取既有聚合轨迹，仅在明确404时补查自动摘要，权限/依赖故障不会隐藏。

JDBC模式在原命令事务中保存V3新增摘要列和索引；Redis模式在原COMPLETE Lua中同slot原子保存完成状态和请求/订单索引。保持原权威存储选择，不双写两种存储；查询窗口沿用24小时，旧命令可重放但无自动摘要时返回404。摘要不包含OfferToken、原始主体、购物车明细；最多200个候选、单标签256字符，截断标识明确；termsVersion未从当前响应获得，显示未提供。仅记录真实DECISION_EVALUATED，不伪造Reserve、Confirm或Exposure。

Redis本身的持久化/容量和JDBC历史命令清理仍按原幂等存储治理；本片不承诺跨24小时法务保留或全链路事件汇聚。新增索引会增加Redis内存，生产容量/恢复仍需S13验收。老服务忽略新增JDBC列；Redis旧代码不生成自动轨迹，回退期间不得把残留摘要当作持续更新。
