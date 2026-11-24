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

已有本地演示库可运行 `python3 test-data/daily-measurement.py init`，为 `retail-cn` 的 `mock-cmp-ha-2026` 补最近 30 个北京时间自然日的事件收入和补贴成本。每天在 00:00、08:00、16:00 各有一笔样本，时间到达后才写入。重复运行会补缺并推进演示水位，适合交给本机定时任务。用 `python3 test-data/daily-measurement.py verify` 核对库内事实与投影，并对账看板和日序列。

该命令需要仅监听本机回环地址的 DEV 网关，以及已执行基础种子的 `dev-infra` MySQL。发现该租户有其他来源的看板投影时会停止。停止定时任务后，如需移除这批样本，可显式运行 `python3 test-data/daily-measurement.py cleanup --confirm-cleanup`。原始演示水位的恢复记录位于 `~/.local/share/marketing-daily-measurement/original-watermark.json`。

## 一期能力

- 控制面：MarketingPlan/Campaign、五类低代码方言、版本、diff、仿真、风险检查、多级审核、ReleaseBundle、灰度、ACK/reconcile、回滚与 kill switch。
- 决策面：不可变规则制品、Drools executable model、DMN、稳定实验分桶、分层定价、兼容/互斥求解、逐行与多出资方精确分摊、签名 OfferToken。
- 履约面：PromotionApplication、ReservationGroup、预算/库存/券状态、幂等 confirm/cancel/refund/reverse 和守恒对账。
- 数据面：事件接入、受众快照与实时 membership、Flink keyed state/timer 旅程、触达同意/静默时段/频控、实验曝光、转化归因与水位。
- 平台面：OIDC JWT、租户/组织/门店范围、RFC 9457 错误、OpenAPI/AsyncAPI/JSON Schema、审计、OTel、Prometheus、Grafana、Docker Compose、Helm 和 CI 安全门禁。

### 控制台规则编写

- 创建活动时实际保存 `STANDARD` / `REFERRAL` 类型；类型尚未确认时关闭编辑入口，避免把标准规则写入裂变活动。
- 活动就绪按本活动 Offer 引用的权益版本和 SKU 核对。已填快照的在线资格尚无读取证据时显示待核实。裂变运营的下拉和直达链接只接受当前范围内类型已确认为 `REFERRAL` 的活动。
- Offer / Journey 支持按节点配置金额、比例、日期、权益版本、条件、等待和触达参数。通过「出口连接」或画布连线连接显式节点；不会自动补入隐藏结束节点。Offer 当前仅支持定额/比例与完整单链，不开放尚不可执行的条件分支。
- 保存、校验、试算与审核绑定同一修订；继续编辑会使原校验失效。保存成功但校验失败时保留版本和输入，可重试校验。
- Offer 金额试算使用实际输入的金额/币种，不验证库存、时间或领取资格；有商品/人群等范围时由后端拒绝缺上下文的试算。资源发放台账不等于外部权益到账。
- Journey 保存校验后可使用「旅程仿真」：输入初始变量、按时间排序的事件和模拟窗口，复用实际 JourneyCompiler/JourneyRuntime 推进条件、事件等待和计时器，展示每次信号的状态及拟生成动作。POST `/api/v1/definitions/{definitionId}/versions/{version}:simulate-journey` 要求 `definition:simulate` 和原定义数据范围；不会落库、写 Outbox 或发送/发奖。输入变化会隐藏旧结果，旧通用金额仿真入口拒绝 Journey。
- Journey 仿真正文最多64KiB、32事件、每字典32项，窗口最多7天；最多64次信号、总配置步数10000，达到预算返回 `TRUNCATED`。同刻先计时器后事件；保留现有图校验和运行时限制，生产调度延迟、处理时间TTL、外部资格与完整循环发布不在模拟范围。
- 浏览器合同验收：`pnpm --filter @marketing/console exec playwright test lowcode-authoring.spec.ts`。可选真实本地验收：设置 `MARKETING_LIVE_UI=1`，使用 `--project=chromium --grep 'complete explicit Offer|Journey timer' --workers=1`；需要本机18080网关、DEV身份及真实权益目录，会在dev-tenant创建隔离活动和定义，不执行审核/发布/发奖。

### 人群数据边界

权益资产使用 `GET /api/v1/benefits/catalog/page` 分页读取最新版本，支持名称、权益ID、版本、绑定SKU/未绑定提示、状态中英文及北京时间创建时间的服务端包含搜索。路由单独放在catalog/page，保留包括ID为page的既有权益详情读取。每页默认25、最大100，按权益ID稳定排序；页面支持加载更多、重试、刷新，只报告已加载数量，详情和新版本编辑入口不变。该查询不访问外部SKU服务、不改变库存或发放。

营销资产使用 `GET /api/v1/audiences/page` 分页读取每个人群的最新版本（默认25、最大100），按人群ID升序。搜索在服务端覆盖名称、ID、版本、规则指纹、状态中英文和北京时间创建时间；百分号与下划线按字面匹配。页面支持加载更多、失败重试、刷新和完整详情，只显示已加载数量。分页是当前读取，新增较小ID需刷新看到；旧数组接口继续兼容，会员画像接入边界不变。

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

运营发放列表分别显示投递状态、最近发放结果和核验时间。权益中心受理后仍需核验实际发放结果。分页列表一起读取已保存的履约摘要，最近查询失败时保留上次核验结果。选择“查看发放结果”后读取 `GET /api/v1/award-intents/{intentId}/fulfillment`，展示最近成功查询的订单/项状态及北京时间；`POST /api/v1/award-intents/{intentId}/fulfillment:refresh` 仅排队查询，202不表示发放成功。两个接口都要求 `trace:read`，租户来自当前认证；LEGACY/SHADOW不支持，风控拦截无履约订单。

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

### 发布目标与回滚恢复

发布中心在每条清单行选择激活、回滚或命名空间熔断。`GET /api/v1/releases/{manifestId}/rollback-candidates` 要求 `release:read`，只返回当前 ACTIVE/desired 清单真实保留、曾激活、未过期且在当前身份范围内的目标（最多 10 个）；候选读取不创建发布槽。页面展示具体代际、定义和有效期，提交时服务端再次检查状态与槽位，不推算“上一代”。

激活、回滚和熔断的幂等回执也重新检查当前操作权限，回滚同时检查源和目标的组织/店铺范围。回滚遇到断网或 5xx 时，页面保留原清单、目标代际和幂等键；即使重连后源已变为 ROLLED_BACK、候选查询返回 409，仍可“重试原回滚请求”确认原结果，不新建第二条指令。

Kill Switch 的原因随原签名指令保存，其范围是当前租户的整个命名空间，不局限于所选清单的环境、部署单元或运行类型。激活/回滚显示实际记录的操作人，不再收集未被接口保存的原因。完整原因审计、组合发布与定时执行仍待后续实现。

### DMN 工作台分析与试算

`/designers/dmn` 从空表开始编辑输入/输出字段及规则，通过真实 KIE 引擎分析覆盖缺口与重叠、运行测试输入。分析调用 `POST /api/v1/dmn/analyze`（`definition:compile`），求值调用 `POST /api/v1/dmn/evaluate`（`definition:simulate`）；网关复用编译服务路由与时间预算。两者都没有持久化、发布或权益副作用。

选择活动后可保存及恢复真实草稿版本。控制服务 `POST /api/v1/campaigns/{campaignId}/dmn-drafts` 使用 `definition:write`、`Idempotency-Key` 和 `expectedVersion`（首次为 0）；读取 `/latest`、`/versions/{version}` 使用 `definition:read`。V5 迁移新增 `mk_dmn_draft_version`，沿用当前活动的租户/组织/店铺范围，借活动行锁串行分配不可变版本，草稿/幂等回执/审计同事务提交。相同当前内容不产生空版本；过期基准返回 `409 DMN_DRAFT_VERSION_CONFLICT`，页面保留输入，明确确认后才加载最新。旧幂等回执也要重新验证当前访问权。不存在版本返回 `404 DMN_DRAFT_NOT_FOUND`，不会把一般网络或权限错误解释为空白草稿。

保存允许未填写完整的名称/单元格，结构仍限 1–4 输入、1–4 输出、1–32 规则、名称 32 字符/单元格 256 字符；正文解析前限 512 KiB。测试输入和计算结果不持久化，保存不代表语义通过。首次绑定尚无草稿的活动会保留已编辑内容；保存期间继续编辑仍显示未保存；演示模式禁止保存数据库版本。重新打开页面后选择相同活动可恢复最新草稿，界面显示基准版本、保存人及北京时间。DMN 草稿尚未接入审核/发布或在线事实映射，不因保存成功改变运行规则。

请求中的 `table` 包含 `hitPolicy`、`inputs`、`outputs`、`rules`，求值额外传 `context` 字符串字典。当前支持 UNIQUE / FIRST、1–4 输入与 1–4 输出、最多 32 规则；列名为唯一 ASCII 标识符，不能用模型/决策名称及 FEEL 保留字。单元格最多 256 字符，仅允许通配 `-`、单个数字比较、字符串或布尔等值；输出仅标量字面量。数字最多 18 位数字和 6 位小数，不接受公式、函数或任意 XML。测试上下文逐列匹配类型；文本不加引号，规则内的文本使用 JSON 双引号。

服务端先鉴权，再限制请求正文为 512 KiB（包括无 Content-Length 的分块传输），之后严格解析 JSON 并生成转义 XML。超过限制返回 `DMN_REQUEST_TOO_LARGE` 400，非法结构/类型使用稳定 `DMN_*` 问题码。单进程工作台同时只执行一次计算，繁忙返回 `DMN_WORKBENCH_BUSY` 503；指标 `marketing.dmn.workbench` 仅使用 operation/result 低基数标签。KIE 模块在编译或求值完成后移除，容器/校验器释放。此限制不等于进程沙箱或超时后的强制终止，生产混合负载与隔离仍须独立验收。

返回真实引擎诊断和上下文，精确数字递归编码为十进制字符串，客户端不以浮点数重算。无命中可返回 `success=true` 但决策值为 null，页面显示“无结果值”；合法文本 `"null"` 和空文本保留引号。修改规则会使旧分析/结果失效，迟到结果被忽略；修改测试输入只失效试算结果。规则键盘重排保持焦点跟随同一规则，FIRST 顺序以当前表格为准。

### 补贴账本核对

「运营与客服 → 资金对账 → 补贴流水」可按北京时间入账范围、活动或待归属筛选。「衡量分析 → 核对补贴流水」携带当前活动和时间范围。页面按币种累计已加载净额，表格保留原始资金申请 ID、流水 ID 和出资账户。

`GET /api/v1/funding/subsidy-postings` 需要 `funding:reconcile`，同时校验资金申请的组织和所有店铺。窗口包含起点、排除终点，最长 31 天。使用 `nextCursor` 在同一范围内继续读取，分页是实时查询。只返回预算确认、退款和冲正，退款和冲正按实际入账时点以负分值返回。金额为精确十进制字符串。组合报价在验证参与凭证后签入活动 ID，预留事务保存该归属。旧申请和未绑定组合的申请在列表显示「待归属」。

V14 扩展资金库，兼容旧流水小数秒精度。部署时先完成数据库扩展，再升级资金服务和决策服务，然后升级控制台。资金流水可与现有事件投影并行核对，报表口径切换和历史补录在对账通过后独立进行。

### 历史补贴差异核对

「衡量分析」支持自定义北京时间，最长 366 天。账本对账最长 31 天，点「读取账本汇总」按同一活动及起止时间读取事件成本和账本净补贴，并显示各自读取时间。按活动查询时，同一时间窗口内可见的待归属流水独立列示；全部活动时账本侧包含已归属和待归属。

`GET /api/v1/funding/subsidy-postings/summary` 沿用资金对账权限，按授权范围及币种返回精确净分值和流水笔数。全租户身份还会收到 `unmatchedPostingCount`，表示该窗口内缺失有效资金关联的预算流水及无法确认资金类型的流水笔数。差额要求事件水位已覆盖、资金权限为全租户、资金关联齐备、币种可比，并满足所选活动的归属条件。可通过「核对补贴流水」继续查看明细。差额可用来排查重复成本、入账时间和来源覆盖；CSV、事件补贴成本和 ROI 仍使用现有报表口径。

`POST /api/v1/promotion-applications/{applicationId}:attribute-campaign` 需要 `funding:attribute` 及申请组织和所有店铺的权限。请求体的 `offerToken` 是原始签名报价。报价需匹配申请保存的摘要和标识、通过原预留时点的验签，并有签名活动声明。首次成功记录操作者和时间，同一凭证重试返回首次结果。缺原始报价、签名活动或可信公钥时，流水继续显示待归属。V15 增加审计字段，补录只写资金申请归属和审计字段。已入账的金额、流水 ID 和退款日期沿用原值。

总览、衡量分析和 CSV 标明当前金额及 ROI 来自事件汇总。归因重算写入归因明细，报表的实付收入仍需与交易中心凭证核验，补贴成本仍可与资金账本并行核对。汇总金额字段缺失时控制台报读取失败，只有接口明确返回 0 才能按零值展示。

「计划与活动 → 查看报表」打开相应活动的衡量分析。活动范围由当前身份确认，再读取报表与资金对账。日序列请求 `dayOffset=%2B08%3A00` 以北京时间零点分桶，起止时间仍为包含起点、排除终点。服务端回显实际分桶偏移，旧测量服务的 UTC 日分桶在页面及 CSV 中标明。

### 分析报告范围与导出

`/analytics` 使用同一组 `from/to` 查询 dashboard 和 series，选今天（北京零点开始）、滚动7天/30天或刷新时更新窗口；两个响应都成功后才显示与导出。身份变化、刷新中或任一请求失败时禁用导出并隐藏旧值，可直接刷新恢复。重算按钮作用于当前租户全部归因，不受页面窗口约束。

「导出当前报告」下载UTF-8 CSV，包含租户、起止时间、粒度、读取时间、投影水位、汇总原始分值、事实计数和完整返回序列；不会在点击时另查出不同数据。字符串防CSV公式注入，金额保留分值与两位展示，超出JS精确整数范围时明确拒绝。两接口共享窗口不代表数据库原子快照；日序列沿用后端UTC自然日分桶，界面和CSV显示北京时间。所选范围无覆盖或水位未知时显示“数据未就绪”。水位只覆盖一部分时，可选择“查看已覆盖范围”，用原起点和水位作为新窗口重新查询两接口。只有完整覆盖的查询可展示正式金额、趋势和导出；首页同样按水位保护今日金额。净补贴成本非正时，控制台和 CSV 显示 ROI 不适用。

活动筛选读取有权访问的真实活动列表（需 `campaign:read`），dashboard与series同时按可选 `campaignId` 精确过滤当前租户投影；未传包含未关联活动的事实。两响应回显实际 `campaignId`，旧服务没有确认所选活动时页面拒绝显示和导出。活动ID与窗口写入CSV；归因策略筛选仍未实现。分析页不内置营收、实验或归因样本。静态DEMO仅提示连接数据源，演示事实/投影使用 `scripts/seed-data/system-mock.sql` 写入隔离库后通过真实接口读取。浏览器合同验证：`pnpm --filter @marketing/console exec playwright test analytics-report.spec.ts`；设置 `MARKETING_LIVE_ANALYTICS=1` 可对本机18080执行只读GET及下载验收，不触发归因重算。

活动筛选新增测量服务Flyway V5索引 `(tenant_id,campaign_id,occurred_at)`，保留原全租户时间索引。未知活动返回零值，不做跨服务活动查找；空白、周围空白、控制字符或超过128字符返回 `MEASUREMENT_CAMPAIGN_FILTER_INVALID`。水位仍表示整个租户的投影进度。后端升级前，旧服务上的全部活动报告继续可读，选择活动会明确失败而非静默显示全量。


### 营销资产与消息模板版本

营销资产的人群、权益记录可直接进入对应 ID 的设计器；人群链接失效时明确提示，不加载另一条记录代替。消息模板分类通过 `GET /api/v1/templates/catalog/page` 分页读取最新版本（默认25、最大100），按模板ID升序。服务端搜索覆盖ID、v版本、渠道、必需变量、状态中英文和北京时间创建时间；变量按持久化顺序显示。支持更多、失败重试、刷新、完整正文详情、新建模板和基于已有内容保存新版本，保存后刷新当前搜索，旧数组接口保留兼容。正文按纯文本展示；页面不内置业务模板。

`POST /api/v1/templates` 沿用 `template:write`、`Idempotency-Key` 和不可变 `(tenant, templateId, version)`；旧成功回执也检查当前写权限。版本重复返回 `409 TEMPLATE_VERSION_EXISTS`，旧正文不变；同键同载荷重试回原结果，同键不同载荷仍冲突。保存沿用现有 `APPROVED` 状态，不增加审批流程、不发送消息，也不自动替换 Journey 引用的版本。渠道标识须与运营配置一致，保存模板不等于验证外部通道可用。

输入边界：模板 ID 1–128字符、渠道1–32字符且无空白/控制字符；版本为正安全整数；正文1–8000字符且非全空白；必需变量最多32个，每个1–60位字母、数字、下划线、点或短横线，以字母或下划线开头。EMAIL须包含 `unsubscribe` 说明；输入错误和版本冲突保留页面草稿。未改载荷的失败重试沿用同一幂等键，改内容才换键；保存中防止关闭/重复点击，未保存输入关闭时提示。模板管理在无写权限或DEMO模式不执行写入。

浏览器验证：`pnpm --filter @marketing/console exec playwright test template-management.spec.ts`。设置 `MARKETING_LIVE_TEMPLATE_ENDPOINT` 为已授权的隔离触达服务地址可验证真实MySQL双版本保存；该用例只创建模板，不发送消息。不要将其指向生产。

### 审批分页与可见范围

审核队列与发布向导使用 `GET /api/v1/approvals/page`，按当前租户、组织、门店和审核状态在数据库筛选后翻页。`limit` 为1–100（默认25），`status` 可选OPEN/APPROVED/REJECTED，`cursor` 使用上一页返回的 `nextCursor`；`hasMore=false` 表示本次遍历已到底。界面的“已加载”数量不是全部记录或今日汇总，待审导航超过100条时显示 `100+`。

游标按提交时间与审核单ID排序，审核更新时间变化不会移动游标。分页期间的新增和状态变更不构成冻结快照，刷新会从第一页重新核对；游标失去可见权限时返回 `APPROVAL_CURSOR_INVALID`。旧 `/api/v1/approvals` 数组接口仍保留最近100条兼容读取，但同样先按可见范围筛选。V6只新增分页索引，无业务数据回填或删除。


### 发布清单分页与可见范围

发布中心通过 `GET /api/v1/releases/page` 按当前租户、组织、门店以及可选 `state` 在服务端筛选，支持加载更多、失败重试和从首项刷新。`limit` 默认25、范围1–100；`cursor` 为当前范围可见的清单ID，按不可变创建时间和ID降序定位，状态变化不会单独使游标失效。响应包含 `items/nextCursor/hasMore/state`；这是实时遍历，新增或状态变化后应刷新，不把已加载数当总量。原数组接口保留，最多返回50条可见清单，权限过滤先于截断。

所有页和回滚候选缓存绑定完整身份与权限范围；权限切换后清除旧页及操作目标。列表查询只需 `release:read`，逐行激活、回滚和命名空间熔断仍沿用各自权限、确认和幂等规则。演示模式不读取或生成发布记录，本分页不增加组合发布、定时激活或真实业务写入。


发布向导暂存后使用 `GET /api/v1/releases/{manifestId}` 定向读取本次清单和当前 ACK，不依赖最近50条列表；每次仍检查 `release:read`、租户和定义的组织/门店范围。权限缺失返回403，组织/门店范围拒绝沿用400 `INVALID_ARGUMENT`，跨租户或不存在返回404。读取失败或刷新期间禁用激活，读取接口不会生成 ACK。

首页、侧栏和活动就绪检查复用按完整身份范围隔离的发布分页缓存。首页明确展示已加载数，侧栏以 `+` 标识仍有后续已激活清单，不把清单数当发布槽数。活动就绪支持继续核对和失败重试；未读完、无权限或读取失败时保持“待核实”，只有完整读取后才判定未发现对应版本的暂存/激活清单。缺省定义在查询缓存中表示为 `null`，避免空资源被误报为请求失败。

活动列表的组织/门店过滤遵守身份范围的精确大小写匹配；`*` 字符串权限分别表示该维度全域访问。空组织不返回活动，空门店只允许当前组织的无店铺活动；类型筛选与租户隔离始终保留。

非DEMO首页以中性标题展示接口数据，活动卡计数为“已加载活动”。水位覆盖部分今日区间时，首页查询并展示北京零点至水位之间的金额和小时序列，同时提示今日数据尚未齐全。水位延迟缺省为未知，长时长以天/小时/分钟展示并保留原始秒数。金额保留分，零收入不绘制正高度柱。含负收入的窗口改为有符号小时明细表，避免正值柱图误导。


### 历史 Journey 等待节点

已保存的 `journey.wait_timer@1.0.0` 可在 Journey 设计器中点击“转换等待节点”，显式替换为当前 `journey.wait-timer@1.0.0`。转换保留节点 ID、配置、端口、连线、变量、条款和定义 ID，支持撤销/重做；加载时不会自动改写。需要写权限且当前注册表明确支持目标节点，未知语义版本继续拒绝自动转换。

保存使用既有不可变版本 API 追加草稿，相同语义重试复用版本；校验通过后才可仿真或重新提交审核。原版本、审批、发布清单和运行中实例保持不变。不能通过重灌种子覆盖历史定义，转换也不等于已经发布或触达。
