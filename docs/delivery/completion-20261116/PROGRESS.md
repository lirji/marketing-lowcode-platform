# 项目补齐实施进度

## 目标与授权
- 用户于 2026-11-16 确认“按照你的建议顺序来执行”：先正确性/启动/CI，再真实运营链路、裂变和高级能力；不将完成一个切片等同于整个项目完成。
- 基线 `main/082e9fed1d90b7784780d68e91eedb2409ee090e`，原工作树干净。当前仓库 `/Users/liruijun/personal/LLM/marketing-lowcode-platform`。
- 允许当前仓库代码、测试、文档及本地验证；按用户持续授权使用任务分支、逻辑提交、正常合并推送远程 main。生产部署、共享数据清空、其他仓库修改不在授权内。
- 审计证据：`/Users/liruijun/.local/share/marketing-audits/20261116-completeness/`；Docker 启动与恢复证据：`/Users/liruijun/.local/share/marketing-deployments/20261116-start/`。
- 规范：`~/.codex/architecture-standards.md` 业务建模、持久化、接口契约、并发安全；`personal-tech-stack.md` 并发、线程池、日志、状态机；engineering-workflows/backend-implementation/continue-approved-delivery/task-git-delivery。前端、Runtime 专项随切片读取。

## 执行清单与验收

| ID | 结果及可观察验收 | 依赖 | 状态 |
|---|---|---|---|
| S01 | 触达重试复核当前同意/抑制/未成年人/个性化/静默时段/模板；终态幂等且频控不重复扣减；渠道限速、熔断冷却与单探测恢复通过回归 | 现有触达/Provider 契约 | DONE |
| S02 | DEV 身份切换清理旧查询及缓存；真实轨迹空值/失败/加载不显示演示结果，异步结果不串查询 | 当前前端 API | DONE |
| S03 | 空库可启动控制面并完成合法首发后 Decision 才 ready；Flink 校验名称/状态/检查点及恢复选择；真实 SKU 依赖验证 | 本地资源、已有跨系统合同 | DONE |
| S04 | 修复 CI 暴露的纳秒存储、跨仓基础设施访问与失效 Trivy 引用；对应远程工作流实际成功 | GitHub 仓库访问凭据 | DONE（待合main） |
| S04c | 触达同键请求指纹、服务端实际时间频控、迟到投递结果不覆盖终态；历史无指纹待重试记录明确拒绝自动重发 | 既有contactKey契约；S04分支先合并 | VERIFIED（待交付） |
| S05 | 空白画布节点专属配置、真实活动类型/设计意图保存、搜索定位/模拟输入；合法定义能校验保存 | 现有编译器支持集合 | TODO |
| S06 | 真实人群来源及快照/segment 一致、权益目录选择、审批发布到决策 | S03/S05、上游人群合同 | TODO |
| S07 | 普通 AwardIntent 终态/取消恢复及 Journey 接线；自动轨迹与对账闭环；真实浏览器验收 | S06、权益终态合同 | TODO |
| S08 | 裂变可信身份/交易/SKU/终态合同与适配器、READY ACK 接线 | 已有外部合同核对 | TODO |
| S09 | 裂变 C 端参与/邀请/绑定及持久 Worker；复用已完成资格、配额、奖励、授权与回滚机制 | S08 | TODO |
| S10 | 裂变专用投递/取消/终态/measurement，profile/网关/镜像/备份与端到端验收 | S09 | TODO |
| S11 | DMN 编辑分析求值、组合/定时发布、完整 Journey 及可恢复迁移/重算、真实实验曝光 | 已有合同，新增语义须核对 | TODO |
| S12 | 分析筛选导出、资产管理、完整分页、DEMO 数据落库、前端错误采集 | 相关后端能力 | TODO |
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

## 已验证 S04（待按依赖顺序合入 main）
- S04a `94d1334` 已合入并推送。V4 将静默时段扩容至 varchar(18)，真实 MySQL 纳秒时间新建/更新与阻断共11项测试通过；本地持久库未迁移。
- 分支 `fix/reproducible-ci-infrastructure`：`3698e1e` 与 `7b49a92` 已推送。CI 使用版本化 dev-infra 快照、临时随机凭据和诊断脱敏；本地默认仍用共享 dev-infra。R1 明确使用只读权益目录合同 fixture，未声称真实外部发奖。
- CI MinIO/mc 按已核验官方固定源码构建，基础镜像固定 digest，保留许可证；解决官方旧二进制镜像无法拉取。Trivy 固定官方 v0.36.0 提交，保持原检查强度。Gitleaks 仅忽略逐项核实的13条精确历史 fingerprint，无路径/规则宽泛排除。
- 本地18项脚本测试、actionlint、4种 Compose 模型/Helm、Python 编译、diff/Hygiene PASS（无 formatter 限制）。Gitleaks 8.24.3 扫描76个提交，零发现。
- **远程 PASS**：CI [43800797985](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43800797985) 对应3698e1e，Java/契约/架构、前端、部署及38迁移均通过；7b49a92 未改产品 Java/TS。Security [43801234881](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43801234881) 对应7b49a92，Gitleaks/Trivy/SBOM/两语言CodeQL全部通过。
- **空环境全栈 PASS**：[43801238973](https://github.com/lirji/marketing-lowcode-platform/actions/runs/43801238973) 对应7b49a92，固定源码 MinIO 构建、空库启动和 R1（f46d52db16d9）、种子后第二轮新业务ID验收（bd3f19b51174）及 teardown 成功。真实项目服务/MySQL/Redis/Kafka/Flink，外部目录为合成只读 fixture，触达为 SANDBOX、轨迹为手动写入，不代表真实外部履约或生产验收。
- 本机冗余迁移测试已停止且临时容器确认不存在，连接中断属于主动停止结果；本机 deployment 执行期间修改脚本导致截断失败，远程不可变版已通过。历史失败和当前范围保存于 `S04_REMOTE_EVIDENCE.json`、`s04-*.log`，无未知数据库操作。

## 当前 S04c 实施
- 分支 `fix/contact-request-idempotency` 基于7b49a92，先合S04再合本片。V5新增可空请求指纹，稳定排序JSON保留类型与数组顺序；同键异载荷返回既有 `IDEMPOTENCY_PAYLOAD_CONFLICT`，历史无指纹待投递返回 `CONTACT_RETRY_UNVERIFIABLE`，其他状态只读返回。
- 频控按服务端 Clock 准入时间；迟到 Provider 返回仅条件更新 PENDING/RETRY_PENDING，不能覆盖已推进回执、误写失败DLQ/事件。
- 两维独立审查发现首次并发插入竞态；修复为先争取唯一键后校验许可/扣频控，失败整体回滚。唯一键竞争失败在回滚后的新事务中读取赢家，核对身份后只读返回，不重复调用Provider。已有待投递记录仍可并发重试，依赖渠道稳定业务键去重，未宣称全局单飞。
- 最终27项（25真实MySQL集成+2回调）通过，包含频控拒绝后的记录回滚断言；契约Maven/13份JSON、Hygiene和diff检查通过（无formatter限制）。独立业务/事务复核无新增阻断。证据`s04c-final.log`、`s04c-contracts.log`、`s04c-hygiene.json`。无真实短信、无共享库迁移。

## 下一步
完成S04c最终验证与复核、逻辑提交；按S04→S04c顺序正常合入推送main。随后S05→S13连续推进，源代码准备记录见证据目录 `NEXT_S05_NOTES.md`。Docker应用仍为旧镜像，后续按实际受影响服务重建验证，不能以源码测试替代已部署状态。
