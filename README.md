# Marketing Low-code Platform R1

面向大型零售电商营销场景的完整 R1 参考实现：Java 21、DDD、微服务、事件驱动、React 低代码工作台、Drools/DMN 规则执行、Flink 实时受众/旅程/衡量，以及生产候选级的身份隔离、发布回滚、可观测和部署资产。

> 当前结论是 **production candidate / conditional-go**。代码与部署资产可以进入企业环境验证；真实上线仍必须完成目标环境压测、灾备演练、资金不变量验证、安全扫描、密钥接入和法务合规签字。本仓库不声称已达到京东真实规模。

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
| 营销控制台 | http://localhost:3000 | 默认 DEV 身份，完整菜单与设计器 |
| API Gateway | http://localhost:8080 | REST 统一入口 |
| Flink Dashboard | http://localhost:8081 | 3 个流作业 |
| Keycloak | http://localhost:8180 | 本地 OIDC；管理员密码见 `.env` |
| Grafana | http://localhost:3001 | 可选；使用 `--observability` 启动 |
| Prometheus | http://localhost:9090 | 可选；使用 `--observability` 启动 |

开发模式的参考租户是 `retail-cn`，组织为 `retail-business`。Keycloak realm 预置操作员 `operator` / `local-operator-change-me`，仅用于本地演示，绝不可用于共享或生产环境。

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
