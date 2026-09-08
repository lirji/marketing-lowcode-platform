# Marketing Low-code Platform R1

面向大型零售电商营销场景的完整 R1 参考实现：Java 21、DDD、微服务、事件驱动、React 低代码工作台、Drools/DMN 规则执行、Flink 实时受众/旅程/衡量，以及生产候选级的身份隔离、发布回滚、可观测和部署资产。

> 当前结论是 **production candidate / conditional-go**。代码与部署资产可以进入企业环境验证；真实上线仍必须完成目标环境压测、灾备演练、资金不变量验证、安全扫描、密钥接入和法务合规签字。本仓库不声称已达到京东真实规模。

新增裂变能力正在分阶段实施，当前能力、专项证据和未开放门禁见[裂变后端实施导航](docs/REFERRAL_IMPLEMENTATION_STATUS.md)。完整裂变尚未达到上述R1生产候选结论。

## 五分钟体验

前置条件：Docker Desktop、Java 21、pnpm 9，以及同级目录的 `dev-infra`。数据库与中间件统一复用 `dev-infra`；首次启动会生成仅本机可读的 `.env`、初始化隔离资源并构建业务镜像：

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
MARKETING_USER=marketing PASSWORD='本地强口令' \
  bash ../auth-platform/deploy/marketing-platform-provision.sh

# 叠加 Casdoor OIDC 后重建控制台与网关
bash deploy/compose.sh --secure up -d --build
```

门户正式入口是 `http://localhost:8084/login?returnTo=%2F`。OIDC 只接受组织 `marketing-platform`，并校验派生 clientId；未知组织不会跳转 Casdoor。生产 catalog 在域名与 Casdoor 回调登记完成前保持 `coming-soon`。

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

## 一期能力

- 控制面：MarketingPlan/Campaign、五类低代码方言、版本、diff、仿真、风险检查、多级审核、ReleaseBundle、灰度、ACK/reconcile、回滚与 kill switch。
- 决策面：不可变规则制品、Drools executable model、DMN、稳定实验分桶、分层定价、兼容/互斥求解、逐行与多出资方精确分摊、签名 OfferToken。
- 履约面：PromotionApplication、ReservationGroup、预算/库存/券状态、幂等 confirm/cancel/refund/reverse 和守恒对账。
- 数据面：事件接入、受众快照与实时 membership、Flink keyed state/timer 旅程、触达同意/静默时段/频控、实验曝光、转化归因与水位。
- 平台面：OIDC JWT、租户/组织/门店范围、RFC 9457 错误、OpenAPI/AsyncAPI/JSON Schema、审计、OTel、Prometheus、Grafana、Docker Compose、Helm 和 CI 安全门禁。

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

## 关键边界

- 平台不会重造商品、订单、支付、会员主数据或商业短信/Push 平台；通过版本契约、防腐层和连接器对接。
- “exactly-once”只描述 Flink 状态与兼容 sink 的处理保证。任何真实发券、资金或渠道副作用仍以业务幂等键、账本和补偿实现。
- 决策只报价，不直接扣预算或发权益；资金和权益状态只由 Benefit/Funding 上下文改变。
- DEV headers 默认仅允许本地 Compose。生产 Helm 在模板阶段拒绝非 OIDC 模式。

### 权益中台跨系统键

- `tenantId` 来自已验证 token owner，拒绝客户端 header 覆盖。
- `subjectRef` 是用户稳定引用，与 Casdoor `sub` 或营销 subject token 的映射由 crosswalk 管理。
- 营销版本键为 `campaignId + definitionVersion`，权益资产键为 `benefitSkuId + skuVersion`。
- `sourceRequestId` 是营销到权益中台的跨系统幂等键。
- Benefit/Funding 通过 `BENEFIT_CENTER_BASE_URL` 查询 tenant 隔离的 SKU 目录；发布 ACTIVE 权益时实时校验模板仍为 ACTIVE。
- AwardIntent 使用服务端签名 OfferToken 重建资格、SKU 与金额；内部触发接口不接受金额字段，运营侧仅通过 `GET /api/v1/award-intents?campaignId=` 查看状态。
- AwardIntent 在落 outbox 前以 `sourceId=MARKETING_AWARD` 调用 risk-platform；CASH 使用服务端组装后的最小单位金额，非 CASH/LEGACY 使用 `amount=1,currency=XXX`。业务拦截写 `mk_award_intent_block` 并返回 202，超时、5xx 或降级挑战写 `UNAVAILABLE` 后返回可重试的 `RISK_UNAVAILABLE` 503，均不会写 outbox 或应发事实。
- 发放路由默认 `LEGACY`（也可设 `SHADOW`）；只有 `MARKETING_AWARD_TENANT_MODES=tenant-a=CENTER` 显式命中的租户才进入中台 outbox，且还需开启 `MARKETING_AWARD_RELAY_ENABLED`。模式在首次入队时固化，切换配置不会让旧请求改道。
- 迁移期营销与 drools 都使用稳定的 `sourceSystem=drools-activity` 配合相同 `sourceRequestId`，即便交接误重叠，权益中台仍会按同一幂等身份拒绝第二份不同 payload；正常切流仍必须先关旧租户 authority，再开 CENTER。
- CENTER 入队与 `marketing.award-expected.v1` 应发事实同事务提交；Relay 用租约版本 fencing、单租户批量上限、熔断和原幂等键重试，成功后回填 `benefitOrderNo`。回滚时先清空对应租户的 CENTER 映射，再关闭 Relay，保留 PENDING/DEAD 行供审计和恢复。
