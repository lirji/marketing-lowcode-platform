# 项目补齐实施进度

## 目标与授权
- 用户于 2026-11-16 确认“按照你的建议顺序来执行”：先正确性/启动/CI，再真实运营链路、裂变和高级能力；不将完成一个切片等同于整个项目完成。
- 基线 `main/082e9fed1d90b7784780d68e91eedb2409ee090e`，原工作树干净。当前仓库 `/Users/liruijun/personal/LLM/marketing-lowcode-platform`。
- 允许当前仓库代码、测试、文档及本地验证；按用户持续授权使用任务分支、逻辑提交、正常合并推送远程 main。生产部署、共享数据清空、其他仓库修改不在授权内。
- 审计证据：`/Users/liruijun/.local/share/marketing-audits/20261116-completeness/`；Docker 启动与恢复证据：`/Users/liruijun/.local/share/marketing-deployments/20261116-start/`。
- 规范：`~/.codex/architecture-standards.md` 业务建模、持久化、接口契约、并发安全；`personal-tech-stack.md` 并发、线程池、日志、状态机；engineering-workflows/backend-implementation/continue-approved-delivery/task-git-delivery。前端、Runtime 专项随切片读取。

## 前序交付与本机部署记录（截至 2026-11-23 本轮开始前）
- 用户已确认同一活动组合整体生效/回滚，并要求继续流程；会员/画像优先Commerce、交易证据优先交易中心、认证Auth的方向进入增量接入设计。具体技术合同和机器凭据仍按真实系统核对，不再重复询问来源选型。当前分支 `feat/activity-release-bundles` 基于 `d57a572`，S11e/f/g1–g4a已交付，g4b运行端slot保护精确CI通过，g5a Flink固定参与执行本地验证通过，继续真实准入与整体应用闭环；设计与切片见 [活动组合增量设计](ATOMIC_RELEASE_DESIGN.md)。
- 先执行依赖齐备的S11e不可变组合准备与成员发布保护，再推进运行端和整体切换；上游缺少机器快照/主体映射的部分保留依赖状态。当前未修改其他仓库、未部署或迁移共享库。文档提交 `d57a572` 的CI `44095478630` 已核实三项SUCCESS，原pending记录失效。
- S01–S05已完成；S06–S13仍按下表保留未完成范围。S12c–S12q已交付；上一轮已部署产品提交 `dbe268b5ed25004c5ee35af171ceb147e2509e55` 已正常快进合入并推送main，精确CI `44092826351` 的Java/合同/架构、前端、Compose/Helm三项全部通过。该阶段分支为 `fix/asset-preview-reads`，其后交付回执仅改文档，运行制品仍固定该产品提交。
- 本机仅18个必要容器运行：13个marketing-r1、权益目录适配服务、MySQL/Redis/Kafka/ClickHouse；15个配置了健康检查且全部healthy，另3个未配置健康检查，全部运行且无OOM。console=dbe268b、edge-gateway=7764526、engagement=166dc43、benefit-funding=607542e、audience=a260865、control=b3858e6，其余应用045dfd9。control V7已迁移；UI8084、网关18080、Flink18081。详见 [本机部署回执](LOCAL_DEPLOYMENT_20261117.md)。
- 用户授权Docker只运行本项目组件；13个额外无关容器及原17个langchain应用保持停止，所有容器与数据卷保留。本轮只更新console，17个其它运行容器ID/镜像/启动时间不变；3个原Flink作业及检查点继续推进，禁止重复恢复或提交空作业。
- 上一轮部署验证：271项前端测试、98项浏览器用例通过，10项既有独立服务用例跳过；lint/build/Hygiene无阻塞，未配置canonical formatter。真实Docker四类资产页在桌面与Pixel7的分页、搜索、详情及字段/节点读取回归通过，无业务写入、API失败或页面异常。运行回执与截图：私有证据目录 `runtime-dbe268b/`。
- 上一轮部署可见结果：活动/首页/运营/资产的静态预览不显示假业务数据或旧缓存，资产刷新不能绕过读取门禁；OIDC身份范围变化、退出与过期清理旧请求/缓存/草稿，旧令牌401不退出已更新令牌，远端退出失败能恢复登录页。本机仍为DEV认证，真实IdP联调未验证。
- 2026-11-23 恢复核验：g6b3b 已正常合入并推送 main，精确提交 323688e 的 CI 44143684715 三项通过。S11g6b3c 真实 checkpoint 首验通过，后续结果及恢复顺序见文末。

## 当前交付结果：活动组合发布

用户要求先完成 S11g–i。基线 main/846b9be，复用 feat/activity-release-bundles。验收依次为逐副本精确执行取证、Journey 与 Decision 联合验证及原子准入切换、Control 可恢复操作与回滚、控制台真实操作、定时到期重验和暂停恢复。验证使用隔离实例，不触及原共享作业或正式部署。本机 S11g–i 整链验收通过。真实 Chromium 从空活动创建 v1 并激活，两个独立 Decision 进程通过各自签名验证，共享库保存同一参与。排期暂停后到点未执行，恢复后应用 v2，再以序号 3 回滚到 v1；旧参与和新参与随后均由实际 Flink 作业执行固定的定义并写回 Journey。最小部署配置的解析问题已修复。权益资金服务、MySQL 和 Kafka 均运行真实实现，跨仓 SKU 目录使用隔离 HTTP 夹具；XXL-JOB 触发按仓库脚本接入，正式 IdP、密钥、流量观测与生产调度未部署。

产品及验收提交 `57da27a` 的五项 CI 全部通过，覆盖后端全量、控制台、组合端到端、独立 Journey checkpoint 和部署渲染。[CI 运行记录](https://github.com/lirji/marketing-lowcode-platform/actions/runs/45136575100)

重复验收入口 `scripts/verify-bundle-release.sh`。证据目录 `~/.local/share/marketing-delivery/completion-20261116/` 的 `s11hi-real-console/`、`s11ghi-integrated.log`、`s11ghi-guards.log`。全量前端 271 项单测、104 项桌面/手机浏览器回归通过；12 项默认依赖指定环境的用例跳过，真实组合 Chromium 独立运行通过。

## 未完成项与恢复顺序

| 顺序 / ID | 当前事实与未完成范围 | 下一步及所需输入 |
|---|---|---|
| 1 / S06b | 已有同意与导入边界补强；真实人群来源、全量计算、snapshot/segment投影及审批发布到决策闭环未完成 | 已选择Commerce会员/画像和Auth认证；需落实业务会员映射、机器读取/稳定快照合同，不能把OIDC sub或本地预览名单当权威会员 |
| 2 / S07 | SKU版本前提、履约观察与恢复、轨迹及对账边界已补；真实终态查询、取消/追回和Journey发奖闭环未验证或未接完 | 需要同租户且有benefit.award.read的机器凭据、按来源取消墓碑及终态合同；Journey还缺可信会员、OfferToken和正式资格输入 |
| 3 / S08–S10 | 裂变本地资格/配额/奖励/授权/回滚持久化保留；可信来源适配、READY ACK、C端、Worker、专用投递取消、measurement及运行端到端仍待完成 | 先签收身份、会员、订单、SKU、风控与权益合同，再逐层接入；默认拒绝保持，不能用模拟来源宣称真实闭环 |
| 4 / S11 | DMN工作台、发布目标/回滚及Journey同源仿真已完成；活动组合整体发布、回滚及定时排期通过隔离验收；完整Journey、可恢复迁移/重算、真实实验曝光尚未完成 | S11e–i 组合发布实现与验收见当前优先交付及增量设计；业务长任务仍需补齐。实际曝光/发奖依赖前述来源，并非全部由外部环境阻塞 |
| 5 / S12 | 活动和时间窗口筛选/CSV、资产管理分页、审批发布分页、错误采集及静态预览真实性已补齐；归因策略筛选与敏感字段访问工单尚无完整接口 | 统计需正式策略维度及投影合同；敏感访问需授权工单合同。原审计的风险策略配置和权益历史扩展仍需按真实后端能力细化，不以现有详情页替代完整历史 |
| 6 / S13 | 本机Docker启动、健康检查和Flink恢复已验证；进程级编译沙箱、生产存储/归档、真实OIDC/KMS/轮换、隐私保留及容量/故障/混合版本/灾备验收未完成 | 明确生产目标、资源、保留策略与验收预算；生产部署需另行授权。安全基线和方案文档不等于生产验证通过 |

旧目标合同存在同路径不同入参的冲突；本轮已查交易中心真实代码：批量查询保留 `/internal/v1/order-referral-evidence:query`，首单补查另有 `/internal/v1/first-order-referral-evidence:query`。两者使用交易原生memberId，营销canonicalSubject适配仍Pending；首次确认支付与目标结算首单口径尚需显式映射。下一步据实际接口修订消费者合同，不把旧规划或源码存在当真实上游联调成功。

恢复入口见根 `CODEX_PROGRESS.md`。S11e–i 的实现与隔离验收见“当前交付结果”。本机业务作业在 Docker 重启后为零，旧部署回执仅用于核对历史。S06b/S08 真实上游合同及本表其余项目保留在原计划中。

## 执行清单与验收

| ID | 结果及可观察验收 | 依赖 | 状态 |
|---|---|---|---|
| S01 | 触达重试复核当前同意/抑制/未成年人/个性化/静默时段/模板；终态幂等且频控不重复扣减；渠道限速、熔断冷却与单探测恢复通过回归 | 现有触达/Provider 契约 | DONE |
| S02 | DEV 身份切换清理旧查询及缓存；真实轨迹空值/失败/加载不显示演示结果，异步结果不串查询 | 当前前端 API | DONE |
| S03 | 空库可启动控制面并完成合法首发后 Decision 才 ready；Flink 校验名称/状态/检查点及恢复选择；真实 SKU 依赖验证 | 本地资源、已有跨系统合同 | DONE |
| S04 | 修复 CI 暴露的纳秒存储、跨仓基础设施访问与失效 Trivy 引用；对应远程工作流实际成功 | GitHub 仓库访问凭据 | DONE |
| S04c | 触达同键请求指纹、服务端实际时间频控、迟到投递结果不覆盖终态；历史无指纹待重试记录明确拒绝自动重发 | 既有contactKey契约；S04分支先合并 | DONE |
| S05 | 空白画布节点专属配置、真实活动类型/设计意图保存、搜索定位/模拟输入；合法定义能校验保存 | 现有编译器支持集合 | DONE |
| S06 | 真实人群来源及快照/segment 一致、权益目录选择、审批发布到决策 | S03/S05、上游人群合同 | IN_PROGRESS |
| S07 | 普通 AwardIntent 终态/取消恢复及 Journey 接线；自动轨迹与对账闭环；真实浏览器验收 | S06、权益终态合同 | IN_PROGRESS |
| S08 | 裂变可信身份/交易/SKU/终态合同与适配器、READY ACK 接线 | 已有外部合同核对 | TODO |
| S09 | 裂变 C 端参与/邀请/绑定及持久 Worker；复用已完成资格、配额、奖励、授权与回滚机制 | S08 | TODO |
| S10 | 裂变专用投递/取消/终态/measurement，profile/网关/镜像/备份与端到端验收 | S09 | TODO |
| S11 | DMN 编辑分析求值、组合/定时发布、完整 Journey 及可恢复迁移/重算、真实实验曝光 | 已有合同，新增语义须核对 | IN_PROGRESS |
| S12 | 分析筛选导出、资产管理、完整分页、DEMO 数据落库、前端错误采集 | 相关后端能力 | IN_PROGRESS |
| S13 | 进程沙箱、生产存储/身份密钥、隐私保留、容量/故障/混合版本/灾备实证 | 明确目标/资源/真实凭据，生产部署另行授权 | TODO |

子系统项在进入实施前继续按可独立验证的结果细分；不编造身份、交易、履约终态、不放开默认拒绝保护。生产专项的环境验收未通过就保留未验证。

## 已完成 S01
- S01 提交 `8604156` 已快进合入并推送 `origin/main`，本地/远程 SHA 一致。远程 CI 另行核对，不宣称全绿。
- `fix/engagement-delivery-guards`：重试当前许可复核、实际发送时间静默校验、不重复扣频控；稳定 TemplateState 编码。Provider 单进程按租户/渠道/地址隔离，逐次限速、30 秒默认冷却、单一半开探测、代际隔离迟到结果、中断终止；路由状态有界，README 已说明多副本与容量边界。
- 最终验证 `./mvnw --batch-mode --no-transfer-progress -pl services/engagement-service -am test -Dtest=EngagementIntegrationTest,ProviderCallbackAuthenticatorTest,ProviderConnectorTest,SignedHttpProviderConnectorTest -Dsurefire.failIfNoSpecifiedTests=false`：19 项通过，其中 8 项真实 MySQL 集成用例（6 个新增策略变化场景）。证据 `s01-final.log`、`s01-evidence.json`。
- 独立审查 `/root/business_gaps` 发现亚毫秒补充精度问题，修复并加回归后复核通过。Code Hygiene Gate 通过，唯一限制为仓库无 Java formatter；`git diff --check` 通过。
- 未向真实短信渠道发送；未更新本机应用镜像；未修改共享库数据。Testcontainers 使用一次性 MySQL。没有新 worktree。
- 证据目录：`/Users/liruijun/.local/share/marketing-delivery/completion-20261116/`。

## 验证检查点
- S01 首轮 Mockito 桩设置失败已修；历史失败 `s01-regression.log` 保留，最终 19 项通过，测试进程已结束。
- S04 原基础设施仓库不可访问已用公开可复现 CI 快照修复；真实本地依赖仍为共享 dev-infra。

## 已完成 S02
- 提交 `39bbc6e` 已快进合入并推送 origin/main，本地远程一致。
- 分支 `fix/console-session-and-trace`，基线 `8604156`；DEV 切换前卸载观察者、取消查询/清缓存和页面状态；退出标记未登录，API 会话版本阻止旧读写回执/旧401进入新会话，保留“写操作可能已在服务端生效，回原范围查结果”语义。
- 轨迹绑定提交时的类型与 ID，加载/失败/空结果不渲染演示裁决；乱序响应不覆盖最新结果，编辑输入不篡改已展示结果 ID。
- 全量组件 112 项通过（maxWorkers=2），查询类型补充后受影响 7 项再次通过，共 113 个不同用例。lint/build 通过；桌面/Pixel7 共 4 项浏览器用例通过，覆盖真实客户端 fixture、登录切租户、轨迹加载/失败/空/成功。该证据不冒充真实后端全链路。
- 已实际查看桌面成功、窄屏失败/空结果/切租户截图，复用现有视觉布局；截图 `s02-screenshots/`。Code Hygiene Gate 通过（无 formatter 限制），根代理另行复查 mutation 迟到和路由退出竞态，无剩余当前片阻断项。
- 失败证据保留：早期 logout 竞态和测试选项类型错误已修；首轮全并行日期控件超时，降低并发后通过。当前无本片测试进程；应用 Docker 尚未更新。

## 已完成 S03（本地与空环境 Runner 通过）
- `fix/local-bootstrap-and-flink-recovery`提交3323e48已正常快进合入并推送origin/main，本地远程SHA一致；采用runtime-and-deploy/project-documentation。无新worktree。
- 拆开网关启动与Decision规则readiness；TimeLimiter明确普通控制4s、编译15s、各运行时控制10s，在线Decision/事件保持1s。原签名首发ACK/激活使Decision从DOWN变UP；本机仅网关按原镜像+不可变配置覆盖重建，覆盖文件gateway-s03.compose.json/application.yml在证据目录，源文件一致。S01/S02业务/前端镜像尚未更新。
- Flink提交器改为显式bootstrap profile；名称、全部子任务、近期完成checkpoint校验；已有状态必须显式三作业恢复计划和no_claim。现有三个作业未重提/取消。
- 真实SKU目录：`marketing-benefit-catalog-only-v3`复用既有权益镜像，768MiB、所有workers/outbox/consumer关闭、Flyway关闭；`mk_catalog_ro_20261116`仅有benefit_center SELECT（SHOW GRANTS实证），GET200/5项，unless-stopped。v1/v2失败试验容器停止；原deploy-benefit-center-1保持Created。私密env在证据目录0600保存，不入Git。
- 修正验收脚本的范围预览契约、权益版本绑定、隔离人群ID及有界安全重放；真实目录为空先失败。修正演示outbox遗漏PUBLISHED/分桶的问题，仅校正本机两演示租户6条已发布、从未claim的固定mock记录；没有删除消息或扩大背压阈值。
- PASS：15项脚本测试、4项Gateway HTTP/Redis故障测试；Compose/Helm/11条告警规则；隔离MySQL37份迁移和10项种子检查，临时容器已清理；Code Hygiene PASS（无formatter限制）、diff/文档链接检查通过。
- PASS：完整真实R1 `s03-live-acceptance-warm.log`，run=960d014dd178，dev-tenant/verification-org/verification-shop，SKU-CASH-10；审批、签名激活、人群/Decision、预留确认与对账、Journey/Flink/沙箱触达、Measurement/Flink全通过。未发送真实权益或短信。目录数据实读不等于外部发奖完成。
- 历史失败保留在S03_DIAGNOSTICS.md及各日志：冷超时/旧脚本前置条件/演示背压已修；一次隔离数据库并行时衡量投影超过90s，后来仅一次落库；释放资源后保持原时限完整复跑通过。不据此宣称生产性能通过。
- 证据目录：`/Users/liruijun/.local/share/marketing-delivery/completion-20261116/`；关键s03-first-activation.json、s03-seed-validation/seed-result.json、s03-hygiene-seed-final.json。完整空环境启动已由S04隔离Runner验证；未清空本地共享库。

## 已完成 S04
- S04a `94d1334` 已合入并推送。V4 将静默时段扩容至 varchar(18)，真实 MySQL 纳秒时间新建/更新与阻断共11项测试通过；本地持久库未迁移。
- 分支 `fix/reproducible-ci-infrastructure`：`3698e1e` 与 `7b49a92` 已正常快进合入并推送 main。CI 使用版本化 dev-infra 快照、临时随机凭据和诊断脱敏；本地默认仍用共享 dev-infra。R1 明确使用只读权益目录合同 fixture，未声称真实外部发奖。
- CI MinIO/mc 按已核验官方固定源码构建，基础镜像固定 digest，保留许可证；解决官方旧二进制镜像无法拉取。Trivy 固定官方 v0.36.0 提交，保持原检查强度。Gitleaks 仅忽略逐项核实的13条精确历史 fingerprint，无路径/规则宽泛排除。
- 本地18项脚本测试、actionlint、4种 Compose 模型/Helm、Python 编译、diff/Hygiene PASS（无 formatter 限制）。Gitleaks 8.24.3 扫描76个提交，零发现。
- **远程 PASS**：CI [43800797985](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43800797985) 对应3698e1e，Java/契约/架构、前端、部署及38迁移均通过；7b49a92 未改产品 Java/TS。Security [43801234881](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43801234881) 对应7b49a92，Gitleaks/Trivy/SBOM/两语言CodeQL全部通过。
- **空环境全栈 PASS**：[43801238973](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43801238973) 对应7b49a92，固定源码 MinIO 构建、空库启动和 R1（f46d52db16d9）、种子后第二轮新业务ID验收（bd3f19b51174）及 teardown 成功。真实项目服务/MySQL/Redis/Kafka/Flink，外部目录为合成只读 fixture，触达为 SANDBOX、轨迹为手动写入，不代表真实外部履约或生产验收。
- 本机冗余迁移测试已停止且临时容器确认不存在，连接中断属于主动停止结果；本机 deployment 执行期间修改脚本导致截断失败，远程不可变版已通过。历史失败和当前范围保存于 `S04_REMOTE_EVIDENCE.json`、`s04-*.log`，无未知数据库操作。

## 已完成 S04c
- 分支 `fix/contact-request-idempotency` 基于7b49a92，S04已合7b49a92，本片b67dbc7已正常快进推送main并核对远程一致。V5新增可空请求指纹，稳定排序JSON保留类型与数组顺序；同键异载荷返回既有 `IDEMPOTENCY_PAYLOAD_CONFLICT`，历史无指纹待投递返回 `CONTACT_RETRY_UNVERIFIABLE`，其他状态只读返回。
- 频控按服务端 Clock 准入时间；迟到 Provider 返回仅条件更新 PENDING/RETRY_PENDING，不能覆盖已推进回执、误写失败DLQ/事件。
- 两维独立审查发现首次并发插入竞态；修复为先争取唯一键后校验许可/扣频控，失败整体回滚。唯一键竞争失败在回滚后的新事务中读取赢家，核对身份后只读返回，不重复调用Provider。已有待投递记录仍可并发重试，依赖渠道稳定业务键去重，未宣称全局单飞。
- 最终27项（25真实MySQL集成+2回调）通过，包含频控拒绝后的记录回滚断言；契约Maven/13份JSON、Hygiene和diff检查通过（无formatter限制）。独立业务/事务复核无新增阻断。证据`s04c-final.log`、`s04c-contracts.log`、`s04c-hygiene.json`。无真实短信、无共享库迁移。

## 下一步
当前恢复入口见上方“当前状态”；以下阶段记录保留当时的验证事实，涉及“待交付/未部署”的历史描述以当前状态及部署回执为准。

## S05 实现与验证
- 分支 feat/usable-lowcode-editors，基于已推候选 b67dbc7（S04c）；S04 已合 main/origin 7b49a92，S04c 精确候选 CI43802922378 的Java/契约/架构、前端与部署全部PASS，b67dbc7已正常合入main/origin。
- 先保存真实 campaignType 并按类型路由；按编译器真实字段实现 Offer/Journey 专属配置与显式结束节点/连线；注册表搜索、成本与问题定位采用真实数据。新建未知节点不可静默按颜色推断。
- 每次编辑使已保存/已校验版本失效，阻止旧版本仿真/审核；定义异步加载不能覆盖已编辑画布，草稿按租户/组织/身份隔离。Offer 提供金额/币种试算，明确缺购物车/人群/库存事实的边界；Journey 完整仿真接 S11，不显示伪价格成功。
- 复用现有 Select/DateTimePicker/FieldHint/RecordTable、主题及 frontend/AGENTS.md；需要组件、真实API、桌面/窄屏操作与截图验收。未重做导航/主题，未引入新组件库。

### S05 验证检查点
- 实现：持久化真实 campaignType，标准/裂变设计入口双向提示；显式 Offer/Journey 专属表单、注册表端口/权重/搜索、真实问题 nodeId；可见连线与结束节点。只展示运行时支持的节点版本，Offer 不再提供不可执行条件分支。
- 修复版本竞态：保存和校验捕获不可变图与本地修订号，编辑即失效；迟到结果不能恢复旧审核资格。服务端加载时禁止编辑/保存，加载失败不回落覆盖已有定义。浏览器草稿按租户、身份、组织/店铺及定义隔离。
- 最终源代码的build/lint与33文件122项组件测试全部PASS（s05-build-delivery.log、s05-lint-delivery.log、s05-unit-delivery.log）。早期测试选择器、ReactFlow泛型和测试库类型错误已修，历史日志保留。
- 6项桌面/Pixel7合同fixture浏览器PASS（s05-e2e-third.log）；真实本机网关+现有服务、真实MySQL目录/定义：Offer新建→空白编辑→保存校验→2500分试算优惠500分、Journey等待120秒与elapsed出口→保存校验，两项PASS（s05-live-third.log）。未审核/发布/实际触达或发奖，此项后续S06/07验收。真实联调仅转发原API请求，不替换业务响应。
- 真实Offer活动0a89cd5b-a7f2-4cf5-9e8a-ac725a6a3389（截图可追溯），其他隔离验收数据保留，不清共享库。首次真实UI测试因拦截/api/**误伤源码模块导致加载失败，已缩为/api/v1/**；首次裂变选择器缺明确aria-label已修。
- 已实际查看桌面真实试算、手机配置及最终桌面Offer/手机Journey截图；修复窄屏Problems遮挡、小图Minimap与默认节点重叠，保留搜索/名称/完整字段，低频资金/范围折叠。证据s05-fixture-final/、s05-live-final/、s05-browser-recheck/。
- fe-review由frontend_gaps独立检查状态/API，两个P2已修并复核PASS；根代理完成视觉与对抗验证。S05已验证并交付61b471b；S06–S13仍在执行范围。

### S05 最终复核
- 两项独立审查P2已关闭并复核通过：实际活动类型未解析前关闭三个设计器写入口；保存成功/校验失败正确保留新版本，后续动作清除旧错误。新增组件回归覆盖两类失败恢复。
- 最终122项组件测试PASS（s05-unit-final4.log），build/lint通过；全量50项浏览器首轮46PASS，4项旧文案/旧菜单预期已按真实活动类型修正，受影响42项复验全部PASS（s05-e2e-recheck.log），其他8项首轮PASS仍有效。
- 最终截图s05-browser-recheck已人工复核。Hygiene通过（s05-hygiene-final.json，无canonical formatter限制），diff检查通过。真实服务两项验收通过；当前后端接口未变，后续页面类型保护通过实际API类型及组件/浏览器验证。Docker UI仍旧镜像，61b471b已交付，继续S06。
- S06外部业务信息待用户异步回答：会员身份/画像权威来源（commerce-platform会员域 / 外部系统 / 隔离演示验收）。已查本仓库分析与交易集成合同，均明确未确认来源，不能假定登录sub等于会员ID。此等待不阻止已授权的内部一致性及其他独立项。

## S06 当前子片 S06a（验证通过）
- 分支 fix/audience-snapshot-integrity 基于S05候选61b471b；S05精确CI43804843190全部PASS，61b471b已正常快进合入并推送main。
- 范围：移除UI生成六个虚构主体并把20条预览样本当全量快照的路径；保存规则仍可用，真实预估/快照明确待画像来源。保留原样本API供显式合同测试，不声称真实圈选。
- 现有快照命令补可选Idempotency-Key（兼容无键旧请求）；指纹含segment/version/无序成员集合与时间。复用本库mk_api_command，不新增数据库/中间件。
- 成员更新由唯一键条件upsert仲裁首次并发与乱序，回读真实成员状态；同版本不同状态409。快照初始count/checksum与后续可变membership的语义明确，不擅自承诺冻结。
- 输入校验：同步快照最多10000成员、非空白主体、watermark<=asOf<expiresAt、创建时未过期；成员版本为正；expiresAt瞬间即过期，与Decision对齐。
- 验收：真实MySQL并发首次更新/逆序/同版本冲突、快照重放与跨路径异载荷、过期边界/无效时间/容量；组件和桌面手机页面验证不发送伪造预览/快照。
- S06b真实来源、全量计算、snapshot/segment投影、审批发布决策尚未实现。待业务身份来源回答；不以S06a补强冒充S06完成。

### S06a 验证检查点
- 后端10项PASS（7个真实MySQL集成，s06a-backend-second.log）。首次并发测试复现MySQL RR no-op upsert后旧读视图返回500，已用写后专用FOR UPDATE当前读修复；独立复核business_gaps PASS。
- 前端lint/build PASS；其他32文件118组件PASS，人群文件测试mock提升错误已修，最终该文件5项PASS，共123不同用例；桌面/Pixel7两项浏览器PASS，截图已实际查看，按钮状态与字段布局无覆盖/溢出。日志s06a-unit*.log、s06a-e2e.log及s06a-browser/。
- 最终后端10项PASS（s06a-backend-final.log），补充缺member字段不能隐式撤销的回归；合同Maven/13份JSON及Hygiene PASS（无formatter限制），diff PASS。S06a准备逻辑提交；未部署人群服务，未修改共享数据，无新worktree。画像来源问题仍待用户回答。
- S05控制台已从61b471b不可变归档构建并单独更新本机marketing-r1/console（8084），image=sha256:a8bd3840b2e5505b2cae0231366a70222978d81d46a7e115cdb8aeb06f9f4ec1；health与真实API桌面/手机页面检查PASS，0业务写入/0页面异常/无溢出，截图已实际查看。s05-console-result.json记录结果与旧镜像回退；S06a人群补强尚未部署。其他服务/共享infra/Flink不变。

## S07a SKU 投递版本前提（验证通过）
- S06a提交b5967d7已正常快进合入并推送main，精确CI43805628341全部PASS。真实会员来源仍待回答，S06b不冒充完成；先推进不依赖身份选型的S07边界。
- 当前fix/award-sku-version-fence，基于b5967d7。已读本机benefit-center正式OpenAPI：AwardItemIntent支持expectedSkuVersion（>=0），创建时主库校验，旧请求缺失保持旧摘要；普通营销组装却未传该字段。
- 范围：新组装的普通营销意图固定当前目录返回的SKU版本，持久outbox重试不重新取版本；保留旧DTO无字段序列化与历史载荷，裂变专用DTO不变。绑定发生在意图组装时，不能宣称活动发布时已固定SKU版本。
- 验收：真实MySQL持久化版本、目录变化后原命令/出站字节不变、版本冲突不冒充受理；原普通/裂变兼容回归。仅调用本机测试HTTP替身，不发送真实权益。
- 已核对终态GET及item级补偿接口；按source取消墓碑仍无真实上游接口。S07终态/取消/Journey/自动轨迹/对账尚待继续，不发明取消成功。

- S07a最终25项PASS（16真实MySQL +9普通/裂变DTO兼容，s07a-first.log）；独立复核business_gaps PASS，Hygiene/diff PASS（无formatter限制）。未向真实权益中心发放或修改外部仓库。下一子片继续普通发奖履约状态查询及恢复，不将202视为到账。

## S07b 履约观察与查询恢复（验证通过、待交付）
- 当前feat/award-fulfillment-observation，基于已推7a9c7c7；S07a精确CI43806083229全PASS，7a9c7c7已正常快进合入并推送main。S06a主干交付完成，真实画像与决策投影仍待S06b。
- 已核实上游GET /openapi/v1/award-orders?sourceSystem&sourceRequestId和按orderNo查询，状态与明细含外部来源、主体、稳定clientItemId/SKU/数量/金额，可与原出站载荷核对。HTTP202仅受理。查询/履约事件均无可依赖的单调业务版本，不能按接收时间覆盖终态或自行发明版本。
- 设计边界：只保存“权益中心当前查询结果+观察时间”，与投递SENT/DEAD分离；检查来源、请求号、主体、项身份后才入库，404/超时/403不能算发放失败或取消。数据库租约代次隔离迟到查询结果；保留最近成功观察与同步错误，避免失败覆盖已知结果。
- 外部GET目前不支持POST/catalog的机器委派租户白名单；真实使用需租户匹配、带benefit.award.read权限的机器凭据。未具备时保持未验证/失败，不放宽认证或修改外部仓库。按来源取消墓碑不存在，仍为明确外部阻塞。
- 后续：先完成持久化观察、真实合同HTTP适配、受权限保护的查询刷新和并发恢复测试；再接既有有界Relay执行机制及控制台查看。当前已写观察表V13、租约Mapper、查询服务和HTTP适配，尚未验证；继续API/worker/控制台及真实MySQL测试。复用本项目持久任务+调度唤醒机制，单独1线程有界批次，与投递隔离，不新增业务编排或调度平台。


### S07b 验证检查点
- 持久观察/API/查询worker/独立读凭据/运营详情已实现；V13迁移完整中文注释，查询与投递状态分离，旧载荷和历史迁移不改。手动刷新补建历史观察，默认开关关闭，真实GET凭据未验证。
- 独立后端审查发现并修复：人工刷新未失效旧候选导致失败预算回退；关闭时503与统一handler实际502不一致。前端审查发现并修复：旧202覆盖新GET观察；长列表详情定位/关闭焦点缺失。均补直接回归。
- 初轮31后端测试1失败来自测试HTTP单线程被慢正文占用，改隔离有界HTTP测试线程后7个新集成PASS；新增热租户公平性后最终8个履约集成+原25项业务回归+5个handler+5个合同测试全部PASS（s07b-backend-recheck.log）。首次合同失败为API面清单未纳入两个已实现入口，已补精确清单；未移除断言。
- 前端125个不同组件用例PASS，新增乱序/焦点后操作面10项复验PASS（总126）；lint/build PASS。20行列表桌面/Pixel7详情与失败保留、焦点浏览器2项复验PASS，最终截图已实际查看（s07b-browser-reviewed）；证据s07b-*。Compose/Helm/render校验PASS，未实际部署本片/迁移共享库。
- Hygiene通过（无canonical formatter限制），文档明确无真实到账/取消/负载证明。S06b身份来源仍等待用户，下一步S07c核对Journey/轨迹/对账入口，外部缺口不能冒充完成。

- 后端两项独立finding已复核关闭；前端两项已修并通过回归，独立复核PASS，无剩余本片阻断finding。本片未开启真实查询/发奖，未部署新镜像，保留全部任务证据和旧镜像。
- 最终合同脚本PASS（全部合同测试及13份JSON），Compose/Helm检查PASS。准备在feat/award-fulfillment-observation按单个纵向片提交，后续以精确SHA远程CI决定主干交付；本地验证不冒充已部署。


## S07c 自动决策轨迹（验证通过，待交付）
- 分支feat/automatic-decision-traces基于afee6fc；S07b已推任务分支，精确CI43807716729前端/部署PASS、后端FAIL，先定位日志，不合主干。原S07b产品验证33业务+5异常+5合同、126不同前端、2浏览器、合同/Compose/Hygiene PASS，双独立审查通过；尚未部署。
- 当前Journey GRANT仅携带本地resourceKey/quantity/subjectToken，不含可信会员映射、OfferToken/正式资格和新发奖来源合同；保留本地资源账本，不擅自接对外AwardIntent。权益按来源取消墓碑依旧缺上游实现。此两项保留待合同，不冒充S07完成。
- 自动轨迹优先补“真实Decision计算自动记录并可查”，事件仅说明真实发生的决策，不虚构Reserve/Confirm/Exposure。复用当前幂等存储权威：JDBC在同一command行/事务保存脱敏trace及查询索引；REDIS在现有COMPLETE Lua内以同tenant hash tag原子写查询索引/脱敏trace，沿用24小时窗口。不引入MySQL+Redis双写事务、不切换线上幂等存储、不新增Kafka主题或后台进程。
- 兼容：旧command无trace仍可重放，轨迹明确未找到；按请求/订单返回当前可查的最新决策，稳定时间+quoteId排序；只有已完成请求可查，不返回OfferToken或原始主体/购物车。现有measurement手工轨迹入口保留，控制台在其明确404时查询自动决策摘要，其他错误不隐藏。
- 验收：真实MySQL/Redis并发、幂等回放、身份/过期隔离、完成原子性与索引排序；前端成功/404/失败路径真实契约验证。当前未写S07c产品源码。

- S07c初步源码已写：AutomaticDecisionTrace有界脱敏摘要、V3同command列索引、Redis COMPLETE同slot原子更新索引、GET端点；编译PASS（s07c-compile.log），尚未测试或前端接入。S07b远程后端失败优先修复，失败日志s07b-ci-failed.log；两个子片改动必须分开提交。

- S07b CI唯一失败为ReferralHeldReviewMySqlTest固定12个迁移而当前V13有13个，已按新schema更新计数，7专项PASS（s07b-ci-migration-recheck.log）；单独测试提交推原S07b分支重跑，不夹带S07c。S07c真实MySQL/隔离Redis测试运行中（s07c-backend-first.log），尚未完成。

- S07c第二轮10项后端PASS（6真实MySQL/Redis双模式+3旧持久化+1路由）；初轮2个测试fixture错误（Mockito覆盖stub时调用旧answer、spy Spring代理）已修，不是放松业务断言。新增真实HTTP权限/跨租户验收待最终运行。
- 前端显式404补查自动摘要，401/403/5xx不回退；初轮请求计数误包含已有client-error遥测，改精确限定业务请求，4项PASS。独立审查发现两次请求间会话切换间隙，已保存整次查询sessionVersion并在补查前/返回前校验，新增microtask会话切换回归待结果。
- 浏览器原轨迹/会话隔离+新自动摘要共6项PASS（s07c-browser），合同脚本PASS。S07b重跑首次在下载Maven时HTTP429，未执行测试；已仅重跑失败后端job（43808335508 attempt2），前端/部署PASS保持，当前后端执行中。

- S07c最终12后端PASS（7真实双存储/HTTP/回滚/乱序/权限/旧回放+3已有持久化+路由+决策），129全量组件、lint/build、6浏览器、合同/13JSON PASS。最终桌面/手机自动摘要截图已实际查看；候选与报价同时显示，未知terms显示未提供，标题不冒充全链路齐备。
- 独立审查会话P2已修复复核PASS；JDBC/Redis原子性无剩余本片finding。证据s07c-backend-final.log、s07c-unit-final.log、s07c-browser-final/等。S07c尚未部署或提交；S07b精确9b290a0 CI后端attempt2仍在运行，等待正常主干交付。

- S07c最终Hygiene通过（无formatter限制），客户端5项末次复验PASS，diff通过。准备当前任务逻辑提交；用户输入、生产/共享数据和外部仓库不变。

- S07b CI attempt2发现新测试共用MySQL造成待发fixture被旧Relay测试扫描（预期1次实际13次），属于测试隔离缺陷，未放宽原断言。正在改AwardFulfillmentIntegrationTest独占Testcontainers MySQL并跑完整权益服务89项；日志s07b-all-benefit-tests.log。S07c17文件已暂存待提交，S07b测试隔离修复必须用--only单独提交并推原分支，不夹带S07c。

- S07b隔离修复f45f1dc已独立提交推送feat/award-fulfillment-observation，未夹带已暂存S07c；完整权益包88项全部PASS（s07b-all-benefit-tests.log，应用启动测试不在该包筛选内）。远程精确CI43809440693已启动，待其全量结果；未合主干。
- S07c17文件验证版本已锁定，准备独立提交；无本地测试运行，运行镜像和共享数据未变。

## S11a DMN 真实分析与求值（验证通过，待CI交付）
- 先前依赖：S07b f45f1dc精确CI43809440693、S07c 6bb34f1精确CI43809493388运行，均待主干交付；本片从6bb34f1建立feat/dmn-workbench。S06b/S08–10和Journey外部发奖仍受会员/上游合同阻塞，不越过默认拒绝。
- 本子片结果：当前DMN页面可自定义输入/输出列和规则，以真实KIE引擎分析Gap/Overlap并对输入求值；空白起步、不生成业务样本。编辑后使旧结果失效，失败保留输入。本片仅计算，无发奖/发布副作用；DMN版本持久化与发布绑定留S11b，页面明示尚未保存。
- 契约：compiler新增POST /api/v1/dmn/analyze、/api/v1/dmn/evaluate，分别definition:compile/definition:simulate权限，沿用网关编译预算与ProblemDetail。请求table={hitPolicy:UNIQUE|FIRST,inputs:[{name,type}],outputs:[{name,type}],rules:[{inputs:[string],outputs:[string]}]}，type=number|string|boolean；evaluate加context:字符串值字典。响应分别{valid,gaps,overlaps,messages}、{success,results,messages}，均为真实引擎结果。
- 边界：最多4输入/4输出/32规则；字段名ASCII标识符且唯一；单元格256字符。仅受限数字比较、字符串/布尔等值及通配“-”；输出仅标量字面量，服务端生成并转义XML，不开放任意FEEL/XML。上下文字段和类型精确校验；数字精度/长度有界，字符串使用JSON引号。全局单个求值/分析准入，繁忙明确拒绝；不声称进程级沙箱/强制终止已完成（S13）。新增指标无租户高基数标签；不写DB，不新增服务/组件。
- 验收：真实KIE重叠/缺口、UNIQUE冲突/FIRST顺序、无命中null、非法语法/边界/授权、并发拒绝与资源释放；真实客户端组件和桌面/手机操作、乱序结果保护、build/lint/合同及独立审查。规模为一个跨前后端子片，下一复核点是引擎输入/输出兼容验证。

### S11a 验证检查点
- 后端真实KIE/HTTP/错误处理首轮26项PASS（s11a-backend-second.log）；首轮测试访问包私有TenantContextHolder编译失败已改用既有callInScope机制。进一步资源回收/并发与既有编译回归待复验；新接口不访问共享数据库。
- 前端136全量组件PASS，独立审查3个P2（权限分别控制、文本null与无结果区分、键盘重排行焦点）均修并有回归，最终DMN10项PASS。两浏览器首轮PASS；中间轮新带引号文本与规则格同名导致测试选择器歧义，限定真实结果表后2项PASS（s11a-browser-reviewed.log），根代理已查看手机结果，调整字段方向/操作列宽避免占满窄屏。
- S07b最新CI43809440693所有业务/集成测试通过，仅最后ArchUnit异常类Unavailable缺Exception后缀失败。根代理在原3文件改为UnavailableException；不修改断言/架构规则，当前跑s07b-architecture-fix.log后独立补交。S07c6bb34f1包含同一祖先，精确CI待最终查询；S07b/c尚未主干合并。

- S07b/S07c已交付：8a59f2f精确CI43810321895 Java/合同/架构、前端、Compose/Helm全部PASS，正常快进推送main并核对本地/远程一致8a59f2f（s07bc-delivery.json）。保留S11a未提交工作树，不包含新DMN实现；无部署。
- S11a根代理追加解析前512KiB限制并验证已知/分块正文与权限先行，最终36后端（27compiler含8旧编译+3adapter+6handler）PASS，s11a-backend-bounded.log。前端137不同用例验证有效；最终10DMN组件、lint/build PASS；两浏览器最终截图已由根代理及frontend_gaps实际查看，3个P2独立复核全关闭。合同/13JSON、Hygiene（无formatter限制）PASS。
- 真实浏览器联调准备：已打包计算服务，只绑定127.0.0.1:18882、Flyway关闭、数据库指向不可用9端口，避免访问共享数据；日志s11a-live-server.log。尚未验证启动或浏览器结果。后端子代理本轮遇额度限制停止，根代理已接手收尾；不因此声明整个计划完成。

- S11a最终本地验证39后端（新增两DMN网关HTTP路由在1.5秒下仍成功，在线Decision维持短预算）、137不同前端用例、lint/build、合同/13JSON、Compose/Helm/11告警、Hygiene全部PASS（无canonical formatter限制）。部署校验初次使用相对DEV_INFRA_ROOT导致Docker挂载失败，改绝对路径后PASS，无产品缺陷或共享基础设施变更。
- 最终真实浏览器：临时完整compiler应用127.0.0.1:18882（Flyway OFF、不可用DB端口9）与前端4177，桌面/Pixel7共6次实际KIE请求PASS，0页面错误/0视口溢出；s11a-live-result.json、s11a-live-browser-final.log与s11a-live-*.png，截图已实际查看。首次联调看到单输出重复name/typeRef引发DMN标准警告，已按引擎标准移除、加断言并重新打包复测；无命中仍真实null。
- 临时Java/Vite已发送TERM停止，仅本任务PID48653/47767，后续核验进程与端口；旧第一次服务PID47572已确认143退出。无共享库写入、外部发奖、Docker应用部署或新worktree。S11a准备单个逻辑提交，S11b版本持久化及其它S11项仍未开始实现。

## S11b DMN 版本保存（设计确定，实施中）
- 基于e1964fc建立feat/dmn-version-storage；S11a已推feat/dmn-workbench，精确CI43821070821运行，未合主干。其临时服务与Vite均确认143正常退出，未留本片运行服务。
- 结果：DMN草稿可归属当前有权限的活动，保存不可变版本，重新打开恢复最新版本；服务端保留历史版本可按版本读取。沿用control的数据所有权、租户/组织/店铺范围和审计链。保存草稿不授予审核/发布资格，不把DMN任意列自动接入在线决策事实。
- 数据：新增V5 mk_dmn_draft_version，主键(tenant,campaign,version)、FK已有campaign；table_json为有界草稿、content_hash、created_by/created_at。每活动一条DMN版本序列，借已有campaign行锁序列化保存，expectedVersion必填（首次0），不符409；不改历史版本。相同当前内容不产生空版本；同键重放使用既有ControlCommandExecutor并在重放前重新验证当前权限/活动范围。无消息或新基础设施。
- API：POST /api/v1/campaigns/{campaignId}/dmn-drafts 要求definition:write、Idempotency-Key，body={expectedVersion,table}；GET同路径/latest与/versions/{version}要求definition:read。响应{campaignId,version,contentHash,createdBy,createdAt,table}；未保存404 DMN_DRAFT_NOT_FOUND，版本冲突409 DMN_DRAFT_VERSION_CONFLICT。512KiB解析前上限、结构与字符串类型严格校验；草稿允许空字段/单元格及不完整规则语义，但维度1–4列/1–32规则、字段32字符、格256字符有界。真正语义分析仍由S11a完成。
- 前端：复用当前活动API和Select，选择活动后加载真实版本；保存后显示版本与北京时间，编辑后明确未保存。旧加载/保存回执不覆盖新草稿；409保留输入，并明确选择后加载最新。切换活动会丢失未保存修改时在页面内确认。服务端版本是权威，不用localStorage代替数据库。
- 验收：真实MySQL首次并发/同基线争用/同键回放及异载荷、跨租户/组织/店铺与权限撤销、审计同事务回滚、历史不可变；组件与真实HTTP浏览器保存恢复/冲突/失败，合同、build/lint/CI。先实现后端存储闭环，再接页面并验证，不以保存成功冒充发布完成。

### S11b 实施与验证检查点
- S11a已正常快进交付main=e1964fc，精确CI43821070821三job全PASS，s11a-delivery.json；S11b工作树保留，未部署应用。
- 后端V5/Mapper XML/活动锁/预期版本/幂等重放前权限/同事务审计和3API已写；前端活动选择、保存/恢复、冲突保留、在途编辑保护与重试键已写，当前均待最终验证。
- 首轮后端fixture误将活动创建201断言为200已修；第二轮确认平台组织/店铺拒绝沿用400（权限拒绝403），不借本片修改全局合同。审计故障触发器需要独占Testcontainers允许创建，现已隔离，不修改共享DB权限；第三轮s11b-backend-third.log运行。
- 前端首轮构建useRef初值和Badge tone类型错误已修。独立fe-review发现首次绑定丢未绑定草稿、DEMO_MODE可能真实保存两P2，均已修待回归；截图/真实浏览器未验证。无业务发布或真实权益副作用。

- 本地Docker曾在并存两隔离MySQL启动时显著迟滞，第三轮未完成，已仅TERM本任务测试PID52411/52410并停止自有6037测试实例；9863实例由Testcontainers清理，状态已核对，无共享服务停止/重启。s11b-docker-block.json记录结果。Docker随后恢复，Flink原3作业ID与运行状态保留（s11b-flink-preserved.json）。
- 改独占DMN测试库限制512MiB且单独运行，6真实HTTP/MySQL用例全部PASS（s11b-backend-isolated.log），含审计故障回滚；旧4ControlFlow用例在此前已PASS。追加4草稿边界测试及空元素处理后最终10项复验/打包运行s11b-backend-final.log/session49400。
- 前端146全量PASS，新增首次绑定失败恢复后20DMN专项PASS（共147不同用例）；lint/build/合同13JSON/Hygiene PASS，4浏览器PASS。独立fe-review两P2及恢复分支均复核关闭，根代理/独立review实际查看桌面/手机截图。当前真实数据库浏览器联调尚未进行，不以fixture浏览器替代。

- S11b最终本地验证：10新增后端（6真实HTTP/MySQL+4边界）、4旧ControlFlow、10架构用例PASS；147不同前端用例、最终20DMN、lint/build、合同13JSON、Hygiene PASS（无canonical formatter限制）。根代理按java-build-review做第二轮权限/锁/幂等/事务/SQL检查，独立frontend review三项（含恢复分支、演示写入、真实长UUID溢出）全部关闭。
- 真实浏览器两视口通过：临时control18883 + 512MiB独占MySQL13319，真实创建测试活动、保存v1、刷新恢复、并发更新v2后保存返回409并保留输入、明确确认恢复v2、v1历史不变；s11b-live-result.json及s11b-live-browser-final.log。仅其他导航API用空fixture，campaign/draft请求原样转发真实服务。根代理与独立review均已实际查看最终桌面/手机截图，0页面错误/固定视口宽度无溢出。最初真实长UUID暴露选择框超宽，已修label约束并改fixture/固定viewport断言，最终4浏览器PASS。
- 临时Java54838/Vite54700已TERM，独占marketing-dmn-live-20261116已停止（s11b-live-cleanup.json）；共享dev-infra/Flink及真实业务库未修改。本片可提交，后续精确SHA CI通过才交付main；尚未Docker部署应用。S11发布/Journey/迁移重算/曝光及S12/13仍未完成，会员来源问题仍待答。

## S11c 发布目标选择与真实回滚候选（设计确定，实施中）
- S11b已提交推送eb4bfd7（24文件），精确CI43823667689运行，尚未合主干。新分支fix/release-target-selection基于该提交，保持切片独立交付。
- 核对事实：ReleaseCenter只对列表第一条操作；rollback用stableGeneration-1猜目标，与后端retainedGenerations/曾ACTIVE/未过期/当前slot规则不一致。激活/回滚确认框收集reason但API不发送，文案却承诺不可篡改审计；不虚构留痕。本片先做有界操作正确性，组合/定时、聚合状态仍未实现。
- 结果：每条清单提供对应操作，回滚候选由control读取既有retained和当前slot返回（最多10），验证源为当前ACTIVE、目标曾ACTIVE/未过期且当前用户有访问权；最终rollback仍重新检查，迟到候选不启用其他清单操作。用户明确选择目标并勾选范围，不推算generation。发布replay前重新检查当前操作权限和source/target范围；为rollback target补当前范围检查。保持现有签名、slot CAS、幂等和Outbox。
- UI仅Kill Switch收集已被真实接口保存的原因；激活/回滚显示指令真实记录的操作人，不再声称未实现的审批依据审计。完整审计原因和自动/组合发布另有后续子片，不能以本片冒充S11完成。
- 验收：服务端候选跨槽/过期/未曾ACTIVE/权限隔离、旧幂等回执权限撤销、最终提交再次校验；组件/双视口确认选择、错误与迟到结果、合同/构建/CI。无真实业务发布。

- S11b精确CI43823667689三job全部PASS，已正常快进main=origin/main=eb4bfd7，s11b-delivery.json，保持S11c工作树。S11c后端候选/源目标授权、控制器重放前校验和前端逐行操作/候选选择已写，初步编译及前端build PASS；新4真实集成+旧回归s11c-backend-first.log/session30609，前端8组件s11c-unit-first.log/session16317及合同session45202正在核查。尚无S11c浏览器验收，独立fe-review已请求。

- S11c后端最终22项PASS（5新真实HTTP/MySQL、4原控制链、13Offer/Referral门禁），s11c-backend-final.log；合同/13JSON PASS。新增KillSwitch重放前当前权限校验，完整rollback事务仍保留slot CAS/Outbox。未触发真实发布relay。
- 独立fe-review两P2确认并修：Modal onClose不稳定导致逐字输入丢焦点；网络回滚成功但回执丢失后重连候选409使同命令重试不可达。现在稳定关闭回调+pendingRef；未知/5xx结果独立使用原mutation.variables及key重放，不依赖候选查询成功。9发布组件PASS含onlineManager断网恢复；全量/双视口浏览器仍待核查，前一全量仅App旧全局按钮断言失效已按逐行操作更新。无新主干交付或Docker应用部署。

### S11c 最终本地验证
- 22后端真实MySQL/HTTP及原门禁、10架构、156全量前端（最后9发布回归）、26合同浏览器、合同/13JSON、lint/build通过。末次日志s11c-unit-final.log、s11c-build-verified.log、s11c-architecture.log；Hygiene通过（仓库无canonical formatter限制）。状态码复用共享ReleaseState；既有错误码集中于原服务校验函数，没有改变HTTP/错误语义。
- 独立fe-review两P2均关闭：输入稳定焦点，以及真实ApiProblem(status0)未知结果原请求恢复。后端由根代理第二轮核对当前权限/目标范围/只读候选/最终CAS与幂等Outbox，无本片未关闭finding。
- 真实浏览器桌面/Pixel7通过：隔离MySQL中种子历史清单，真实control执行9→4回滚，故意丢弃已成功响应，重连真实候选409，原参数/幂等键重放200，SQL确认desired=4且仅1条激活指令。s11c-live-result.json、s11c-live-browser-second.log；截图已实际查看，无页面错误/视口溢出。历史清单为隔离fixture，Relay关闭，不代表完整编译/审批/运行时资格或生产回滚演练。
- 首次Java在临时MySQL就绪前失败，核对后启动成功；首次浏览器种子TenantId JSON结构错误导致500，修正隔离fixture后通过，没有修改产品反序列化或放宽测试。旧日志保留。
- 本片临时Java60637/Vite59860及自有测试MySQL已停止，端口关闭已确认（s11c-live-cleanup.json），准备提交；尚未远程CI/主干交付/本机应用部署。S11其他子项及S12/13继续，外部会员/履约合同仍待确认。

## S11d Journey 同源仿真（设计确定，实施中）
- 基于S11c 1b66e34建立feat/journey-simulation；S11c精确CI43825793504运行，尚未合主干。S11跨slot原子发布/聚合状态需要业务原子边界，外部发奖依赖会员/资格合同；先执行独立纯仿真，不新增调度器或基础设施。
- 复用JourneyCompiler/JourneyRuntime，新增定义版本专属POST :simulate-journey，当前definition:simulate和组织/店铺范围，校验真实已保存图。输入initialVariables及按时间有序events（offsetSeconds/eventType/attributes），horizonSeconds限定模拟窗口；START后按事件和计时器推进，同一时刻timer先于event、同刻event按输入顺序，结果记录真实信号边界状态/指令摘要/未触发计时器。无落库/Outbox/真实发送/发奖，不能冒充整个Journey生产能力完成。
- 资源有界：正文64KiB、最多32输入事件、每字典32项、key64/value256、窗口7天、最多64次信号推进、每信号最多1000步、迭代最多100（超过拒绝，不改写定义运行预算）；达到64次停止并明确TRUNCATED。初始条件和事件属性仅为本次试算输入，不作为业务数据库Mock。
- 页面复用原设计器，需已保存校验版本及权限；独立弹层编辑键值变量和事件序列，时间为相对开始秒数，结果按字段分列，不显示价格。修改输入使旧结果失效；版本/身份切换清理弹层和迟到回执，错误保留输入，DEMO禁止真实调用。
- 验收：条件双分支、等待事件/计时器、重复/步数限制、动作仅摘要、同引擎结果、无副作用和权限范围、输入边界；组件迟到/失败恢复、真实HTTP双视口与构建/合同/CI。旧通用Journey价格假结果改明确拒绝并指向专属入口，Offer/Referral响应保持。

### S11d 首轮实现检查点
- S11c精确CI43825793504全部PASS，1b66e34已正常快进推送main并核对本地远程一致；s11c-delivery.json，S11d工作树保留。
- S11d后端同源编译/状态机、严格64KiB输入、版本范围鉴权及专属接口已写；前端变量/事件表单与结果已写。首轮20后端（6纯仿真+3真实HTTP/MySQL+3原runtime+4Offer+4原control）PASS，s11d-backend-first.log；前端正在全量验证。合同/浏览器尚待最终结果。
- 两次组件测试fixture问题已修：输入标签含帮助文字需要显式可访问名称，异步mutation需等待实际启动后再交回回执。零等待环路测试改为交替两个等待节点，以真实触发多次计时器；同一计时器键循环在既有runtime可能消耗单信号预算，仿真忠实返回FAILED，不因此修改生产语义。
- 进一步明确：总配置节点步数预算10000；虚拟时间同刻优先timer不代表生产调度顺序，生产处理时间TTL/外部资格未模拟。既有GraphValidator禁止循环的规则保留，不冒充完整循环发布支持。
- 独立frontend代理尝试失败，环境报模型不受支持，未得到本片独立结论；根代理承担第二轮对抗复核，不能引用S11c审查作为S11d证据。无S11d业务副作用或应用部署。

### S11d 最终本地验证（2026-11-17）
- 21项相关后端验证有效：7纯仿真、3真实HTTP/MySQL、3原Journey runtime、4Offer、4ControlFlow；最后非法/溢出计时器回归及exec JAR打包通过（s11d-package.log），授权/严格解析在s11d-backend-enums.log。10架构、163全量前端、lint/build、合同13JSON通过，合同浏览器2新+6原流程通过。最后前端markDirty同步关闭仿真弹层，避免后续校验误重开。
- 真实浏览器桌面/Pixel7均通过：真实API创建活动与保存校验，PAID事件10秒→计时器15秒→仅拟生成SEND；无事件返回WAITING。SQL逐次核对仿真前后定义版本/状态与ControlCommand/Outbox数量不变，无页面错误和外部溢出；s11d-live-result.json、s11d-live-browser-second.log，结果截图已实际查看。
- 首次真实浏览器末尾fixture断言把“相同图保存”错当新版本；实际服务按hash复用v1且校验后VALIDATED，改为对比前后实际版本/状态后通过。保留失败日志；未修改产品版本/校验语义。测试仅隔离MySQL，Relay关闭，共享infra/Flink无改动。
- 根代理第二轮复核权限先行、64KiB流式上限、严格JSON、虚拟时间边界和总步数限制、输入/版本失效以及无副作用；未得到独立代理审查结果（模型不可用）。Hygiene允许既有journey-runtime-spi内部依赖，仓库无canonical formatter为明确限制。
- 尚未S11d提交/远程CI/main交付；S11其它子项不能据此标DONE。下一片先处理S12已有测量接口可支撑的窗口一致性、刷新与报告导出，活动/归因过滤必须先补真实后端协议，不启用虚假筛选。

## S12a 分析窗口与报告导出（设计确定，实施中）
- 分支feat/analytics-report-export，基线S11d 39f62a9；S11d精确CI44028678330已启动，当前未合main。S11d自有Java/Vite/MySQL已停止，共享环境保留。
- 使用已有dashboard/series协议：每次选今天/7天/30天或刷新时固定同一个from/to，两个响应作为同一页面查询整体采用；切换身份/范围后不显示旧回执，失败、加载或刷新中不允许导出。两请求同窗口不代表数据库原子快照，明确保留水位和获取时间。
- 导出UTF-8 CSV当前窗口汇总、事实类型计数及完整返回序列；包含范围/时区/粒度/水位/单位，引用已展示的成功响应，不临时再查出另一份数据。CSV控制公式注入并正确转义引号/换行；金额保留分值与精确两位展示，不伪造百分比或币种换算。
- 移除分析页硬编码业务样本与无效活动/策略下拉；静态DEMO展示连接数据源说明，业务示例沿用scripts/seed-data/system-mock.sql的测量事实/投影种子。活动筛选与归因策略要后端正式实现后另片接入，不在前端过滤部分数据冒充全局筛选。
- 现有重算API是当前租户全部归因，不受当前日期窗口约束；修正按钮/提示范围，保留原权限与幂等协议。验收涵盖稳定窗口/身份切换/旧请求迟到、失败恢复/导出内容与防注入/DEMO无假值、桌面窄屏浏览器及真实接口只读验收。

### S11d CI修正
- 首次精确CI44028678330的前端失败：原S05浏览器两视口仍断言“当前不提供完整旅程仿真”，末次产品提示更新使这项旧断言失效。已改为验证专属仿真按钮可用及真实副作用边界，未删除验收。最终8项相关浏览器PASS（s11d-browser-final.log）。
- 单独测试提交d2c271a，正常推送feat/journey-simulation并重新启动精确CI44029947101；当前未合main。S12a工作树未混入该提交。

### S12a 本地验证通过
- 固定查询窗口、页面与CSV共享实际成功响应；当前身份/权限隔离、旧请求迟到、查询失败恢复、重算真实作用范围已实现。字符串CSV防注入，原始分值/完整序列/租户/水位等元数据保留；超安全整数拒绝展示和导出。
- 173项前端测试有效（末次10分析专项PASS）、lint/build通过；s12a-unit-all.log、s12a-unit-verified.log、s12a-lint-verified.log、s12a-build-verified.log。首轮lint因控制字符正则被规则拒绝、首轮build因Testing Library无exact选项失败，均已修复并重验，没有放宽门禁。
- 4项桌面/Pixel7浏览器PASS：2合同故障恢复/下载、2本机18080真实测量GET/下载，s12a-browser-final.log、s12a-browser-final/。CSV原始金额及全部序列条数与真实回执一致；无页面错误/溢出，最终桌面合同与手机真实截图已实际查看。
- 本机真实当前窗口为0，投影水位在2026-09-16，页面因此显示“当前水位尚未覆盖所选窗口”；不把零值当业务没有发生，也未写共享库或重算。静态DEMO已无业务硬编码；新测量种子运行/全部DEMO页面治理仍属S12后续。
- 根代理第二轮核对异步范围、无额外导出查询、CSV转义与整数精度；独立代理仍不可用。准备独立S12a提交/精确CI，尚未Docker应用发布。下一步S12b接真实campaign_id投影筛选（现有列/SQL可支撑），归因策略选择不能伪造为投影过滤。

## S12b 真实活动投影筛选（设计确定，实施中）
- 基线dc72a96，分支feat/analytics-campaign-filter；S12a精确CI44030493838运行中，S11d修正精确CI44029947101运行中，两者均尚未交付main。
- dashboard与series增加可选campaignId，按已存投影campaign_id精确匹配、租户和时间条件共同限制；不筛选属性JSON、不跨库查活动，不改变冲正/水位/归因策略。未传保持原全租户语义，传空白/超128/控制字符拒绝，错误码使用现有DomainException机制。响应回显campaignId；旧服务不确认活动范围时前端拒绝显示/导出，避免静默忽略筛选。
- 前端活动下拉读取当前有权访问的api.campaigns（需campaign:read），查询键和CSV包含所选活动ID；切换范围使旧数据失效，失败保留选项可重试。measurement:read仍是当前租户统计权限；活动筛选只收窄统计，不授予新数据权限。归因策略筛选仍未实现，不伪造为投影维度。
- SQL只在现有Mapper XML，以参数绑定增加条件；新增(tenant_id,campaign_id,occurred_at)索引迁移，保留原时间索引服务全租户查询。验证实际MySQL跨活动/跨租户/冲正/边界/旧全量行为和EXPLAIN、合同/组件范围切换及旧服务拒绝、双视口真实新后端过滤/下载。

### 已交付与S12b验证检查点
- S11d d2c271a精确CI44029947101全部PASS，已正常快进交付main（s11d-delivery.json）。S12a dc72a96精确CI44030493838全部PASS，已正常快进交付main（s12a-delivery.json），S12b未提交改动保留；尚未更新Docker应用。
- S12b实际后端5测试PASS（3新活动/租户/冲正/边界/输入/权限/索引+2原测量集成），s12b-backend-first.log；Mapper参数绑定无业务拼SQL，V5索引在真实MySQL完成迁移并出现在EXPLAIN possible_keys，不能据此宣称容量压测通过。10架构/合同13JSON通过，s12b-architecture.log、s12b-contracts.log；最终全前端验证运行中。
- 8浏览器PASS（s12b-browser.log），含两个视口的报告失败恢复、旧服务筛选确认拒绝、真实原测量GET/下载，以及新测量服务18884+真实控制面活动目录。仅自有临时数据库写两条事实；全量300元，活动甲100元/乙200元，CSV活动ID及金额一致。结果截图已实际查看手机和桌面真实筛选；无页面错误/视口溢出。
- 不改共享数据库、不触发归因重算；本片临时Java18884及自有测试MySQL已停止（s12b-live-cleanup.json，待端口核验）。归因策略筛选仍待正式统计口径；S12其它项继续。
- 下一步完成S12b最终检查/提交/精确CI；随后仅按已验证不可变版本更新本机Docker必要业务服务，保留共享dev-infra与原3个Flink作业，不重复submit。现有容器来源4e847da、console61b471b，与最新源码差异需要先核对。

- S12b全量前端首轮174/176通过，两个既有路由/设计器用例在高并发下超时；专项13测试与8浏览器仍通过。停止本片临时Java/DB释放资源后，以maxWorkers=2重跑全量（s12b-unit-bounded.log），不增加超时、不削弱断言，结果待核对。18884端口已关闭。

### S12b 最终本地验收
- 降低测试并发后176/176全量前端PASS，未改测试超时/断言；lint/build、Hygiene通过（无canonical formatter为限制）。证据s12b-unit-bounded.log、s12b-lint-final.log、s12b-build-final.log、s12b-hygiene.json。前述5真实后端/10架构/13JSON合同/8双视口浏览器证据均仍适用。
- 根代理第二轮确认：筛选参数与租户/时间共同进入全部聚合SQL、未知ID不会退回全量、前端必须收到两个一致的筛选回显、CSV包含实际活动ID。共享服务/数据未修改。当前准备提交与精确CI，Docker必要服务更新仍未执行。

## S12c 资产入口与消息模板版本管理（本地验收通过，交付中）
- 基线main `881c337`，独立分支feat/asset-template-management；本地运行版本045dfd9。使用frontend-implementation/backend-implementation，复用现有资产分类、Modal/Select/RecordTable及模板GET/POST协议。
- 范围：资产人群/权益行链接到选中ID设计器；人群不存在时明确提示，不误载首条。消息模板展示完整内容/必需变量/版本/状态/创建时间，新建模板或基于当前内容保存新版本；不修改旧版本或引用方，不发送消息。保存沿用现有APPROVED语义并显式提示，不伪造新的审批流程。
- 契约补齐：模板ID≤128、渠道≤32、正安全整数版本、正文≤8000字符、最多32个变量且每个≤60字符；沿用项目ProblemDetail，重复版本409、输入400及当前权限在幂等回放前核对。渠道保持既有字符串协议，界面说明渠道必须与运营通道配置一致。
- 验收：真实MySQL验证版本不可覆盖、同键回放/不同载荷冲突、同版本竞争、读写租户隔离和权限撤销、输入边界；前端成功/失败/409保留输入、相同请求重试同键、修改载荷换键、未保存关闭保护、权限/DEMO、桌面/手机真实接口及视觉检查。
- 资产全部分页、归因策略、剩余DEMO治理与前端错误采集不计入本片完成；外部身份/履约合同待确认。

### S12c 验证检查点
- 28项触达真实MySQL集成测试PASS（含3项新增版本并发、权限/租户隔离、输入边界）；全量186项前端组件测试PASS，lint/build通过。证据s12c-backend-first.log、s12c-frontend-all.log、s12c-lint-first.log、s12c-build-first.log。
- 浏览器实证准备使用本任务独占MySQL容器的独立template_live库，临时触达API18888；Kafka消费者/Outbox关闭、Sandbox模式，不写共享业务库或发送消息。首次启动因数据库授权步骤尚未成功而失败，已核对并补齐此测试库授权后重启；不会修改业务连接权限。
- 当前待办：浏览器桌面/手机实际保存与DB双版本核验、契约/架构检查、根代理第二轮审查、文档与精确CI交付。尚未提交/更新应用镜像。

### S12c 最终本地验收
- 真实MySQL28项、全量前端186项通过；最终变更后模板后端3项和受影响前端19项再次通过。lint/build、10项架构检查、13项JSON合同及实际OpenAPI YAML解析通过。日志s12c-backend-final.log、s12c-frontend-final.log、s12c-lint-verified.log、s12c-build-verified.log、s12c-architecture.log、s12c-contracts.log、s12c-openapi.log。
- 桌面/手机4项浏览器最终PASS（s12c-browser-verified.log及同名目录）：冲突保留输入/未保存关闭保护，真实隔离MySQL创建v1、基于内容保存v2、读取详情。已查看桌面详情与手机编辑区截图，修正弹窗留白后通过。s12c-live-db.json确认8个测试模板均保留v1/v2，零contact/outbox；测试数据仅在自有template_live。
- 根代理第二轮复核权限撤销后的回放、租户隔离、版本并发唯一性、存储边界、晚到响应与资产深链；未声称独立代理审查。Hygiene无阻断项，仅无canonical formatter限制及已命名数字常量的提示，见s12c-hygiene-final.json。
- 自有临时18888服务和MySQL容器已停止，数据保留，见s12c-live-cleanup.json。共享dev-infra、11应用和Flink未改变。当前提交/精确CI待执行，Docker仍为045dfd9。
- 下一步：本片提交与精确CI通过后正常快进main，更新本机engagement-service与console并验证；随后处理列表完整分页，优先修正审批范围过滤发生在LIMIT之后的问题。

## S12d 审批范围与完整分页（代码及本地Docker已交付）
- 分支fix/approval-scope-pagination，基于S12c候选c14289e；S12c精确CI44041099756前端/部署PASS，后端运行中，尚未main/部署。两个切片分别提交。
- 已证实：旧审批先取租户最近100条后Java过滤组织/门店，造成可见数据遗漏；审核页又在客户端筛OPEN，发布向导只看同一截断列表。侧边栏和“今日治理态势”也误将截断列表当总量/今日。
- 范围：保留旧数组API但权限范围先于LIMIT；新增有界审批分页API，SQL先按组织/门店/状态筛选，按不可变created_at和case_id降序、limit+1探测后页，cursor需属于当前可见租户范围。返回items/nextCursor/hasMore及状态回显，不承诺数据库快照，刷新重新开始。
- 前端：审核页支持状态选择、加载更多/失败重试/刷新，标注已加载数；发布向导加载全部可见APPROVED页；导航待审数采用有界结果与“+”标记。查询键绑定租户/身份/权限/组织/门店，权限切换清除旧内容，不将局部页统计伪装成总量。
- 验收：真实MySQL超过100不可见/其它状态记录不遮挡可见目标；同时间多页无漏重、状态更新/新增后继续页、非法与跨租户/范围cursor、限额/权限、旧API兼容；前端分页失败保留已读、范围切换、状态切换/重试、发布较早批准单；桌面/手机真实接口及视觉检查。此片不含release列表及其它资产完整分页。

### S12d 首轮验证检查点
- 实现了审批范围SQL过滤、有界稳定游标及兼容旧数组接口；增加V6两个索引，前端审核/发布选择/导航使用分页，状态与权限范围进入缓存键，数量明确为已加载。
- 7项真实MySQL后端PASS（3项新分页+4项原控制工作流），s12d-backend-final.log；首轮测试捕获OGNL单字符通配判断为字符而非字符串的问题，已在本片审批范围表达式修正并覆盖通配读取。9项前端专项PASS，s12d-frontend-first.log。
- 首次前端编译缺少两处StateBanner的必需detail已修，等待全量lint/build/测试；不将首轮日志当最终证据。下一步实际OpenAPI解析、双视口真实隔离接口与视觉/根代理复核。
- S12c精确CI44041099756三项PASS，c14289e已正常FF远程main；两服务已更新并健康，runtime-c14289e/runtime-smoke.json通过原容器/Flink保留与实际API。浏览器出现连接拒绝，curl及独立浏览器连接后续通过，正在复验；尚未把Docker交付标为最终PASS。

### S12c 容器路由补修
- 实际Docker浏览器发现`/assets`返回301 Location到内部8080，`/assets/`403；根因Nginx `try_files $uri $uri/ /index.html`将同名静态目录当页面。属于本片真实入口阻断，开发服务器验证无法覆盖。
- 单独分支fix/console-assets-route，仅修改Nginx目录回退；S12d未提交改动保留。验证实际隔离Nginx路径、静态JS与404语义后提交精确CI，再仅更新console并复验原S12c真实浏览器。

### S12d 最终验证推进
- 全量193前端、最终9受影响组件、lint/build PASS；双视口8浏览器PASS（s12d-browser.log/目录），包括真实隔离MySQL的55条OPEN全量与发布选择105条APPROVED，120条不可见记录被排除；已查看手机列表和桌面较早批准单选择截图。
- 浏览器仅GET；s12d-live-db-after.json确认审批280条、零决定/命令/Outbox变化。临时API18889和自有MySQL已停止，数据保留，s12d-live-cleanup.json。
- 根代理复核发现SQL默认不区分大小写会放宽原TenantScope精确标识语义，已补组织/门店二进制比较与大小写回归；s12d-backend-verified.log重跑中。合同固定路由集合已同步新增分页API，并补有界响应检查；最终架构/合同日志待核对。
- S12c路由补修8bb1270精确CI44044235242全部PASS且已FF main。镜像准备首次裸SHA引用错误已改为已验证本地标签；更新console后Docker inspect出现15秒超时，自动恢复流程仍在核对，当前不能宣称最终部署PASS。运行状态runtime-8bb1270/rollout-state.json；不重启Docker或共享服务。

### S12d 本地验收结论
- 最终权限精确匹配回归3项真实MySQL PASS并已重新package（s12d-backend-verified.log）；前述4控制工作流PASS仍适用。最终193全量前端、9专项、lint/build、8双视口浏览器证据见本片日志，未改动已验证的交互行为。
- 最终10架构检查、19合同测试及13JSON检查PASS（s12d-architecture-verified.log、s12d-contracts-verified.log）；实际OpenAPI YAML解析PASS。首次架构命令误包含无关依赖集成测试，已停止自有进程并按ArchitectureRulesTest限定重跑，不把中断运行计为PASS。
- 根代理第二轮审查通过：范围/状态先于LIMIT，通配与大小写权限语义一致，游标重新校验当前可见范围，稳定创建时间顺序，状态变化后不依赖更新时间定位；前端完整范围缓存键、失败后保留已读与重试、历史批准单可选择，无伪造总量。Hygiene无阻断（无canonical formatter限制），s12d-hygiene-final.json。
- 本片准备逻辑提交及精确CI；当前共享库尚未应用V6。本地Docker的S12c控制台补修仍在恢复，先恢复控制台再安排本片control/console更新。
- 相邻待办：旧campaign列表XML仍有单字符通配OGNL判断，后续范围治理需补覆盖；release列表50条截断与范围后过滤仍待下一片，不冒充已修。

### S12c/S12d 交付与Docker恢复结论
- c14289e、8bb1270、e9f3972均已正常FF main；回执s12c-delivery.json、s12c-route-delivery.json、s12d-delivery.json。S12d最终本地验证及精确CI已PASS；未声称独立代理审查或完成全部S12。
- 首次8bb镜像打包将Nginx配置继承为0600，user101无法读取，console进入重启失败；随后Docker管理API持续超时，回滚/停止结果当时未知。恢复后确认该失败容器已停止，未把旧manifest重复部署。
- 按最新仅运行本项目的授权，保存3个真实Flink保存点及备份后恢复Docker管理，停止13个无关运行容器。重新核对实际容器、镜像、环境、端口、内存和卷，再以已验证e9产物构建control/console；配置显式0644，实际镜像隔离预检PASS。
- 本机18容器检查、11应用健康、3作业恢复路径与检查点推进、V6迁移、审批参数拒绝/状态分页/模板读取PASS；实际8084桌面与Pixel7模板详情、未保存保护、审核状态筛选、发布批准单读取PASS，无业务写请求、页面错误或横向页面溢出，最终截图已查看。
- 证据根下runtime-recovery-e9f3972/保留artifact-manifest、rollout-state、runtime-smoke、browser-smoke、project-only-containers、flink-restore-state、container-resources；控制库备份、保存点备份和私密env仅存本机0600。无新增worktree，不清理旧证据/停止容器/用户文件。

## S12e 发布清单范围与完整分页（DONE）
- 基线main/82dbab4，分支fix/release-scope-pagination。Docker恢复及S12d交付已完成，后续不重复原Flink恢复/发布。
- 已证实旧GET /releases先取租户50条再逐条检查第一制品定义的组织/门店，导致被不可见记录挤出的可见清单无法读取；发布页共用不含身份范围的缓存键，也没有完整翻页。
- 本片保留数组API但范围先于LIMIT；新增GET /releases/page，limit默认25、上限100、可选state和manifestId游标；按不可变created_at/manifest_id降序，当前权限验证游标，状态切换服务端筛选，返回items/nextCursor/hasMore/state。延续单定义发布范围规则，不引入组合发布语义。
- 前端使用完整身份/权限/组织/门店查询键，支持更多、重试和刷新；标注已加载数。保留既有激活/回滚/熔断的逐行目标、权限与未知结果重试保护，不执行真实业务写入验证。
- 验收计划：真实MySQL超过50不可见清单不遮挡可见数据、多页同时间无漏重、状态变化游标、租户/大小写/组织门店隔离、非法参数；前端分页/失败保留/权限切换与原发布操作回归，真实隔离服务桌面手机浏览器、合同/架构检查。

### S12e 验证检查点
- 已实现旧数组API范围先于LIMIT、新有界分页与V7索引、完整权限查询键、服务端状态筛选和加载更多/重试/刷新；保留逐行激活/回滚/熔断与幂等保护。首项摘要改为“当前筛选首项”和“清单流量配置”，不把配置称为实时流量或额外宣称校验完成。
- 真实MySQL 11项PASS（发布分页3、既有发布目标5、审批分页3），覆盖110条同时间跨页、超过50不可见清单、状态游标变化、租户/大小写/组织门店/通配授权、索引候选；s12e-backend-second.log。首轮测试模板替换误改HTTP方法名，已修复并重新验证。
- 前端40文件198项全量PASS，最终client7项亦PASS，s12e-frontend-final.log、s12e-client-final.log；lint与构建通过。合同20项、10架构及13JSON、实际SnakeYAML OpenAPI解析PASS；s12e-contracts-final.log、s12e-gates.log、s12e-openapi.log。
- 浏览器8项通过：分页故障/重试2视口、既有回滚未知结果2视口、既有激活确认2视口、真实独占MySQL第55条记录/筛选/较早操作目标2视口；s12e-browser.log保留首轮4成功2环境失败，s12e-browser-live.log保留激活2成功/隔离种子DTO错误2失败，最终真实两项见s12e-browser-verified.log。已查看手机列表与桌面较早目标截图。
- 隔离服务准备首次DB未完全就绪，后续核对实际状态后重试；自有种子TenantId最初误写字符串，按真实Java记录改为对象并只修复230条自有测试记录。最终浏览器仅GET，没有共享库测试灌数或真实发布/回滚。最终读库230条清单、零命令与Outbox；临时API18889及自有MySQL已停止，数据保留，见s12e-live-db-after.json、s12e-live-cleanup.json。
- 根代理第二轮复核范围与游标SQL、稳定状态、完整缓存键、角色切换和旧操作目标；未声称独立代理验证。Hygiene无阻断，仅无canonical formatter限制与命名常量数值提示，s12e-hygiene.json。

### S12e 交付与运行验证
- 3f220195a86227e65ddf03e0730ad75f6a06c9bc精确CI44056163714三项PASS，正常FF main；s12e-ci-exact.json、s12e-delivery.json。
- control/console已更新至3f22019，更新前V6备份保留，V7两项索引验证成功；实际镜像预检、18必要容器、其它16容器身份/启动时间不变、3个既有Flink作业保存点与检查点推进、状态/游标/非法参数读取PASS。
- 实际8084桌面/Pixel7模板、审批、发布状态筛选、向导批准单读取PASS，无业务写请求/API失败/页面错误/横向溢出；已查看手机发布筛选及桌面向导截图。证据runtime-3f22019/{artifact-manifest,rollout-state,runtime-smoke,browser-smoke}.json。
- 复核仍有旧数组消费者：ReleaseWizard从最新50条找暂存目标，CampaignReadiness据此判断已发布，AppShell/Dashboard把局部数量作为总量；完整身份缓存也需补齐。登记S12f，不把发布中心分页等同于所有消费者整改完成。

## S12f 发布读取调用点（DONE）
- 计划：增加按清单ID读取的只读授权接口，向导只轮询自身暂存清单；壳层/首页复用完整身份键分页并明确已加载数量；活动就绪可继续分页、未遍历完不宣称未发布。保持发布/回滚写语义，不自动加载无界全集。
- 验收：真实MySQL验证较早清单、租户/组织门店/权限拒绝；前端ACK目标、失败禁用、身份切换、页尾就绪与局部计数；合同/构建/远程CI以及真实本地Docker双视口回归。

### S12f 实现与验证检查点
- 已实现按ID读取当前ReleaseView，复用租户/冻结定义范围与release:read；向导只轮询本次暂存ID，读取失败/刷新中不能激活，客户端拒绝ID不匹配。首页/壳层复用完整范围分页并标注局部数量；活动就绪支持更多/失败重试与未知状态。
- 真实MySQL4发布读取/分页 +5发布目标、合同规格7、架构10测试PASS，见s12f-backend-final.log；全前端首轮204通过/1失败（活动缺省定义返回undefined被React Query视为失败），已将当前组件的空定义明确转null，受影响5项重新PASS。新接口范围错误沿用现有TenantScope的400 INVALID_ARGUMENT，权限缺失403，合同与测试已按真实语义对齐。
- 初始单测另发现JSX闭合遗漏和重复消费同一个Response的测试fixture，已修复。上述失败日志保留s12f-initial-unit.log、s12f-consumers-unit.log、s12f-frontend.log、s12f-backend.log，不把首轮失败写为通过。
- lint/build已通过；状态常量复用调整后待最终验证。双视口向导ID/ACK失败保护浏览器测试运行中，日志s12f-browser.log。尚未提交/推送/部署S12f。

### S12f 本地验收结果
- 当前源码全前端41文件205项PASS；最后状态常量修复后受影响3项再次PASS，lint/build最终通过。见s12f-frontend-final.log、s12f-overview-final.log、s12f-lint-final.log、s12f-build-final.log。
- 真实MySQL9项、OpenAPI合同规格7项、架构10项PASS，控制面exec JAR打包通过。新接口读取较早清单和新ACK，无权限403、组织/门店越界400 INVALID_ARGUMENT、跨租户/不存在404；复用原TenantScope拒绝语义。
- 新向导ID读取/错误禁用激活2视口、既有激活确认2视口、回滚未知结果2视口，共6浏览器用例PASS，s12f-browser-verified.log；测试HTTP样例隔离，无真实编译/暂存/ACK/激活写入。已查看桌面ACK就绪与手机失败截图。最终fixture正确回应侧栏状态筛选/批准单读取，未依赖8080外部服务。
- 根代理复核ID/租户/范围授权、失败不授予激活、完整身份缓存、未遍历完待核实、旧操作回归及差异；不声称独立代理审查。Hygiene最终无finding，唯一限制是仓库无canonical formatter；s12f-hygiene-final.json。源码指纹与产物哈希分别s12f-validated-fingerprint.json、s12f-built-artifacts.json。
- S12f本地验收PASS，待精确远程CI、正常FF main及本机control/console增量部署后完成运行验收。整体S06–S13仍未全部完成。


## S12g 活动列表权限范围（DONE）
- 从S12f提交2e2263f分出fix/campaign-list-scope，交付顺序S12f再S12g；不是将S12f当成已部署。S12f远程CI44060287931运行中，预构建镜像/备份已就绪，实际运行仍3f22019。
- 证据：ControlMapper.selectCampaigns使用单字符OGNL `contains('*')`，且组织/门店比较使用数据库默认不区分大小写排序规则，与TenantScope精确匹配不一致。
- 计划/验收：只修当前活动列表通配与组织/门店精确范围，保留租户、类型过滤及空组织拒绝；先真实MySQL回归复现，再修复并验证全域/部分通配、具体范围、大小写、空范围、其它租户和权限拒绝。无表迁移或业务写语义改变。

### S12g 本地验证
- 修复前真实MySQL两个用例均失败：全域通配预期7条实际0条；org-a/shop-a预期3条实际5条（混入ORG-A与SHOP-A）。证据s12g-before.log。
- 修改活动SQL两处通配为字符串OGNL，并将组织/门店比较显式转BINARY，严格沿用TenantScope大小写语义；不改变类型、租户和组织级无店铺记录规则，不新增索引/表或写入行为。
- 修复后13项真实MySQL PASS：当前范围2、既有控制流程4、审批3、发布读取/分页4，s12g-after.log。根代理复核XML参数绑定与空组织服务层提前返回；Hygiene对此XML变更为NOT_APPLICABLE，git diff --check通过。前端沿用已通过的S12f证据。
- 待单独提交、精确CI和顺序交付；S12f必须先正常FF main，S12g随后只更新control服务。无本机业务数据修改。

### S12f 远程与运行交付
- 2e2263f3d750cb37f4c4df29fb24bc03c43e6fa5精确CI44060287931三项PASS，正常FF main；s12f-ci-exact.json与s12f-delivery.json。control/console已更新至此版本，18必要容器健康，其它16容器不变，Flink原恢复作业检查点持续推进。
- 实际单清单读取、权限拒绝/跨租户404、完整列表/状态筛选、模板/审批与桌面/手机首页、发布中心、向导读取PASS，无业务写请求；runtime-2e2263f/{rollout-state,runtime-smoke,browser-smoke}.json。已查看真实手机首页截图。S12g只读范围修复b3858e6已提交，精确CI44061394760进行中，单独control镜像与配置/备份已就绪。

## S12h 首页数据表述（DONE）
- 真实Docker截图runtime-2e2263f/mobile-release-overview.png暴露：固定标题“增长脉搏稳定”，把全部已读活动数当在线活动；数据水位停留2026-09-16而当前为2026-11-17，延迟仅显示数百万秒。零收入点也被最小柱高抬成正值。
- 从b3858e6建立fix/dashboard-data-status；先交付S12g，再交付本片。修正首页为中性标题、已加载活动计数、缺失延迟保留未知、按单位展示实际延迟和零值图形；不定义未经确认的实时SLA、不变更测量API或生产数据。
- 验收：真实API数据/延迟缺失/零值单位测试，保留DEMO隔离、lint/build与双视口页面；精确CI及仅console增量部署。

### S12g 远程与运行交付
- b3858e6e01b54a4f0812b92c4d12906ddca92822精确CI44061394760三项PASS，正常FF main；s12g-ci-exact.json与s12g-delivery.json。仅control更新，console保持2e2263f，其它17容器身份/镜像/启动时间不变。
- 实际全域活动查询与控制库只读集合核对一致，REFERRAL类型筛选、发布单清单权限拒绝、18必要容器健康及3个Flink原作业检查点推进PASS；runtime-b3858e6/。前端没有变更，复用runtime-2e2263f双视口证据，不把未重跑的浏览器描述为新增验收。

### S12h 本地验收
- 210项全前端测试PASS（42文件），含新增旧水位、缺失/非法延迟、活动请求失败、分金额、零柱和负值明细场景；lint/build通过。s12h-frontend.log、s12h-lint.log、s12h-build.log。
- 桌面与Pixel7两个浏览器合同样例PASS，实际截图已查看；长延迟/金额/卡片不横向溢出，零值柱高度为0，非DEMO无“在线活动/稳定/实时经营”断言。s12h-browser.log与s12h-browser/。
- 根代理核对数据来源、未知值和负值语义、DEMO隔离、保留ROI与金额分；Hygiene无阻断，仅命名单位常量数值提示与仓库无canonical formatter限制。无新增依赖/后端改动，尚未精确远程CI和console运行部署。

### S12h 远程与运行交付
- f0ab7cfe7826e211316ef32318e59a2f3c61dec5精确CI44063426678三项PASS，正常FF main；s12h-ci-exact.json与s12h-delivery.json。仅console更新，control保持b3858e6，其它17容器身份/镜像/启动时间不变。
- runtime-f0ab7cf运行与真实桌面/Pixel7浏览器PASS，18必要容器正常、3个Flink原作业检查点推进；实际首页展示7个已加载活动、4条清单和61天18小时历史延迟，无页面错误、请求失败、业务写请求或横向溢出。已查看真实双视口截图。
- S12h已完成；下一步核对历史Journey节点及版本迁移，原S06–S13计划继续，外部会员/交易/履约合同仍未解决。

## S12i 历史Journey等待节点修复（DONE）
- 只读核对当前库：marketing-platform与retail-cn各有mock-journey-cart-recall v1使用journey.wait_timer@1.0.0，其它Journey节点为当前类型；当前种子已为journey.wait-timer，重新灌种子会覆盖历史版本，不能用作迁移。
- 从d855213建立fix/journey-legacy-definition。修复范围仅已知等待节点旧名：设计器明确展示迁移提示，由编辑动作替换当前草稿类型，保留节点ID、配置、端口、连线、变量及原definitionId；保存沿现有API创建/复用新DRAFT版本，原审批/发布与运行实例不变。不放宽后端注册表，不转换未知语义版本；新注册节点不可用时禁用迁移。
- 验收：加载不隐式改图/写入、权限/注册表/语义版本保护、撤销/重做、保存新版本与失效校验、真实MySQL原版本/审批/发布保留；双视口按钮与保存/仿真流程、lint/build、精确CI和console增量运行验收。仅已核对的本机演示定义可通过现有保存/校验API追加草稿，不提交审批或发布。

### S12i 实现与验证检查点
- 显式迁移、当前注册表/权限保护、撤销重做、原定义ID/连线/变量保留已实现。初始回归2项按预期失败，修复后全前端214项PASS；真实MySQL/HTTP4项PASS，证明v1审批版本/审批行/发布行不变，重复保存只产生v2、校验/纯仿真成功、outbox为空。
- 双视口转换/校验/仿真2项PASS，未发送审批/发布请求。首轮build发现新增Testing Library测试误用了Playwright的exact参数，已删除无效参数，待重新build与受影响测试；lint首轮通过。证据s12i-*。尚未提交或改动真实演示定义。

### S12i 本地验收结果
- 214项前端PASS；修正测试类型后9项受影响测试与最终build通过，lint通过。真实MySQL4项、桌面/Pixel7浏览器2项通过；已查看手机迁移提示与桌面仿真弹窗截图。s12i-before.log保留修复前2项失败，s12i-build.log保留首轮类型失败，其余对应*-final日志。
- 根代理复核仅已知旧名/语义版本转换、写权限/当前目录保护、原图内容保留与新版本幂等、原审批发布不变；没有后端产品代码或公开合同变更。Hygiene通过，仓库无canonical formatter是明确限制，非独立代理审查。待精确CI、FF main及console增量部署，之后对两个已核对本机演示定义追加草稿并复核原始记录。

### S12i 远程与真实数据验收
- 0c8aff81e2fdb44239cfad1c5763259dcc21311c精确CI44067162029三项PASS，正常FF main；仅console更新，control保持b3858e6，其它17容器未变，18必要容器正常、3个Flink原作业检查点推进。runtime-0c8aff8/回执PASS。
- 真实8084桌面/Pixel7读取历史v1、转换与撤销均PASS，零页面错误/请求失败/写请求；截图已查看。随后通过既有API为marketing-platform与retail-cn的mock-journey-cart-recall分别追加v2并校验，3600秒窗口纯仿真均完成（1800秒等待与拟发送指令），未实际触达。
- s12i-seed-plan.private.json保留原完整图和固定幂等键，s12i-seed-result.json记录新版本及结果。两次操作前后原v1定义、审批、发布、Journey运行定义/槽与控制面/旅程发件箱计数散列全部一致；没有重灌种子、替换发布、迁移运行实例或改其它租户。后续恢复先读回v2，不重复创建或重跑旧迁移。
- 下一步S12资产完整分页及其服务端全字段搜索；真实会员来源、交易/取消终态等外部合同仍待确认，整体计划未完成。

## S12j 人群资产分页与全字段搜索（DONE）
- 基线7b98776，分支feat/audience-asset-pagination。当前人群列表一次性返回所有最新定义；先闭合人群资产，再顺序处理权益/模板，字段与节点等受限字典不机械分页。
- 合同：新增GET /api/v1/audiences/page，复用audience:read与身份租户，limit默认25/最大100，cursor为上一页末人群ID，q最长128且trim后回显query；按人群ID升序，每ID只返回最新版本，limit+1计算hasMore/nextCursor。跨页是当前读取，不承诺数据库历史快照；同ID新版本不移动排序，新增较小ID需刷新看到。旧数组接口保持兼容。
- 搜索在SQL中先于LIMIT执行，按可见名称、ID、版本、规则指纹、北京时间创建时间与状态中英文做字面包含匹配；百分号/下划线不是通配符。复用现有PK(tenant,id,version)做租户范围与最新版本查找，不默认增加索引。
- UI复用资产表格、详情、编辑入口及现有视觉，服务端搜索与加载更多、错误重试/刷新；完整身份及搜索缓存键，不能只筛当前页冒充全局搜索。记录已加载条数，不猜总数；变更租户清空旧详情。
- 验收：真实MySQL超过两页、最新版本/游标稳定、后页字段搜索、中文状态/北京跨日时间/字面符号、跨租户/权限/非法参数、查询计划；前端故障/换查询/更多/详情、双视口、合同/lint/build/精确CI及audience+console本地运行。不要重做已完成S12i或Flink恢复。

### S12j 实现检查点
- 新接口/SQL范围搜索/游标协议及OpenAPI已实现；真实MySQL分页3项和原人群流7项共10项PASS。修复前3项按预期404失败，日志s12j-before.log。最新版本更新不重复/漏读已有ID，搜索涵盖后页、中文状态、跨日北京时间与字面符号，越权/非法参数被拒绝，执行计划含PRIMARY。
- 前端已接新分页，提取复用AssetTable并保留权益/字典旧行为；完整身份查询键、后页失败保留旧页/重试、全字段服务端搜索、详情/编辑入口及换租户清除详情。18项针对测试PASS。首轮旧AssetsPage空列表fixture未切新合同导致1失败，已按新接口更新并通过，保留s12j-frontend-first.log。
- 尚未运行最终全前端/lint/build/浏览器/合同、未打包/提交/部署。下一步这些验证及受影响页面回归；最后版本搜索测试增强为不依赖规则指纹中的版本数字，需重跑该MySQL测试。

### S12j 验证修正
- 首轮全前端220项、lint/build通过；浏览器初轮单次503被全局自动重试恢复，未能验证人工重试提示，已改为持续故障直到人工重试，最终人群2视口+模板2视口共4项PASS；模板原隔离服务专项2项未启用（本片不改模板写入，复用此前真实证据）。
- 首轮合同门禁发现API_PATHS清单未登记新接口，已补齐精确路径，不放宽断言；该轮停在合同，架构与增强后MySQL未执行，正在重跑。进一步为服务端搜索增加300毫秒输入防抖，等待时隐藏旧筛选结果且保留输入焦点；此最终调整待重新验证。

### S12j 最终本地验收
- 最终搜索防抖版本220项前端/43文件、lint/build、人群桌面/Pixel7两项浏览器均PASS，已查看手机完整搜索详情和桌面最后一页截图。10项真实MySQL（3项新增分页+7项原流）、7合同、10架构检查通过；s12j-*-verified.log为最终证据。
- API合同清单漏登记和浏览器单次503自动恢复的验证问题均已修正，保留失败日志。服务端租户/权限与参数边界、最新版本查询、游标独立搜索、前端旧页保留/身份切换/防抖已复核；跨请求不承诺冻结快照，包含搜索仍需扫描当前租户候选，未宣称容量压测通过。
- 待打包/Hygiene、精确远程CI、正常FF main及audience-service与console本机增量更新。无数据库迁移、不重启Flink、不重灌演示数据。
- 人群可执行JAR打包成功，Hygiene无阻断；仓库无canonical formatter，记录为工具不可用限制。语义化分页常量触发的魔法值建议经复核无需改动。最终源文件指纹保留s12j-validation-fingerprint.json。

## S12k 权益资产分页与全字段搜索（DONE）
- 分支feat/benefit-asset-pagination基于S12j a260865，依赖其资产表格提取；先合并部署S12j，再交付本片。S12j精确CI44072001325前端与部署PASS，Java下载Maven遇429、未执行测试，已仅重试失败job；两镜像和Nginx预检通过，尚未部署。
- 新增GET /api/v1/benefits/catalog/page，benefit:read、身份租户、limit默认25/最大100、cursor上一页末权益ID、q最长128且trim回显，按权益ID升序只读每ID最新版本，limit+1判定后续。旧数组/单条读取和写入、库存、发放、外部SKU校验不变；本查询不调用外部中台。
- 服务端字面包含搜索名称/ID/版本/绑定SKU（含未绑定提示）/状态原码及中文/北京时间创建时间；SQL先过滤再LIMIT，复用租户主键，无迁移。分页非冻结快照，较早新ID需刷新。
- UI使用完整身份缓存键、300ms输入防抖、加载更多/重试/刷新、已加载计数和详情编辑。验证真实MySQL多页/最新版本/边界/隔离、前端故障/旧请求/身份切换、双视口/合同/lint/build/精确CI及benefit+console真实本地运行。

### S12k 实现复核
- 新分页/SQL搜索和前端更多/失败恢复已实现；首轮19项真实MySQL（16旧流+3分页）、7合同、226前端/44文件、lint/build、10浏览器通过。初轮测试fixture变量拼写编译失败与旧UI同步断言失败保留日志，已修复后通过；collections布局回归的预期无后端代理日志不作为真实API验收。
- 发现旧权益ID允许page，分页路由改为/benefits/catalog/page，避免遮蔽既有GET /benefits/{benefitId}；新增page详情兼容回归。此前证据对路径变更未生效，正在重跑受影响检查。

### S12k 最终本地验收
- 最终/benefits/catalog/page版本20项真实MySQL（4新分页/兼容+16旧业务）、7合同、10架构检查PASS；226前端/44文件、lint/build、8浏览器（权益2视口+collections6）PASS。已查看手机详情状态/时间和桌面62条末页截图。证据s12k-*-final.log；基线3项404失败及中间失败均保留。
- 根代理复核游标租户验证、SQL参数绑定/搜索先于LIMIT/同ID最新版本、旧page详情兼容、零新增数据库迁移与外部调用；未承诺包含搜索容量压测。打包/Hygiene与远程CI、实际Docker更新尚待完成。

### S12j 真实运行交付
- a260865ceb215140851ad5933cd799436448256c精确CI44072001325三项PASS；Java首次下载Maven遇HTTP429，尚未运行代码，失败job重试成功。正常FF main，仅更新audience-service与console。runtime-a260865/回执PASS。
- 18必要容器正常，16其它容器身份/镜像/启动时间不变；3个原Flink作业保存点来源和检查点推进PASS。实际人群2条按limit1遍历与旧集合一致，搜索/非法参数/权限/租户拒绝PASS；真实8084桌面/Pixel7详情、搜索、空结果、清除搜索PASS，零写请求/API失败/页面错误，手机详情截图已查看。
- S12k可执行JAR打包和Hygiene完成，无阻断；无canonical formatter限制保留。只读包含搜索有租户内扫描成本，本地测试不代替容量基准。最终指纹s12k-validation-fingerprint.json，待精确CI和增量本机部署。

## S12l 消息模板分页与服务端搜索（DONE）
- 分支feat/template-asset-pagination基于S12k 53e151f，先交付S12k再本片；S12k精确CI44073965831运行中，两镜像与环境保留/真实Nginx预检通过，尚未部署。
- GET /api/v1/templates/catalog/page：template:read、身份租户、limit默认25最大100、cursor模板ID、q最长128回显，ID升序每ID最新版本，limit+1；旧数组和POST版本追加语义不变。搜索覆盖模板ID、可见v版本、渠道、必需变量（含无）、状态原码/中文和北京时间创建时间，正文仍只在详情查看。最新版本筛选与搜索在LIMIT前执行，非冻结快照，无迁移。
- 模板变量按持久化CSV顺序稳定回显，使显示值/服务端匹配一致（原Set.copyOf不保证迭代序）。UI完整身份键/防抖/更多/错误恢复/刷新/详情编辑，保存后失效原数组与新分页缓存，保持筛选和版本成功回执，不改变历史版本/旅程引用/触达。
- 验证多页/搜索/版本/权限/租户/输入边界/变量顺序与旧版本保留；前端分页恢复/跨身份/保存后最新版本刷新/冲突与草稿保护；双视口、合同/构建、精确CI及engagement+console本机运行。

### S12k CI夹具修复
- 53e151f的CI44073965831浏览器发现form-controls仍拦截旧权益数组，导致两视口搜索用例失败（其它82通过、10条件专项未启用）。已仅修正该fixture为新分页合同并保留原断言，双视口专项PASS；607542e测试修复提交已正常推进权益分支，新精确CI44075059624运行中。
- 607542e与53e151f产品源/依赖/构建配置无差异（git diff仅该e2e文件）；runtime-607542e以已校验53e151f镜像制品只更新revision标签，原JAR/dist散列和来源记于manifest。重新通过环境保留和真实Nginx预检，尚未部署，不使用当前未提交模板源构建权益版本。

### S12l 实现检查点
- 模板SQL分页/变量稳定顺序/显示字段搜索、合同与前端分页及保存后失效缓存已实现；31项MySQL（3分页+28原触达/模板流）、7合同、10架构检查PASS，s12l-backend.log。修复前3项404按预期失败；前端/浏览器仍在验证，不宣称本片交付。

### S12l 真实写入验收
- 232前端/45文件、lint/build/package/Hygiene通过。首轮浏览器误用TestingLibrary的toHaveTextContent，按Playwright改为toContainText后6项双视口通过，其中2项直连隔离MySQL/实际engagement服务创建v1、编辑v2、按ID搜索并刷新详情；库验收2模板各v1/v2、原正文保留、触达/发件箱均0，s12l-live-db.json PASS。
- 隔离库template_page_live_20261117保留；首次MySQL刚启动未就绪导致建库失败，读回确认未创建后再执行成功；已停止本次测试Java和测试MySQL恢复必要容器集合，不删除数据。新页面手机详情和桌面62条末页截图已查看，发现时区表头与单元格重复，改为表头注明北京时间、值仅显示日期时分秒，使搜索与显示完全一致。此展示修正需重跑受影响前端/浏览器，真实写入逻辑未改。

### S12l 最终本地验收
- 展示时间修正后232前端、lint/build、4浏览器再通过（此轮2个真实写入专项未再启用，复用之前成功数据库证据，写入逻辑未改）；手机详情最终截图已查看，时间与表头清晰，变量顺序和正文完整。最终前端日志s12l-frontend-final、lint-final、build-final、browser-display；数据库/真实浏览器证据s12l-backend、browser-final、live-db。
- 31真实MySQL/7合同/10架构和JAR仍对应当前后端；复核范围仅最新模板分页、读取顺序与UI缓存。无接触消息、出站调用或生产数据；含搜索有租户扫描成本，未宣称容量验证。准备指纹、提交、精确CI及engagement-service+console本机更新。

### S12k 真实运行交付
- 精确607542e7353793a22b413b960b688b6a5dcd359f的CI44075059624三项PASS，正常FF main；仅benefit-funding-service与console更新，audience保持a260865。runtime-607542e回执PASS，18必要容器正常、16受保护容器不变、3个原Flink作业继续推进。
- 真实4条权益limit1完整遍历与旧数组集合一致、后页ID搜索/非法参数/权限/租户拒绝通过；8084桌面/Pixel7详情、搜索、空结果、清除通过，无业务写入/API失败/页面错误，手机完整详情截图已查看。无数据库迁移/种子改动/外部SKU调用。S12l当前改动尚未部署。

## S12m 前端错误采集（DONE）
- 分支feat/client-error-collection基于S12l 166dc43；先交付模板再本片。模板精确CI44076660106三项PASS并正常FF main；runtime-166dc43已完整部署验收，后续本片使用独立制品。
- 现状：report.ts经无认证sendBeacon发送任意message/租户/操作者/原路径，Nginx /client-error直接204丢弃。按已批准S12补齐真实采集：POST /api/v1/client-errors在现有Servlet Gateway内处理，经既有DEV/OIDC身份链和独立租户Redis配额（复用已有基础设施），异常收集不走递归API报错路径。
- 最小事件合同：schemaVersion=1，source固定API/WINDOW/PROMISE/REACT/UNKNOWN，route固定模块枚举，httpStatus=0或100–599，backendTraceId可选32位十六进制；请求最多1KiB、拒绝额外字段/非法枚举。身份只取已验证上下文，不接受正文租户/操作者；不采集任意message、堆栈、URL、查询参数、token或业务ID。服务端参数化结构日志及低基数Micrometer计数，不新增业务数据库或集中采集产品；此片是有界诊断接入，不宣称长期留存或生产可观测性全完成。
- 前端raw fetch keepalive携带当前认证（不用无认证beacon），限制本页每分钟上报次数，失败静默不递归；事件只含允许字段，已退出/DEMO缺身份跳过。旧 /client-error 明确退役，取消假204；edge-gateway本地日志按容量轮转，避免无界增长。新增公共契约、配置及运行说明。
- 验证真实HTTP身份/入参/正文大小/独立限流/Redis故障关闭/日志及指标无敏感字段；前端OIDC/DEV/匿名/DEMO/事件隐私/节流/失败无递归；完整构建与合同/架构、精确CI、gateway+console真实Docker端到端采集。尚未实现。

### S12l 真实运行交付
- 166dc43f5ca29965b249c3a791803b8600d957fb的CI44076660106三项PASS，正常快进并推送main；仅engagement-service与console更新。runtime-166dc43最终PASS，18必要容器健康，16受保护容器未变化，3个原Flink作业检查点持续推进。
- 真实4条模板limit1完整遍历与旧数组集合一致、后页ID搜索/非法参数/权限/租户隔离通过；8084桌面/Pixel7详情、搜索、空结果、清除通过，无业务写入/API失败/页面错误，手机截图已查看。无数据库迁移、重灌数据、消息发送或Flink重提。下一步S12m真实错误采集。

### S12m 首轮实现与验证
- Gateway固定事件合同/1KiB严格UTF-8 JSON/可信租户摘要/固定枚举指标、独立Redis配额及容量日志轮转已实现；前端改认证raw fetch、3秒截止、每页每分钟10条、退出清空上下文、无原始文本/路径/身份和重试。旧Nginx丢弃入口返回410。
- 首轮7采集HTTP（含JWT decoder受控边界）、4原网关准入/超时、6租户身份、7合同、3架构测试通过；25前端定向测试、lint/build通过。完整浏览器86通过/10隔离环境项跳过，新采集2项失败仅因最后一步使用了不存在的“人群”标签，采集/隐私/10条上限断言已过；已修正为实际“Audience 快照”，待重跑。合同增加1项隐私白名单检查，完整最终验证仍待完成。
- 原资产分页用例中业务写请求断言排除明确的诊断端点；诊断本身由新用例完整断言，未放宽业务写入判断。修复同步fetch异常时清理截止计时器，尚未提交/部署。

### S12m 最终本地验证
- 239前端测试/46文件、lint/build通过；完整浏览器88通过/10依赖隔离服务的既有项跳过（s12m-browser-diagnostics.log实际为完整套件）。新采集桌面和Pixel7均真实触发window error、捕获认证请求、503不递归、每页10条上限；旧业务断言保留。
- Gateway 7项采集HTTP（含JWT验证器受控边界）和4项原有限流/超时、6租户上下文、8合同、架构规则通过；固定无HTTP状态常量在Hygiene复核后提取，需以s12m-gateway-final/package-final为最后后端证据。Hygiene无阻塞，项目未提供canonical formatter；已命名常量与不同业务模块的错误码短名提示经人工核对无需合并。
- Compose模型校验通过；只调整本机网关日志容量，不改变共享依赖、业务表、权限规则或生产目标。当前待提交/精确CI及本机gateway+console部署；不把本地通过视为全部交付。

## S12n DEMO活动数据真实性（DONE）
- 基于S12m 7764526建立fix/demo-campaign-truth；M的精确CI44081527926运行中，runtime-7764526镜像/环境保留/Nginx预检PASS，尚未部署；后续只能使用其固定制品。
- 已定位唯一生产demo数据导入为CampaignsPage→shared/data/demo.ts。它还在浏览器构造CMP ID并将本地添加当成成功；已有system-mock.sql包含真实数据库演示活动，runtime API可读，不需要重灌共享库。
- 本片将旧样本移到测试fixture，正式页面只显示真实API活动；DEMO模式保持不调用真实接口、说明如何连接后端、禁用创建/URL直开创建/手动刷新，不显示假活动或假成功。真实模式保存/导航及权限保持，测试覆盖模式切换缓存、错误、创建和浏览器，校对全前端其余DEMO分支。
- 验证后独立提交/精确CI/正常FF main/console增量部署；不新增身份映射、自动重灌数据或业务发布。

### S12n 实现检查点
- 活动页取消本地活动数组、伪造CMP ID与本地“创建成功”；读取绑定完整身份scope，静态预览/权限不足时不读缓存数据、不请求API、不刷新或打开URL创建表单，真实创建仍使用服务端ID导航。旧业务样本移到src/test/fixtures/campaigns.ts，仅测试导入，旧未使用的过时节点样本移除。
- 5定向单元测试与首轮build通过；浏览器/完整前端仍在执行。既有SQL种子已提供活动数据，本片不重新写共享库。进一步120处DEMO分支核对发现DashboardPage与OperationsPage仍有内嵌业务数字/运行记录，列入后续S12o清理；不将S12n宣称全部DEMO完成。

- S12n首轮全量前端242通过、App路由2项失败因旧测试依赖DEMO假创建；已把这两项改成明确真实模式的API fixture，保留原深链/校验/筛选保留断言。静态预览及原Offer/裂变/Journey作者浏览器8项通过。S12m精确CI44081527926已三项PASS并FF main，gateway+console healthy，真实smoke正在执行。

### S12m 真实运行交付
- 77645267a94bbe310e74bb3c842ed0420d9fbfb7精确CI44081527926三项PASS，正常FF main；仅edge-gateway与console更新，runtime-7764526最终PASS。18必要容器健康、16受保护容器不变、3个原Flink检查点推进；本机网关日志local/10m/3已核对。
- 真实8084桌面/Pixel7触发window error→认证诊断POST204→网关安全分类日志，业务页面继续可用，无业务写请求。18080验证匿名401、额外字段/超限400、真实Redis独立配额429且活动读取仍200、低基数Prometheus指标；非法敏感标记未进日志，旧/client-error 410。证据diagnostics-browser/api及runtime-smoke.json。未停止Redis、未触达业务、未新增数据表；不宣称生产身份/长期留存/告警完成。

### S12n 最终本地验证
- 244前端测试/47文件、lint/build通过；静态预览与原真实模式Offer/裂变/Journey作者交互8浏览器通过。修复测试内误用Playwright的exact选项后类型构建通过；移除活动页矛盾的“演示数据可浏览”横幅，单一连接说明复验桌面/手机2项通过，手机截图已查看。
- Hygiene无阻塞（仓库无canonical formatter）；后端未改，无需重跑已有MySQL测试。原SQL种子及本机数据未写入，生产代码不再导入活动fixture；现有S12o首页/运行中心硬编码仍待处理。本片待独立提交/精确CI/console运行验收。

- N候选453d6e5已推任务分支并触发CI44083895972，runtime-453d6e5固定console预检通过尚未部署。预判完整浏览器首项仍依赖旧DEMO本地创建，将该用例改为真实模式+显式创建API fixture，保留深链、服务端ID导航与名称断言；需新测试提交和精确CI，不能用453候选提前部署。

- N完整浏览器首轮86通过/10跳过，4失败来自collections与form-controls两用例的桌面/手机仍使用旧DEMO列表/弹窗；已切到真实模式，并复用迁移后的测试fixture经API返回，原键盘、筛选、焦点、菜单和表单断言不变。console首项服务端ID创建改造已通过；待完整最终回归。

- N最终完整浏览器90通过/10既有独立服务项跳过，3处旧DEMO依赖全部改成接口fixture，保留原行为断言；最终lint/build再通过（s12n-fixture-*）。产品源码与453d6e5相同，测试修正提交后需新的精确CI。

## S12o 首页与运营静态预览真实性（DONE）
- 基于N最终db30029新分支fix/static-preview-truth。N最终精确CI44085089985运行中；runtime-db30029固定console镜像/预检PASS，sourceArtifact453d6e5与最终dist散列一致，仅测试/进度变更。等CI后正常FF main，再deploy→verify-runtime→campaign-browser，不重复453候选部署。
- 清理首页硬编码经营指标、12小时柱图与Cell就绪，运营中心硬编码营销轨迹/候选、Journey/触达/隔离消息及资金PASS。DEMO只显示静态预览/连接提示，不请求或展示缓存中的业务数据，不显示伪造零值或成功。统一DemoBanner文案与真实能力一致；真实接口读取、时间、负数、失败恢复与写入门禁保持。
- 既有system-mock.sql/retail-cn-extra.sql已覆盖活动/事实/发布/实例/触达/隔离/账本；这次只检查数据来源，不重灌共享库、不造真实运行结果。验证DEMO所有相关页无API请求/假业务数据、缓存隔离，真实模式既有回归、桌面手机、lint/build/完整前端、精确CI及console增量Docker。

### S12n 真实运行交付
- db3002953da9d698c62583838582a0b15e607b74精确CI44085089985三项PASS，正常FF main并推送。runtime-db30029仅更新console，18必要容器healthy、17保护容器不变、3个Flink检查点继续推进；13无关容器保持停止。
- 8084桌面/Pixel7实际API活动完整显示、搜索/空结果/恢复、创建表单打开取消通过，无业务写入/API失败/页面异常。手机真实列表截图已查看；没有数据库迁移、重灌数据或Flink重提。

### S12o 实现检查点
- 首页DEMO指标改为未知占位、图表/发布连接提示；运营DEMO轨迹/实例/触达/隔离/资金统一连接提示，移除伪造PASS与旧Grafana死链接。DemoBanner改为静态预览说明，真实API分支保持。初始类型构建通过。
- 新增暖缓存隔离及六运营Tab零请求验证、桌面手机静态预览浏览器用例；定向验证正在执行，尚未提交/部署。

- S12o定向首轮11通过/1失败，暖缓存用例发现发放Tab虽禁用请求仍渲染旧缓存，已增加整页静态预览早返回阻断记录/详情/加载更多；保留失败证据，需重跑。8项桌面手机静态预览及既有真实轨迹/登出回归通过。

### S12o 最终本地验证
- 246前端测试/48文件、lint/build通过；完整浏览器92通过/10既有独立服务项跳过。初轮2失败因发放静态早返回省略原页标题，恢复标题后完整通过；暖缓存用例确保首页及六运营Tab不读API、不显示旧数据或假PASS，手机长图已查看。
- Hygiene无阻塞；仓库无canonical formatter，格式化自动检查不可用，git diff --check通过。后端未变，复用既有真实服务证据；未写共享数据库、未发外部消息。准备独立提交/精确CI与console本机部署。
- 相邻审查发现OIDC登录/令牌更新路径未像DEV路径一样清缓存和失效旧请求；后续S12p按真实身份范围切换补会话边界，避免旧租户/权限数据和草稿带入新会话。

## S12p OIDC会话边界（DONE）
- 基于S12o66ba4c9建立fix/oidc-session-isolation；O精确CI44087403939运行，runtime-66ba4c9固定console镜像/环境保留/Nginx预检PASS，尚未部署。
- 已有DEV会话清理不能覆盖OIDC：applyUser只替换身份/令牌，logout只clear缓存，没有失效旧HTTP/mutation或卸载旧草稿。修正当前身份、业务租户、组织、店铺及权限变化时的会话边界；相同身份与权限的普通令牌更新保留编辑内容。
- 切换立即停止旧身份、卸载观察者、取消查询并清缓存，失效旧请求回执，再安装最新身份；以代际防止慢初始化和并发身份事件覆盖新身份。退出/过期/401同样清理，错误不将旧令牌复活。不变更OIDC身份源、后端信任或业务会员ID。
- 验证真实API客户端的迟到读写/401、切换/权限变化/同范围令牌更新/退出/过期/并发初始化；复用DEV回归及全前端/浏览器、精确CI和本机console部署。外部IdP端到端未由本地模拟证明。

### S12p 首轮验证与修正
- O精确CI前端与Compose通过，Java在Maven下载阶段HTTP429（未编译），已仅重跑失败job；不绕过门禁。
- P首轮264前端通过，包含18OIDC边界用例及原DEV/API回归；初始构建缺useRef参数、测试请求缺scope字段已修正。双视口当前401及原轨迹/DEV通过；远端退出失败暴露OIDC RedirectNavigator.close的window.stop中断LoginPage懒加载，已保持会话转换页至远端流程结束再挂登录页。只保留新的身份与权限，普通令牌刷新不清草稿，旧令牌401不退出新令牌。
- 新增浏览器使用真实oidc-client-ts和受控IdP/API接口返回，验证客户端错误恢复；不代表真实IdP或生产认证联调。最终全量前端/lint/build和定向浏览器正在复验。

### S12o 真实运行交付
- 66ba4c938d670cfe120468bccf507d1ff83a415d精确CI44087403939重试失败下载job后全部PASS，正常FF main并推送；runtime-66ba4c9 console部署PASS，18必要容器healthy、17保护容器不变、Flink检查点推进。
- 8084桌面/Pixel7首页活动数量与实际API一致，运营轨迹默认空输入、实例/触达/隔离消息及资金对账都读取真实接口；零业务写入、API失败、页面异常。真实资金PASS由本次接口结果证明，手机截图已查看；静态预览不会借此显示PASS。

### S12p 最终本地验证
- 266前端测试/49文件（20项新增OIDC会话用例）、96浏览器PASS/10既有隔离服务用例跳过，lint/build/Hygiene无阻塞。增加旧登录回调与新身份/退出竞争覆盖，最终s12p-frontend-final2/lint-final2/build-final2及browser-full为证据；无canonical formatter。
- 浏览器使用真实oidc-client-ts和受控API/IdP，验证当前401清会话、远端退出失败恢复及原DEV/轨迹回归；手机登录失败恢复截图已查看。更新安全基线，修正owner不是业务租户的旧描述；实际IdP生产联调保持未验证。
- 准备独立提交/精确CI与console本机更新。

## S12q 资产静态预览读取门禁（DONE）
- 基于e9d2661建立fix/asset-preview-reads；P精确CI44090681778运行，runtime-e9d2661固定console镜像/预检PASS，尚未部署。O已完成main与本机交付。
- 审查发现人群/权益列表虽然enabled=false，AssetTable刷新仍能手动refetch，且旧缓存可能继续渲染；模板、字段/节点及裂变/分析选项也需隔离禁用模式缓存。静态预览使用连接提示，移除可调用业务接口的入口；不把未查询显示为真实零条。真实模式分页/详情/编辑保持，字段/节点按各自读取权限显示。
- 通过暖缓存单元测试、双视口静态资产/裂变/分析零请求检查及既有真实分页/模板回归验证；不重灌种子、不创建后台模拟记录。

### S12p 真实运行交付
- e9d2661精确CI44090681778三项PASS，正常FF main并推送；runtime-e9d2661固定console部署、18必要容器/17保护容器与Flink推进验证PASS。
- 桌面/Pixel7真实首页/实例/触达/隔离/对账只读验收通过；真实DEV退出后旧表格消失，再登录恢复原活动页和同一API集合，零业务写入/API失败/页面异常。首轮smoke错误假定登录后固定返回首页，实际正确保留returnTo=/campaigns，修正断言后通过；未重部署。运行环境仍为DEV，真实OIDC IdP联调未验证。

### S12q 最终本地验证
- 271前端测试/50文件、98浏览器PASS/10既有独立服务用例跳过，lint/build/Hygiene无阻塞；无canonical formatter。5新增用例覆盖四资产Tab暖缓存/无刷新入口、裂变/分析缓存选项及字段/节点分别鉴权；既有真实资产分页/搜索/详情/编辑回归通过。
- 桌面/Pixel7静态首页/运营/四资产Tab/裂变/分析零业务API请求，手机资产连接提示截图已查看；未重灌数据库或新增示例记录。准备独立提交、精确CI和console本机更新。

### S12q 真实运行交付
- `dbe268b5ed25004c5ee35af171ceb147e2509e55`精确CI44092826351三项全部PASS，正常FF main并推送；固定console镜像部署PASS。18必要容器运行、15健康检查通过、无OOM，17保护容器不变，原3个Flink作业检查点推进。
- 桌面/Pixel7真实人群、权益、模板分页/搜索/详情与字段/节点读取验收全部通过；无业务写入、API失败、页面异常或整页横向溢出。字段/节点手机截图已查看；不重灌种子、不迁移数据库、不恢复/重提Flink。
- 证据：既有私有目录`s12q-ci-exact.json`、`s12q-git-delivery.json`及`runtime-dbe268b/{rollout-state,runtime-smoke,audience-browser,benefit-browser,template-browser,registry-browser}.json`。后续文档回执不改变本轮验证的产品代码/运行制品；本任务整体仍PARTIAL，未完成依赖见当前状态。

## S11e 活动组合准备（本地验证通过）
- 分支feat/activity-release-bundles基于d57a572。组合准备/历史读取、严格16KiB输入、规范幂等、活动范围和签名成员检查、V8持久化及单清单成员保护已写；尚不能整体激活。
- 编译PASS（s11e-compile.log）。首轮测试编译因新ACK fixture参数类型不符失败，已按真实RuntimeAckWrite修正；第二轮真实MySQL测试进行中，日志s11e-backend-second.log。无共享库写入或部署。
- 实施复核将成员归属存入原清单行，避免独立空索引间隙锁造成不同活动之间的登记死锁；设计已同步。规范采用architecture-standards的业务建模/持久化/接口/并发/容量/发布，以及personal-tech-stack并发、状态机；API/授权/审计/DB专题已读。

- 首轮真实数据库回归中，组合9项有8项PASS，发布目标5项、控制链4项、DMN草稿6项全部PASS。唯一失败为“重复slot”fixture同时重复了既有唯一generation键；已改为同slot不同generation，保留原拒绝断言，新增不同活动并发登记验证。最终10组合+旧门禁+机器合同复验日志s11e-backend-final.log，session12756。

- 最终轮发现真实并发缺陷：不同活动同时首次准备时，空组合历史的FOR UPDATE范围产生间隙锁死锁。已改为在始终存在的campaign行上持久分配版本（CAS且与组合/审计同事务），不降低隔离级别、不增加自动重试。其余9组合、13旧门禁及8合同已PASS；针对性10项复验及审计回滚后的序号检查正在继续。

- 并发修复后10项真实MySQL组合测试全部PASS（s11e-concurrency-recheck.log）。活动范围与旧入口提取共用检查，避免新建第二份错误/范围规则；最后范围改动影响的组合、控制流程与DMN测试正在复验（s11e-scope-final.log）。未宣称全项目完成。

### S11e 最终验证
- 共用活动范围检查后，10项组合、4项控制流程、6项DMN集成测试全部PASS（s11e-scope-final.log）。旧发布目标5项及13项发布门禁此前通过且对应代码未再改；合同8项和13份JSON验证、架构检查PASS（s11e-contracts-final.log、s11e-architecture-final.log）。
- 根代理另行按越权重放、并发锁序、失败回滚、成员签名/审批、输入上限及未发布副作用进行对抗复核；未发现本片剩余阻断。审计故障测试日志中的SQL异常是注入故障，断言全部回滚并成功重试。
- Hygiene和diff检查PASS，仓库没有canonical formatter为明确限制；新增错误目录沿用平台异常，活动权限与审计链复用原规则。文档地图、架构边界、接口说明及机器合同已同步。
- 本片不部署、不写共享库，不声称组合整体激活完成；接下来精确候选CI/正常合入main，并继续S11f。

### S11e 远程交付检查点
- 0b332bf精确CI44101729406三项全部SUCCESS，已正常FF合入并推送main，远程一致。连接器没有workflow dispatch能力，使用gh启动，运行结果继续通过GitHub连接器核对。
- S11f采用增量设计末节，先实现签名组合导出（f1），再运行端精确准备（f2）与控制面组合ACK门禁（f3）。不改变已批准整体生效/回滚原则。

## S11f1 完整组合签名导出（验证通过，待合入）
- 在S11e之上新增只读签名导出，不修改原准备版本/摘要；独立签名用途覆盖全部成员内容，签名前重验成员可信性；到期拒绝导出但仍允许查看历史。
- 当前权限/组织/店铺/租户保护、12项真实MySQL组合用例（包含存储损坏与过期历史）、4项新组合签名、1项旧签名及8项机器合同PASS；全合同脚本/13份JSON PASS。日志s11f1-final.log、s11f1-first.log、s11f1-contracts.log；Hygiene/diff PASS（无formatter限制）。
- 独立于编码的根代理复核：完整成员字段进入外层规范字节、用途隔离、内容hash与首片兼容、导出无数据库写入或激活副作用；未发现当前片阻断。没有新增依赖、迁移或运行进程。
- 下游继续S11f2运行端精确成员校验/组合ACK，再S11f3控制面门禁；整体原子生效仍待S11g，不能提前开放UI发布。

### S11f2 恢复检查点
- 中断后核对：当前HEAD/远程任务分支6d21caf，main/origin=0b332bf；上轮dispatch已成功启动44102999560，无需重复触发。
- 正在实现组合专属双层签名ACK、runtime:warm保护且1MiB有界的两个prepare入口；从原generation持久库验证完整成员与制品，Journey同时核对已安装定义，不变更active pointer。
- 无新增基础设施或迁移；共享签名协议、Decision/Journey及对应测试为本片范围，沿用现有信任/运行身份/容量配置。

- S11f2首轮接口文件编译遗漏类结束括号已修（s11f2-compile.log保留）；修复后6项合同签名用例及Decision 5项、Journey 6项真实MySQL/HTTP用例全部PASS（s11f2-first.log）。两个服务均关闭并重建Spring应用上下文后，仍能从持久库准备generation2且线上指针保持generation1；这不是生产进程崩溃或多副本容量验收。应用上下文关闭时Tomcat acceptor日志为关闭socket，不是用例失败。
- 当前继续旧Decision/Journey回归、最终机器合同与架构检查；无活动测试进程，随后启动相关检查并保存日志。

### S11f2 最终验证与交付准备
- 新增运行端11项真实MySQL/HTTP、6项签名协议测试PASS（s11f2-first.log）。旧Decision业务/持久化4项PASS；旧Journey两项首次因Ryuk socket连接失败而未进入业务测试（s11f2-regression.log），单独复跑2项PASS（s11f2-journey-recheck.log），未禁用清理机制或修改业务断言。
- 最终合同脚本及13份JSON、架构检查PASS（s11f2-contracts.log、s11f2-architecture.log）；Hygiene/diff PASS，仍仅缺canonical formatter。根代理从跨租户、签名用途、持久化与缓存差异、准备副作用、旧接口兼容角度另行复核，无本片剩余阻断。
- 测试进程已结束，18既有必要容器保持运行健康，无部署或共享库写入。S11f1候选6d21caf的CI44102999560已全部SUCCESS，准备正常合入main；S11f2准备逻辑提交及精确CI。
- 后续S11f3具体合同已补入增量设计；只收集组合专属ACK并按每成员计算runtimeReady，整体激活依旧属于S11g。

- S11f1的6d21caf已正常FF合入并推送main，精确CI44102999560三项成功。S11f2提交a302414已推任务分支；暂存检查补发现新增测试一处行尾空格，立即用后续小提交修正，不重写已推历史。此前diff检查未覆盖未跟踪文件，此后以完整暂存/基线范围复核为准。

## S11f3 组合ACK收集与当前就绪计算（实现中）
- S11f2格式修正候选c0322e5已推送，精确CI44105058747；main/origin仍6d21caf。S11f3控制面接口、证据表V9及验证逻辑已写，编译PASS（s11f3-compile.log）。真实MySQL集成测试session19133，日志s11f3-first.log。
- ACK写事务的首个SQL锁定既有组合头，然后才读取活动范围/证据，防止REPEATABLE_READ在等待锁前建立旧快照；不调整隔离级别。重放/乱序不回退记录，固定同时间冲突拒绝，各成员最多64个身份；只在当前请求内复用成员验签结果，避免副本数放大重复计算。
- 每成员独立满足副本/容量且证据有效才runtimeReady；当前审批/停用及正式整体激活仍由S11g执行。未部署、未迁移共享库。

- S11f2远程44105058747后端失败：旧Decision持久化测试把全租户运行指针列表第一条当成自己的租户，新增合法组合数据使假设失效。已按查询真实合同改成定位指定租户并断言唯一性/generation/activationSequence；Decision全部20项、Journey全部8项及依赖模块测试PASS（s11f2-module-final.log）。首轮修正误用record访问器的编译失败保留于s11f2-module-recheck.log。修复提交9fc65e3单独交付，不夹带尚未验证的S11f3。
- S11f3首轮9项真实数据库测试PASS（s11f3-first.log）；进一步补齐组合/成员三列外键、损坏组合头拒绝及对应测试，待最终复验。原数据库隔离级别不变。

### S11f3 最终本地验证
- 最终10项组合就绪、12项组合准备及5项旧发布目标真实MySQL测试全部PASS，机器合同8项PASS（s11f3-final.log）；完整合同脚本/13份JSON及架构检查PASS（s11f3-contracts.log、s11f3-architecture.log）。
- 覆盖同身份重复/乱序竞争、不同身份及不同组合并发、每成员副本/容量门槛、五分钟到期与刷新、原始单清单ACK拒绝、权限/租户/范围、16KiB边界、审计故障回滚、64身份上限、损坏证据排除、数据库跨组合外键及损坏组合头拒绝；未产生激活或Outbox副作用。
- 根代理另行复核锁前快照、跨组合成员归属、签名与时间绑定、容量溢出上限、历史过期读取及重复回执；当前片无剩余阻断。完整暂存范围Hygiene/diff为最终交付门禁（初步报告不替代它）；无canonical formatter仍明确保留。
- S11f2修复9fc65e3已推送，精确CI44107210890运行中。S11f3待逻辑提交/精确CI；后续进入S11g请求固定版本、整体激活/回滚与旧入口防绕过，准备就绪不等于线上整体生效。

## S11g1 控制面同slot发布保护（实施中）
- 核对发现：原单清单门禁只查具体manifest的组合归属，同slot新清单仍能绕过。按已批准全入口保护要求，准备成功时将slot标记为组合管理，单清单激活/回滚（含旧幂等回执）拒绝；原已生效配置继续服务，暂存新版本与紧急停用不受影响。
- V10扩展原slot行并回填已有组合成员，固定slot顺序更新与单清单写入共用行锁；标记随组合/审计事务提交，失败全部回滚。此标记不代表组合激活，也不取代运行端防绕过。
- 验收计划：其他清单/活动同slot、旧回执、准备与单清单竞争、审计回滚、跨租户/完整slot隔离、V9→V10存量回填及旧非组合流程回归；仅隔离MySQL。

### S11g1 验证检查点
- 新增5个发布保护场景与V9→V10真实升级用例通过，组合测试17项+迁移1项PASS（s11g1-concurrency-second.log）；前一轮10项组合就绪和5项旧发布目标PASS且产品代码未再变化（s11g1-first.log），共33项不同真实数据库用例。旧读快照在锁内仍被最新标记拒绝，共享slot的两个组合并发准备无死锁，失败事务不遗留标记。
- 首轮新测试因可信TenantContextHolder的写方法是包内可见而编译失败（s11g1-concurrency.log）；使用仅测试源码的同包辅助类修复，未放宽生产访问边界。所有测试进程结束，合同/架构/Hygiene继续执行。
- S11f3候选a8664e2精确CI44108641742三项全部SUCCESS，已推任务分支；因当前S11g1工作树有修改，暂未切main，不清理或暂存丢弃工作。待本片验证后按正常FF一并交付。

### S11g1 最终复核
- 合同全套27项与13份JSON PASS（s11g1-contracts.log），架构10项PASS（s11g1-architecture-final.log）。最初架构命令误写测试类名，没有执行用例；不将其BUILD SUCCESS当架构证据，已按实际ArchitectureRulesTest纠正执行。
- 根代理在编码之外复核了manifest→slot锁序、REPEATABLE_READ旧快照、单清单旧回执、完整slot五维隔离、已有组合回填与不可逆标记的回退要求；当前片无剩余阻断。完整暂存Hygiene/diff PASS（s11g1-hygiene-final.json，仅无canonical formatter限制）；准备逻辑提交及精确CI。真实运行端/Flink原子激活仍未完成，无部署或共享库迁移。

## S11g2 整体激活授权协议（实施中）
- S11g1提交77c1d38已推，精确CI44110842403运行中；S11f3 a8664e2已正常FF合入并推送main。本机无部署、共享迁移或Flink操作。
- 下一片只补独立签名的整体激活/回滚授权合同，绑定完整组合与真实活动范围；与实际运行应用/参与版本绑定分开。具体字段和安全约束见增量设计末节。生产Journey的Flink单写者必须一起接线，尚不开放Control激活。
- 已异步询问版本绑定的业务粒度，推荐沿用订单/参与编号；暂无答复，该信息不阻止g2协议实现。真实会员映射和外部机器合同仍未具备。

### S11g2 实施检查点
- 已实现完整激活授权类型/独立用途签名/内外及原成员信任复验；首轮因合同模块无Jackson生产依赖编译失败（s11g2-contracts-first.log）。改为显式稳定动作字符串+枚举校验，生产合同仍无JSON框架依赖；仅测试使用仓库已管理Jackson版本验证JSON边界。
- 第二轮34项合同用例PASS，其中新增7项签名/范围/用途/租户/时间/轮换与成员密钥撤销场景（s11g2-contracts-second.log）。补充缺shopId与未来成员激活时间拒绝后，待最终合同/架构/Hygiene验证；仍无应用/签发入口。

### S11g2 最终本地验证
- 最终34项合同测试（新增激活签名7项）和13份JSON PASS，架构10项PASS：s11g2-contracts-final.log、s11g2-architecture-final.log。中文/URL形式的真实归属和主体ID可保留，通配组织/店铺与缺字段拒绝；不引入生产JSON依赖。
- 根代理单独复核独立签名用途、完整嵌套规范字节、所有范围字段、原成员密钥撤销、授权时间/成员activationAt、序号与动作code；无本片剩余阻断。应用序号CAS、旧参与保留和实时停用仍属后续，不拿验签通过当发布完成。
- S11g1 77c1d38精确CI44110842403全部SUCCESS，待本轮提交后正常FF合入main。当前无测试进程或未知共享操作。

- 完整暂存Hygiene/diff PASS（s11g2-hygiene-final2.json）；初次工具的依赖允许项用了group:artifact，而工具仅匹配artifactId，已按已选定test依赖的准确ID修正，首轮报告保留。1分钟时间偏差已经是命名协议常量，静态工具仍报数字ADVISORY，已人工核对；无formatter限制保持。

## S11g3a Decision授权持久接收（实施中）
- S11g2提交dde7c6c已推，精确CI44112359075全部SUCCESS；S11g1已正常FF合入并推送main=77c1d38。
- 当前补签名整体授权的持久接收、单调序号和重启读取，具体API/锁序/验收见增量设计末节；接收状态明确RECORDED，不改变旧线上指针，不声称APPLIED。此片与待答的参与业务粒度无关，可以持续推进。

- g3a首轮编译PASS（s11g3a-compile.log）；两张带完整中文注释/复合外键的V4表、严格1MiB接口、head首个SQL锁定及原文读取已实现。8项真实MySQL/HTTP测试正在运行（session28071，s11g3a-first.log），不连接共享库。暂不声明验证通过。

- g3a首轮8项真实HTTP/MySQL用例PASS（s11g3a-first.log）；根代理复核发现新表必须明确二进制且NO PAD排序规则，避免大小写不同的签名活动键被数据库合并。V4改用utf8mb4_0900_bin，补大小写活动并发/未知大小写404与存储时间损坏拒绝用例，继续最终模块回归。

### S11g3a 最终验证检查点
- 全量Decision模块28项PASS，包含新增8项接收/重启/并发/回滚/边界以及旧20项回归（s11g3a-module-final.log）；合同34项/13份JSON及架构10项PASS（s11g3a-contracts-final.log、s11g3a-architecture-final.log）。
- 根代理另行复核首个SQL锁定、不同活动无空索引范围锁、完整复合外键、大小写/NO PAD、双层签名与原制品复验、事务失败原序号不动、RECORDED与线上语义分离；无本片剩余阻断。收敛重复权限/runtime协议常量后仅复跑受影响8项，不重复全部模块；然后完整暂存Hygiene与交付。

- 常量收敛后的8项受影响真实HTTP/MySQL测试再次PASS（s11g3a-final.log）。当前无未结束测试，完整模块28项其余证据有效。Git忽略项为既有构建/测试产物及本机恢复文件，保留不清理；没有新worktree、部署或共享迁移。

- 完整暂存Hygiene/diff PASS（s11g3a-hygiene-final.json）；事务需人工复核项已由上述锁序/回滚/并发证据覆盖，只有无canonical formatter限制。准备提交及精确CI，S11g2精确CI44112359075已全部SUCCESS。

## S11g3b Journey授权持久接收（实施中）
- g3a提交9930648已推送，精确CI44114923717运行中；g2 dde7c6c已通过CI44112359075且已正常FF合入并推送main。
- 继续Journey本库接收投影与核验复用，具体边界见增量设计末节。只保存RECORDED事实，不改Flink/在线状态，仍不依赖待答的参与粒度。

- g3b代码/Mapper/V3、严格接口已写，复用原Journey包内持久核验且未生成ACK或线上指令；编译PASS（s11g3b-compile.log）。真实模块测试session37569正在执行，未连接共享数据库。

### S11g3b 本地验证检查点
- 完整Journey模块17项（新增9项+旧8项）PASS，相关依赖测试一并通过（s11g3b-module-first.log）。覆盖旧active指针不变、上下文重建恢复、并发同键/冲突/不同大小写活动、未知旧序号、缺制品/损坏定义、越权/有界解析、事务失败回滚、复合外键和损坏原文/时间拒绝。
- 根代理复核Journey抽取方法仍为包内能力、两个公开入口分别保留原权限/签名校验；未重新签发ACK、未触及Flink或业务状态。继续合同/架构/Hygiene检查；无共享操作。

- g3b合同34项/13份JSON、架构10项PASS（s11g3b-contracts-final.log、s11g3b-architecture-final.log）。完整暂存Hygiene/diff随后执行；g3a远程CI44114923717前端/部署PASS、后端仍运行，暂不重复触发以免取消有效的在途验证。

- g3b完整暂存Hygiene/diff PASS（s11g3b-hygiene-final.json），1MiB输入和10秒事务是已定义协议预算，事务人工复核与真实失败/并发测试已覆盖；保留无formatter限制。g3a精确CI44114923717现已三项全部SUCCESS，可以正常交付。

## S11g合同复核修正：租户JSON形状
- g3b提交420d84d已推，精确CI44116867989运行中；g3a 9930648已正常FF合入并推送main。
- 在下一步参与证明设计前发现：ReleaseBundleManifest的tenantId实际序列化为`{value:...}`，OpenAPI却写成string。新增真实Jackson序列化对照测试先复现失败（s11g-tenant-wire-first.log），现按原运行格式修正机器合同为共享TenantId对象，不改变API数据或签名字节。当前仅合同/测试/文档修正，待最终验证；没有开启g4业务绑定实现。

- 同一复核发现原ReleaseManifest与ActivationDirective的JSON schema也把同一个TenantId值对象误标为string，已一起按实际类型修正，并加入相同序列化对照验收；只有机器文档变化，无产品字节/行为变化，不扩展到无关事件类型。

- 租户形状修正最终35项合同测试（机器规格由8增至9项）及13份JSON PASS：s11g-tenant-wire-final2.log。只改schema/测试/说明，既有运行源码未变；签名与真实HTTP证据继续有效。

## S11g4a 参与版本凭证（设计后实施）
- 恢复核对128e768提交及推送已成功，不重复执行。下一片定义独立用途的参与绑定凭证及接收校验，固定可信参与编号/主体和完整激活来源，允许旧参与在原清单到期后继续核验历史事实；不赋予重复副作用、跳过实时门禁或自动激活的权限。具体范围、验收和后续持久绑定依赖见增量设计。

- g3b精确CI44116867989三项全部SUCCESS。128e768精确CI44118927860已启动，避免重复dispatch。g4a首轮在测试代码弃用的Jackson asText方法触发Werror，已改用项目asString（s11g4a-first.log）；继续测试，未放宽编译门禁。复核另补非法Unicode代理字符拒绝，避免UTF-8替换造成主体摘要碰撞。

### S11g4a 本地验证
- 45项合同测试及13份JSON PASS（s11g4a-contracts-final.log），新增9项参与凭证和1项schema/record对照；架构10项PASS（s11g4a-architecture-final.log）。覆盖逐字段篡改、双角色信任、全部可信上下文、授权来源替换、原成员密钥撤销、过期后历史恢复、时间纳秒边界、大小写/空格/Unicode及有界严格解析。
- 根代理另行复核摘要规范、签名用途、源授权精确绑定、当前撤销和历史期限分离；本片无剩余阻断。没有签发/绑定HTTP或数据库改动，真正准入/幂等/执行门禁未声称完成。继续完整暂存Hygiene与Git交付，再推进运行端slot保护。

- g4a完整暂存Hygiene/diff通过（s11g4a-hygiene.json），数字提示均为已命名协议边界，人工复核通过；唯一工具限制为无canonical formatter。当前精确CI44118927860仍运行，本片提交后先交付已通过CI的g3b，不取消在途验证。

## S11g4b 运行端slot保护（实施中）
- g4a提交1ad2993已推送；g3b 420d84d已按成功CI44116867989正常FF合入并推送main。128e768精确CI44118927860仍运行；g4a待它结束后dispatch。
- 继续为Decision/Journey旧单清单写入增加同slot持久标记与回填，与组合接收同事务提交，完整设计见增量文档。保持原物理slot主键比较语义，避免独立锁表与旧slot排序规则不一致导致别名绕过；尚不开放整体生效入口。

- g4b编译PASS（s11g4b-compile.log）。两个运行端标记/门禁、HTTP与Coordinator和旧RR快照/失败回滚用例已写，测试session84547（s11g4b-first.log）执行中。V5/V4旧库回填测试已补，下一步隔离运行；无共享数据库写入。

- g4b首轮Decision11项+Journey12项真实HTTP/MySQL用例PASS（s11g4b-first.log），均含旧读快照后标记、HTTP/Coordinator绕过拒绝、失败回滚与重启。测试容器停止后后台同步线程报连接已关闭，以及故障注入SQL报错均有明确关闭/注入上下文，不是用例失败。开始完整模块回归与两个真实升级用例，测试环境仍隔离。
- 128e768精确CI44118927860三项全部SUCCESS；g4a 1ad2993精确CI44120438060已启动。当前main/origin=420d84d，未切换脏工作树或取消在途验证。

- 两个迁移用例和完整模块业务用例均通过：Decision32、Journey21（s11g4b-modules-final.log）。发现Decision测试JVM退出超时，线程dump定位到专属测试MySQL先停止、Spring定时任务仍访问连接池。调整本片两类测试的清理顺序为先关闭应用上下文、再停容器；保留失败/线程dump证据，待受影响测试复验，不把退出超时当生产优雅停机通过。

- 清理顺序首轮修正失败：直接关闭仍在Spring缓存中的上下文导致afterTestClass再次访问时LifecycleProcessor错误（s11g4b-lifecycle-final.log），业务11项已执行但类级清理失败，不记为通过。改用Spring DirtiesContext正常移除并关闭上下文，再由低顺序TestExecutionListener停止专属数据库；继续受影响两类复验。

### S11g4b 最终本地验证
- Spring标准上下文关闭后释放MySQL的修正复验PASS：Decision11+Journey12，正常BUILD SUCCESS且无Surefire退出超时（s11g4b-lifecycle-final2.log）。完整模块32+21与两个升级用例的其余证据仍适用；产品源码未再变。
- 最终45合同/13JSON、10架构PASS（s11g4b-contracts-final.log、s11g4b-architecture-final.log）。根代理另行复核接收head→slot与旧激活slot锁序、原子upsert保留旧指针、旧快照防绕过、回填零代际语义、事务回滚、HTTP/Coordinator覆盖及旧版本退出要求；当前片无剩余阻断。Flink与实际绑定路由仍属后续，未宣称部署或线上原子生效。

- g4b完整暂存Hygiene/diff PASS（s11g4b-hygiene.json，无新增Finding，仅无formatter工具限制）。没有新worktree；忽略项为原构建/测试产物与本机恢复文件，保留不清理。准备逻辑提交；128e768精确CI已PASS可正常合并，g4a精确CI44120438060后端仍运行。

## S11g5a Flink签名参与执行（设计后实施）
- g4b提交bca69ce已推送；g4a 1ad2993精确CI44120438060三项全部SUCCESS。g4b精确CI已dispatch（运行ID待读取session96830），同一命令正在正常交付g4a，先查结果不重试。
- 下一片先落实Flink真实执行边界和EventGateway传播，保留旧持久状态类型，新增独立字符串凭证状态及终态/过期墓碑；具体方案、兼容风险与验收见增量设计。尚未改产品代码，真实savepoint恢复、唯一准入绑定和整体激活仍未完成。

- g4b精确CI44123107153已启动；g4a已按成功CI正常FF合入并推送main=1ad2993。当前HEAD=bca69ce。g5a输入/派生键/只读精确授权解析、独立Flink凭证状态和状态写失败传播正在实现，尚未编译/验证；原JourneyJobState及PlanReference未修改。首个编辑脚本因Python缩进错误未执行，已改用明确补丁，不存在未知迁移或业务写结果。

- g5a当前产品编译PASS（s11g5a-compile.log），测试和事件schema尚未完成；新增源授权单独读取，先检查输入reference是否属于该授权，再查精确制品，避免把伪造reference误判为可重试数据库缺数据。新凭证不进入旧持久PlanReference，旧JSON不输出null新字段；实际兼容/恢复证据待测试。

- g5a首轮真实DB测试5项均在测试seed阶段失败：夹具误用installed_at，真实Journey列为warmed_at（s11g5a-first.log）；已按实际迁移修正，生产查询未改。旧Reducer/Recovery用例已通过，新增独立状态/墓碑及故障传播测试待首次运行，不能将编译PASS当功能通过。

- g5a第二轮在Flink测试Context泛型Mockito替身触发unchecked/Werror，已仅对两个测试helper按既有RecoveryTest方式标注受控转换；未放宽全局编译门禁（s11g5a-second.log）。第三轮执行完整Journey job及EventGateway模块，包含新增真实DB、状态失败/墓碑和转发用例。

- g5a完整模块首轮PASS：Journey job19项（5真实MySQL、7独立状态行为、7旧回归）+EventGateway10项（3转发、7原真实集成），合同46项依赖测试也通过（s11g5a-modules-third.log）。复核补齐源/清单原文为null时按存储损坏恢复，以及真实Flink serializer snapshot往返验收，需重跑受影响12+1项，不复跑未变网关。g4b精确CI44123107153三项全部SUCCESS，待当前切片提交后正常交付。

### S11g5a 验证检查点
- 新增真实Flink serializer snapshot写入/读取/compatible-as-is/反序列化用例PASS，13项受影响真实DB与状态测试再次通过；合同46项、14份JSON、架构10项及Flink shaded JAR打包均PASS（s11g5a-final2.log、s11g5a-contracts-final.log、s11g5a-architecture-final.log、s11g5a-package-final.log）。这只证明当前保留类型的真实序列化往返，不冒充运行中生产savepoint恢复。
- 根代理复核补充：派生enrollment键包含完整参与作用域防跨活动/环境碰撞；空签名的已保存状态按存储损坏恢复，非START凭证错误复用统一目录。后两项是最后受影响代码收敛，继续窄验证后再交付，未重跑无关全仓检查。g5a总有效范围为Journey job20项、EventGateway10项，外加合同/架构。

### S11g5a 最终复核
- 最后11项状态/网关守卫复验通过（s11g5a-guards-final.log）；审查发现JSON字面量null可绕过解析catch并阻塞消费，已收回坏消息边界，9项状态测试再次通过（s11g5a-null-input-final.log），最终JAR重打包PASS（s11g5a-package-final3.log）。有效总数为Journey job21、EventGateway10、合同46/14JSON及架构10。
- 根代理另行复核签名用途/信任角色、租户和成员查询、固定参与派生键、缓存作用域、当前kill、状态故障传播、重复START及墓碑、旧状态类型和事件兼容。当前片无剩余阻断；真实savepoint、混合版本能力门禁、唯一准入绑定和整体切换保持未完成，未把源码支持当上线成功。
- 最终Hygiene/diff PASS（s11g5a-hygiene-final.json）；两个ADVISORY为已有语义名称的缓存上限与1MiB载荷上限，人工归类为常量，无阻断；仓库无canonical formatter。首次门禁发现重复START裸值比较，已收敛到既有非START分支并3项网关转发复验PASS（s11g5a-routing-final.log）。准备逻辑提交和精确CI，g4b已通过CI可正常FF main。

## S11g5b Decision签名参与消费
- g5a提交d6c29c2、精确CI44127908234在途；g4b已正常FF main/origin=bca69ce。下一片按设计增加既有Decision请求的可选参与上下文、精确历史读取及成功重放当前校验，不签发新参与、不以最高接收序号作为active。设计完成，产品尚未修改。
- g5b产品已实现并编译PASS（s11g5b-compile.log）；可选请求上下文、独立信任、精确历史解码和两种存储重放检查已写。6项隔离MySQL/Redis/HTTP用例在运行（session69387，s11g5b-first.log），结果待核对；OpenAPI和操作说明已同步。尚未做全模块/合同/架构/Hygiene，不记为完成。
- g5b首轮6项中5项通过，1项因测试误写旧系统停用错误码失败（实际MARKETING_KILL_SWITCH_ENABLED，s11g5b-first.log）；已核对原目录修正断言。加强同key换凭证用例，保持requestId等其他字段相同，确保确实验证绑定摘要，而非其他字段冲突。继续复验，不改产品错误契约。
- 兼容性审查发现旧版Spring/Jackson可能忽略未知字段，因此补上组合专用URL（旧服务404）并让新服务的旧URL拒绝组合上下文，禁止调用者降级。已同步增量设计、OpenAPI和HTTP边界用例；在途第二轮针对改前版本的结果仅作中间证据，需验证最终入口。
- 第二轮6项真实MySQL/Redis用例通过（s11g5b-second.log）；最终全模块首次被合同路径精确清单拦下，原因是新增两个专用URL尚未登记API_PATHS，已同步且保持精确集合断言（s11g5b-modules-final.log）。网关已有/api/v1/decisions/**覆盖新URL，继续核对限流/权限并重跑最终模块。

### S11g5b 本地验收通过
- 完整Decision模块38项、合同47项及依赖回归PASS（s11g5b-modules-final2.log），14JSON和10架构PASS（s11g5b-json-final.log、s11g5b-architecture-final.log）。6项新增隔离MySQL/Redis/HTTP测试覆盖并发重放、最新接收不覆盖旧参与、换凭证冲突、停用/信任撤销重放拒绝、历史期限与当前优惠期限分离、持久源/制品损坏不回退、旧JSON摘要及专用入口边界。
- 根代理另行复核当前权限/来源组织店铺、两种幂等路径、作用域查询、原制品签名、限流/熔断路由、旧服务忽略未知字段风险与专用URL保护；无剩余阻断。Hygiene/diff PASS（s11g5b-hygiene.json，无新增Finding；无canonical formatter限制）。没有改OfferToken、共享库或部署；实际准入/应用/能力门禁仍待后续。
- g5a d6c29c2精确CI44127908234三项全部SUCCESS，待本片提交后正常FF main。当前g5b准备逻辑提交和精确CI，继续S11g6持久化唯一准入与实际应用闭环，整体仍PARTIAL。

## S11g6a 唯一参与准入
- g5b提交631dce2、CI44130020254已启动；g5a已FF main/origin=d6c29c2。g6a设计细化真实应用投影与RECORDED分离、共享指针锁/唯一业务键、独立签发与内部机器入口；本片没有应用写入口，不能把测试seed当真实整体发布。产品尚未修改，继续实施。
- g6a V6三表/完整外键与注释、Mapper XML共享锁/唯一插入、不可变签名事实恢复、严格8KiB内部接口和可选专用私钥已写。首轮编译PASS（s11g6a-compile.log）；复核补上应用时间不得晚于首次准入、不得早于授权或超出原有效期，尚未验证。当前待OpenAPI、真实MySQL并发/切换/失败恢复及签发关闭测试，没有任何实际应用指针写入口或共享数据变更。
- g6a首轮7项隔离MySQL真实准入测试全PASS（s11g6a-first.log），覆盖并发唯一、跨主体冲突、不同参与共享锁、切换等待、插入后故障回滚、缺私钥恢复/撤信任拒绝、严格输入与大小写/尾空格/Unicode键、源时间损坏。复核把历史资源范围检查前置于主体冲突响应，避免越权探测；已补断言，待最终模块回归。OpenAPI/操作说明/契约入参测试已写；g5b精确CI44130020254三项SUCCESS，待清洁提交后正常FF main。

### S11g6a 本地验收通过
- 完整Decision模块45项、合同49项及依赖回归PASS（s11g6a-modules-final.log），14JSON和10架构PASS（s11g6a-json-final.log、s11g6a-architecture-final.log）。补充不完整签发配置与不被当前参与公钥信任的私钥拒绝后，7项准入真实MySQL测试再次PASS（s11g6a-signing-final.log）；原模块其他结果仍适用。
- 根代理单独复核READ_COMMITTED共享指针锁、实际切换等待、同键唯一仲裁/失败后读回、MyBatis写入清理会话缓存、提交前异常回滚、无远程/MQ事务副作用、先检查历史资源范围再报告主体冲突、时间/签名/Unicode边界、无私钥读取恢复以及空APPLIED默认拒绝。Hygiene/diff PASS（s11g6a-hygiene-final.json）；数值项为显式容量/事务预算常量，事务ADVISORY已有真实并发与回滚证据，无剩余阻断；仍无canonical formatter。
- 本片没有实际应用写入口，V6不回填任何APPLIED记录；未迁移共享数据库或部署。准备逻辑提交/精确CI，随后正常交付已绿g5b；下一步g6b真实执行能力证据与应用协议，不能将测试APPLIED夹具当作线上原子发布完成。

## S11g6b1 Journey实际执行证据
- g6a提交ad48a2a，精确CI44132761406已启动；g5b已正常FF main/origin=631dce2。下一片先定义签名执行探针/回执及可信目标验证，区分真实Flink与Web安装ACK；设计已写，尚未实施合同。实际探测、可靠收集和应用指针仍待后续，不改正在运行的作业。
- g6b1共享探针/ACK/独立签名/可信部署验证器已写并编译PASS（s11g6b1-compile.log）。首轮合同7项中4项通过：两处断言误用IllegalArgumentException（Jackson实际包装ValueInstantiationException），一处篡改成员夹具未同步组合contentHash，被构造器提前拒绝；均已按真实契约修正（s11g6b1-first.log）。新增两份JSON schema并使用与目标$id一致的绝对引用，补全字段/引用一致性测试；最终验证待跑。
- g6b1最终合同57项PASS（s11g6b1-final.log），含7项执行证明行为与1项schema字段/规范$id引用检查；已有16份JSON，架构验证在执行（日志s11g6b1-architecture-final.log，恢复先查进程/日志）。g6a精确CI44132761406三项SUCCESS，待本片清洁提交后正常FF main。

### S11g6b1 本地验收通过
- 合同57项、16份JSON、10项架构检查全部PASS（s11g6b1-final.log、s11g6b1-json-final.log、s11g6b1-architecture-final.log）。根代理另行复核独立签名用途、全部字段覆盖、可信部署来源、完整源链、时限与实际子任务下标约束；单份回执不等于整个作业就绪。
- Hygiene/diff通过（s11g6b1-hygiene.json）；5条ADVISORY均为已命名协议/容量/时钟常量，无阻断，仓库未配置canonical formatter。本片无真实probe投递、无APPLIED写入口、无共享库或运行作业变更。准备提交推送及精确CI，随后交付已绿g6a；下一片g6b2接实际Flink分支。

## S11g6b2 真实Flink探针处理
- g6b1 f1cf177已提交推送，CI44135347731在途；g6a ad48a2a已正常FF main并推送。g6b2设计记录事件封套、真实运行身份和JAR摘要、无旅程状态副作用、只读失败重试边界与有界摘要缓存；尚未实现。
- g6b2输入/输出封套、实际作业身份/专用签名、提交JAR摘要、无状态探针分支及复用本地读端校验已写，首轮编译待核对（s11g6b2-compile.log）。尚未功能验证或发出任何真实探针；下一步真实DB与处理函数测试、schema和操作文档。
- 首轮产品编译PASS；测试编译受Mockito泛型Context替身unchecked/Werror阻断，仅对该测试helper局部标注受控转换，未放宽全局门禁（s11g6b2-first.log）。二轮真实DB/处理函数/旧状态/合同验证运行中，日志s11g6b2-second.log、session96590；两份封套schema/AsyncAPI及操作说明已补。

### S11g6b2 验证检查点
- 完整Journey job31项、合同58项及依赖回归PASS（s11g6b2-modules-final.log），18JSON和10架构PASS；shaded JAR打包与从该真实归档加载入口计算摘要PASS（s11g6b2-package-final.log、s11g6b2-jar-identity.log）。没有连接或改变运行中Flink。
- 根代理另行复核完整探针摘要缓存、时间/当前kill、严格类型路由、missing/浮点nonce拒绝、专用密钥与job graph序列化、源/制品/编译签名、状态零副作用、成功与失败输出故障传播，以及旧生产者/消费者启用门禁。Hygiene提示两处制品启动检查catch过宽，已缩窄到IO/URI/算法/权限异常；继续受影响7项验证并重打最终JAR，不重复未受影响DB回归。g6b1 f1cf177精确CI44135347731三项SUCCESS，待本片清洁提交后正常FF main。
- 最终7项处理函数/制品测试、重打JAR与真实归档摘要核对PASS（s11g6b2-artifact-final.log、s11g6b2-jar-identity-final.log），其余31项模块范围/58合同/10架构证据保持适用。最终Hygiene/diff PASS（s11g6b2-hygiene-final.json），仅已命名路由容量常量ADVISORY及无formatter限制。无剩余当前片阻断；准备逻辑提交/精确CI，继续g6b3可靠探测与收集。未新增worktree，原忽略构建/测试/恢复文件保留，无清理。

## S11g6b3a 持久探针与回执收集
- g6b2 86ffe42已提交推送/dispatch 44137055316；g6b1已正常FF main并推送f1cf177。下一片设计见增量文档：可信部署配置、持久挑战和唯一子任务集合、严格机器入口与原输出consumer分流。暂不投递probe、不写APPLIED，当前未实现。
- g6b3a V5两表/完整作用域外键/注释、可信部署配置、登记/查询/子任务去重收集与既有consumer分流已写，首轮编译结果待核对（s11g6b3a-compile.log）。复核将普通业务消息类型分流移出收集事务，避免给每条旧输出额外增加数据库事务；尚未功能验证，继续真实DB/HTTP/并发和合同。
- g6b3a产品编译PASS；首轮8项集成测试均在BeforeEach失败：测试直接访问Spring代理对象上的故障字段，实际字段位于目标对象。已改为代理可调用的故障控制方法（s11g6b3a-first.log），不修改产品事务或放宽断言；继续二轮验证。
- 二轮真实DB/HTTP 8项中7项PASS，唯一失败是未知探针预期写成409，实际正式合同为404 NotFound；已修正断言保留拒绝行为（s11g6b3a-second.log）。补充真实consumer分流（旧输出转原projector、新ACK入真实库）及启用目标缺Control信任时启动拒绝，继续完整模块验证。

### S11g6b3a 本地验证检查点
- 完整Journey服务29项、合同59项及依赖回归PASS（s11g6b3a-modules-final.log），包含新8项真实MySQL/HTTP并发/回滚、原授权/安装/状态投影和迁移保护。补充真实TenantContextFilter作用域下重建实例查询、当前撤钥/部署变化时拒绝旧READY，窄8项复验在执行（session50481，s11g6b3a-reconstruction-final.log）。18份JSON已校验。
- 复核确认父行锁+READ_COMMITTED/唯一子键、无网络事务、完整租户与运行位置、当前身份与原组织/店铺、原挑战等价校验、第一份子任务ACK不被重复消息覆盖、历史EXPIRED不恢复资格、普通旧输出不新增数据库事务。尚待架构/Hygiene最终门禁及精确CI，不把注册/API可用当可靠投递或实际发布。
- 最终重建查询/撤钥/换部署8项复验PASS（s11g6b3a-reconstruction-final.log），架构10项PASS（s11g6b3a-architecture-final.log）。Hygiene/diff PASS（s11g6b3a-hygiene.json）；事务ADVISORY已以真实并发、回滚及无远程副作用复核，16KiB为已命名协议容量常量，ProbeState与裂变State同名值分别属于执行证据和业务资格，不能跨域合并。无canonical formatter为现有工具限制。
- g6b2 86ffe42精确CI44137055316三项SUCCESS，待本片清洁提交后正常FF main；g6b3a准备提交/精确CI。无运行测试进程，无新增worktree，忽略构建/测试/本机恢复文件保留不清理；未部署、发送probe、迁移共享库或触动原3个Flink作业。继续g6b3b有界可靠投递及恢复。

## S11g6b3b 可靠投递与恢复
- g6b3a bd36e29提交推送/CI44139430044在途；g6b2正常FF main/origin=86ffe42。下一片新增显式派发入口及有界持久投递任务/租约/CAS恢复，原登记API继续不自动发送；设计已写，尚未产品实现。默认关闭，无真实探针或共享环境变更。
- g6b3b已开始V6持久投递表与显式投递状态/Repository合同，尚未Mapper/应用/发送器/API或测试；未迁移数据库。已核对当前dev_infra实际Kafka镜像apache/kafka:3.8.0，隔离测试将沿用该版本和现有Testcontainers BOM，不升级平台。官方模块说明确认org.testcontainers.kafka.KafkaContainer支持apache/kafka；详见https://java.testcontainers.org/modules/kafka/。Spring Kafka4.0.2本地API已核对独立ProducerFactory配置复制与有界关闭能力。
- g6b3b持久层Mapper/XML及适配器已写：完整作用域、SKIP LOCKED单任务领取、租约代次/有效期CAS提交、有界过期退休；仍未应用服务/API/发送器或测试。当前仅代码文件，无真实迁移或投递。
- g6b3b持久层编译PASS（s11g6b3b-persistence-compile.log）；新增真实MySQL/SKIP LOCKED、并发租约、旧代次拒写、提交后故障回滚、重建cursor及过期退休用例正在执行（session89555，s11g6b3b-persistence-first.log）。尚无应用/API/真实Kafka发送器，不把持久层测试当可靠投递完成。
- g6b3b首轮租约测试在编译阶段因JUnit assertEquals与TransactionTemplate泛型返回值重载歧义失败（s11g6b3b-persistence-first.log）；已显式Integer类型并解箱断言，不改产品SQL。复验日志s11g6b3b-persistence-second.log，结果待核对。
- g6b3b真实MySQL持久层用例已完成复验，日志s11g6b3b-persistence-second.log；恢复先核对最终状态，再实现派发应用/有界发送器/worker及机器合同。租约测试不涉及实际Kafka或现有Flink作业。
- g6b3b二轮真实并发测试复现领取缺陷：原候选排序发生filesort，首个事务在LIMIT前锁住多行，第二个SKIP LOCKED事务无法获取另一任务（s11g6b3b-persistence-second.log）。已增加带中文注释的派生pending列及slot/pending/next_at/created_at有序索引，领取查询明确使用该索引，避免多状态过滤破坏排序；待第三轮真实双事务复验。
- g6b3b有序索引修正后，第三轮真实双事务领取/旧代次拒写/回滚/重建cursor/有界过期用例PASS（s11g6b3b-persistence-third.log）。补充对实际Mapper绑定SQL的EXPLAIN断言，固定无filesort的领取执行计划；该新增断言待窄复验。g6b3a bd36e29精确CI44139430044三项SUCCESS，正常快进交付main进行中，原脏工作树保留；先核对Git命令结果，不重复执行未知操作。

- 恢复核验：g6b3b持久层第三轮及实际Mapper SQL EXPLAIN复验PASS（s11g6b3b-persistence-third.log / persistence-plan-final.log）；无测试或Git未知结果。应用层显式派发/查询、短事务租约与CAS、当前证据复核、批次恢复和有界失败已写，正在编译；Kafka适配/生命周期/新合同与全链路验证仍未完成。
- 应用层与独立Kafka发送器test-compile PASS（s11g6b3b-application-compile.log / kafka-compile.log）。新入口/六状态合同、有界单线程relay/停机/指标观察及默认关闭配置已写；隔离apache/kafka:3.8.0与真实MySQL测试正在执行（session49978，s11g6b3b-integration-first.log），包含真实发送/实际KafkaListener收集、未知发送与提交失败恢复、派发权限/唯一性/终态不重置。测试ACK由测试密钥产生，不冒充真实Flink集群。
- 集成首轮停在测试编译：FaultDeliveryRepository缺少父挑战Key显式导入，已修正；产品编译保持通过，尚无本轮功能PASS。继续第二轮真实组件验证，保留首轮失败日志。
- 第二轮13项真实MySQL/Kafka集成全部PASS（s11g6b3b-integration-second.log），含实际KafkaListener重复ACK收集、未知发送/提交回滚后的原nonce恢复与唯一派发。追加隔离broker暂停/恢复预算和独立relay故障后继续/停机/线程不泄漏验证，正在跑完整Journey服务+合同依赖（session8246，s11g6b3b-modules-final.log）。同步运行手册，明确SENT与COVERED都不是APPLIED。
- 完整模块首轮合同60项通过；服务新relay测试编译遇到SmartLifecycle.stop重载与submit方法引用歧义，改为明确void lambda，未改变产品行为或断言。继续完整服务验证，首轮日志保留。
- 完整模块执行中，新增14项真实集成（含隔离broker暂停恢复）与1项relay生命周期验证已PASS，原服务回归继续。Hygiene首轮提示缺UNIT_TEST结论和testcontainers-kafka依赖审批元数据；前者待整模块结束，后者属于已批准切片的真实Kafka隔离验证，test-scope沿用平台2.0.3、不升级/新增生产组件，以该既有授权登记approved-dependency后复核，无需用户重复确认。

### S11g6b3b 验证与交付检查点
- 当前完整Journey服务36项、合同60项及依赖回归全部PASS（s11g6b3b-modules-final2.log）；架构10项PASS（s11g6b3b-architecture-final.log）。新增14项真实MySQL/Kafka测试包含：双事务SKIP LOCKED及实际SQL无filesort、租约接管/旧写拒绝、入队/进度回滚、重建游标、真实发送与实际KafkaListener收集、未知确认重复nonce、暂停broker有界等待/恢复、失败预算与终态不重置；独立relay故障后继续和停机线程释放1项PASS。
- 根代理另行复核：完整租户/位置/原组织店铺权限、原登记不投递、受控topic、三段短事务锁序与事务外发送、当前签名/目标/制品复核、原期限、代次CAS和未知提交恢复、低基数指标/独立producer/单周期线程/有界停机；未发现当前片剩余阻断。旧服务默认关闭路径及原安装/来源/投影/迁移回归通过。真实Flink checkpoint、生产savepoint、混合版本/容量仍UNVERIFIED，测试签名不能替代真实集群证据。
- Hygiene最终无阻断（s11g6b3b-hygiene-final.json）：事务ADVISORY已由并发/回滚/锁序和发送无事务断言核验；三个数值为已命名预算常量；投递State与裂变State属不同领域，不合并。testcontainers-kafka已按授权的隔离验证设计登记，版本沿用2.0.3；无canonical formatter为现有工具限制。diff检查PASS，26个相关文件已核对；无用户改动或新增worktree，忽略构建/测试/本机配置均保留，不清理。
- 本片准备逻辑提交及精确CI；尚未部署、迁移共享库、发送真实运行probe或改变原3个Flink作业。之后继续实际Flink/Kafka checkpoint链路验证、Decision执行证据及g6b4应用门禁，再接Control整体闭环；S06–S13总目标仍PARTIAL。

## S11g6b3c 真实 Flink checkpoint 回执验证

- 已复用任务分支 feat/activity-release-bundles，原工作区干净，无待恢复的 Git 操作。g6b3b 既有本地证据继续有效。
- 验收顺序为隔离库/消息系统启动、提交当前 Journey JAR、实际派发和读取未提交回执、手动触发 checkpoint、读取持久子任务覆盖。测试使用临时密钥和合成活动。
- Docker Desktop 当前停止。优先使用已授权的任务分支 CI 验证，保留共享容器现场。S06–S13 仍部分完成。
- 真实 checkpoint 用例及任务分支 CI 入口已写，本机隔离 test-compile 通过（s11g6b3c-compile.log）。验收使用本次提交 JAR 的 SHA-256、Flink CLI 返回的 jobId 和生产 HTTP/relay/consumer 链路。Docker 实跑结果待核实。
- 处理函数回归复现 Flink 回执的三处日期时间使用数字格式，与事件 schema 不符（s11g6b3c-wire-first.log）。已单独修复该事件输出，探针、状态恢复及作业图共 11 项验证通过并重新打包（s11g6b3c-wire-final.log）。实际 checkpoint 验收在独立 CI 作业执行。
- 任务分支 0be4ffc（含时间编码修复 241152f）的 GitHub 托管隔离验收实际执行 1 项、0 失败/跳过。作业 137542590883 用时 53 秒，触发前 PENDING/0，触发后 READY/2，业务激活和派发表均空。私有日志 s11g6b3c-checkpoint-first.log。共享环境未启动。
- 二轮在未提交读已看到的回执位点上增加 broker READ_COMMITTED 末端检查，同时把首次周期 checkpoint 的最短延迟固定在验收窗口之外。二轮 test-compile 通过，远端实跑及其余 CI 状态以该 revision 的 Actions 记录为准。
- 下一项 Decision 执行证据需覆盖接收业务流量的实例。现有 Helm 从 metadata.name 和镜像标签配置运行身份，Compose 使用固定名称和 local 值。用户已确认由部署流水线出具签名清单，兼容 Compose 和 Kubernetes。
- 获准启动本机 Docker Desktop 后第一次本地验收因默认地址池耗尽而停在网络创建。已支持显式独占测试子网并用无冲突的 10.254.211.0/28 复验，日志 s11g6b3c-checkpoint-local-subnet.log。共享 REST 在引擎启动后返回空作业列表，本次只读核对。
- 本机显式独占子网复验 1 项通过（s11g6b3c-checkpoint-local-subnet.log），服务模块耗时约 46 秒；c6d5f7a 的远程 checkpoint 作业 137543496937 通过。两端均验证实际回执和提交前 broker 位点。

## S11g6b3d Decision 部署流水线签名清单

- 用户选择流水线签名清单，授权决定登记工作流和位置。使用本仓库可复用工作流和 Decision V7 本库记录，支持 Compose/Kubernetes；设计与约束见 ATOMIC_RELEASE_DESIGN.md 文末。仅实现并隔离验证，不执行共享迁移或生产登记。
- 已实现清单合同/签名/机器 schema、JAR 离线签发与验签、严格解析、专用机器身份与事务登记/读取 API、条件推进和不可变历史。真实 MySQL 验证并发同序号、幂等、排空、旧序号/跳号、越权和无效输入。首轮 2 项集成、4 项清单、16 项合同测试通过（s11g6b3d-integration.log），操作 JAR 的签发和核验通过。GitHub 登记工作流与恢复脚本正在作最终验证。
- Journey c6d5f7a 精确 CI 45130097152 四项通过并正常快进推送 main；本机独立子网适配提交 e68ca2b 于任务分支，随本片交付。共享 Flink REST 仍为空列表，未恢复业务作业。
- 完整 Decision 47 项、合同 64 项及依赖回归通过（s11g6b3d-modules.log），10 项架构检查通过（s11g6b3d-final-gates.log）。增加真实 head 更新故障，验证历史插入回滚后可重试成功；最终 2 项数据库联合测试及 4 项清单测试通过并重打包（s11g6b3d-rollback.log、s11g6b3d-package-final.log）。
- 可执行 JAR 签发/验签及时间/位置/内容边界、4 项登记客户端成功/拒绝/未知结果恢复测试和 actionlint 通过。Hygiene 最终无阻断，事务已复核锁与回滚、无网络副作用，仓库未配置规范格式化工具。真实环境登记仍需受保护部署环境、实际观测和机器身份；此时 GitHub 仓库已配置 Environment 数为 0。没有实际部署或写共享数据。
- 该片仅交付部署清单登记。后续逐副本执行回执、应用门禁、Control 与控制台的验收结果见本文件“当前交付结果”。
- 常规 Compose 和 Helm 模板已将流水线租户、actor 与验签公钥仅注入 Decision，三项默认关闭。四种 Compose 模型、Helm lint/渲染、Prometheus 规则及 Python 回归通过（s11g6b3d-render.log）；实际 env 覆盖值和三份可选 Secret 引用已检查。登记客户端在失败分支释放响应，四项复验通过。
