# 验证证据

日期：2026-10-18；基线 `1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c`；源码提交 `6e3a1962080b744dc33de3c05c9113028c4e0c6a`。本目录 [source.sha256](source.sha256) 固定本轮全部变更代码与测试；交付文档后续更新不改变该源码指纹。

## 最终验证

| 检查 | 实际命令/范围 | 结果 |
| --- | --- | --- |
| Maven reactor | `./mvnw -o -B -ntp verify` | 30 个模块构建成功，563 测试，0 失败/错误/跳过 |
| 最后增量复验 | `./mvnw -o -B -ntp -pl architecture-tests -am -Dtest=OfferSimulationTest,OfferReleaseGateTest,RuleCompilerServiceTest,ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false test` | 23 测试通过；用于常量、错误目录、基线资源改名后的验证，不与 563 相加 |
| 前端 | `pnpm frontend:lint`、`pnpm frontend:test`、`pnpm frontend:build` | lint 无警告、26 文件/87 测试、TypeScript/Vite 构建通过 |
| 浏览器 | `pnpm frontend:e2e` | 桌面/移动 Chromium 共 24 通过；API 路由夹具，非真实后端全链路 |
| 合同 | `./scripts/verify-contracts.sh` | 13 JSON 契约；18 合同测试已计入 reactor |
| hygiene | `code-hygiene.py gate --workspace <repo> --base 1392ca9`，附依赖清单和实际 COMPILE/UNIT_TEST/STATIC_ANALYSIS PASS 证据 | 0 findings；缺仓库统一 formatter，周边风格/差异检查通过，IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS |
| 用户文件保护 | 比对 unrelated-before.json 中 SHA-256 | 48 文件全部相同 |

最后浏览器回归之后仅做常量与既有 Zod 枚举引用整理；对应单元测试、lint 和构建已重跑。最后全仓 verify 之后仅整理 Offer 常量/错误目录及改名架构基线资源，已由增量复验覆盖。

## 后端各模块测试数

| 模块 | 测试 |
| --- | --- |
| platform-common | 10 |
| platform-web | 11 |
| marketing-contracts | 18 |
| lowcode-language-core | 9 |
| decision-runtime-spi | 9 |
| provider-connector-spi | 2 |
| journey-runtime-spi | 7 |
| referral-runtime-spi | 93 |
| drools-runtime-adapter | 2 |
| dmn-runtime-adapter | 1 |
| edge-gateway | 1 |
| marketing-control-service | 21 |
| rule-compiler-worker | 21 |
| audience-service | 6 |
| offer-decision-service | 8 |
| benefit-funding-service | 80 |
| event-gateway-service | 7 |
| journey-service | 2 |
| engagement-service | 4 |
| measurement-service | 2 |
| referral-service | 226 |
| flink-job-support | 1 |
| audience-flink-job | 1 |
| journey-runtime-flink-job | 7 |
| measurement-flink-job | 4 |
| architecture-tests | 10 |

## 真实存储与恢复覆盖

- JourneyRuntimeReaderTest 使用隔离 MySQL 8.4.11 和真实 V1/V2 迁移，验证三个查询、租户/单元/版本约束、断开数据库时基础设施异常、有界超时参数。
- JourneyRuntimeRecoveryTest 验证事件/定时器基础设施异常不写业务死信、不消耗定时器业务重试；恢复后重放成功；坏 JSON 仍隔离；初始化配置错误按基础设施错误处理。该证据不是 Flink 集群 checkpoint 实测。
- DecisionPersistenceIntegrationTest 验证真实主键查询、同时间戳/迟提交、过期/旧版本/跨租户，以及真实 DecisionRequestExecutor 事务内另一线程提交撤销后 TTL 过期可见，覆盖 READ_COMMITTED 与 MyBatis 会话缓存。
- Audience API 集成覆盖日期和非法有序比较；Offer 门禁测试覆盖旧已审批/STAGED 源图不能继续发布；浏览器真实连 false 分支并断言保存后的图元数据。

## 对抗复审

依据工程工作流，单 Agent 实施后由三个只读 reviewer 分别审查 Offer、存储恢复、图与类型。以下问题已补修并经二次复查通过，未留新增阻塞：

| 问题 | 修复及证据 |
| --- | --- |
| 旧 basisPoints 制品可从旧审批状态继续激活 | 新版制品 ID 前缀受既有签名覆盖；stage/ACK/activate/rollback 共用源图与重编译门禁 |
| SHIPPING/范围优惠缺完整试算事实 | 返回 OFFER_PREVIEW_CONTEXT_REQUIRED；OfferSimulationTest |
| 跨阶段重复 offerId | 同源候选校验拒绝；OfferSimulationTest |
| Journey 初始化错误被当坏事件 | 统一 JourneyInfrastructureException；配置错误回归 |
| RR/会话缓存回填已撤销人群 | READ_COMMITTED + flushCache；真实并发事务回归 |
| 画布没有运行时具名出口 | 端口矩阵及双端浏览器连接/保存断言 |

## 首轮失败与闭环

- 扩展架构检查后暴露裂变既有 274 处依赖，归一化 231 项冻结基线，后续新增依赖失败；没有删掉新增范围来伪称旧债消除。
- 首轮浏览器 4 个失败来自两个过期夹具（空制品对象、已移除提示）；修正后 24/24 通过。
- 测试初版 Flink ValueStateDescriptor 重载歧义和 Testing Library 不支持的 exact 参数已修正，后续编译/构建通过。
- 原有 lint 混合导出 7 警告已通过拆分工具函数消除；hygiene 的常量/依赖登记问题均已关闭。

## 依赖与边界

沿用已有版本和组件：MyBatis 3.5.19 与既有服务 starter 对齐，Mockito/Testcontainers 为现有测试依赖；lowcode-language-core、decision-runtime-spi 及四个被检查模块为仓库内部模块。maven-shade-plugin 原已存在，因 POM 行调整被门禁当新增，已登记复用。未更新整体技术栈；未做新增供应链漏洞扫描。

## 原始日志索引

本机日志目录：`/var/folders/fh/4kj3hbdd6q1dbswkdz5l_fsr0000gn/T/marketing-architecture-fix-20261018-65r8nhuz`。以下 SHA-256 用于核验留存原始日志；长日志没有提交到仓库。

| 文件 | SHA-256 |
| --- | --- |
| final-verify.log | `fc9040a16e720158eb92bd43217b102b68d4f006d0de793a13726d441e191916` |
| post-hygiene-backend.log | `1a5286035c741cff76b398bb8fde32b9761340620e55b527610d7ae47689ebb3` |
| post-hygiene-frontend.log | `ac0fc20f059a5273df4edb90d711364c4d0c83cc1d26735dc61bc2f003ae81b3` |
| post-hygiene-build.log | `616686d38c2a30d14d210ade9b9d9d0d0fedec67f820836856bb36118950c3d0` |
| frontend-e2e-2.log | `659b0746e8728b19a684e8afb21a45772d5feafe3b2345008a4e2de83613211b` |
| contracts.log | `273a80a02c1e2b26a52e70b52509e8a2d9c67edd9bb32a5b3b28a82be6595fd9` |
| hygiene-final.log | `8b6ba6ced09a08b1c3905d77ad7bfabcadbd22760be585ca49e55c1942041503` |
| unrelated-before.json | `782ddfe6981979a1c03dd9e03e2d779a35366200c5010e9f1c2cb94e87a13f9e` |
