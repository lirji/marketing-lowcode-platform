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
| S04 | 修复 CI 暴露的纳秒存储、跨仓基础设施访问与失效 Trivy 引用；对应远程工作流实际成功 | GitHub 仓库访问凭据 | DONE |
| S04c | 触达同键请求指纹、服务端实际时间频控、迟到投递结果不覆盖终态；历史无指纹待重试记录明确拒绝自动重发 | 既有contactKey契约；S04分支先合并 | DONE |
| S05 | 空白画布节点专属配置、真实活动类型/设计意图保存、搜索定位/模拟输入；合法定义能校验保存 | 现有编译器支持集合 | DONE |
| S06 | 真实人群来源及快照/segment 一致、权益目录选择、审批发布到决策 | S03/S05、上游人群合同 | IN_PROGRESS |
| S07 | 普通 AwardIntent 终态/取消恢复及 Journey 接线；自动轨迹与对账闭环；真实浏览器验收 | S06、权益终态合同 | IN_PROGRESS |
| S08 | 裂变可信身份/交易/SKU/终态合同与适配器、READY ACK 接线 | 已有外部合同核对 | TODO |
| S09 | 裂变 C 端参与/邀请/绑定及持久 Worker；复用已完成资格、配额、奖励、授权与回滚机制 | S08 | TODO |
| S10 | 裂变专用投递/取消/终态/measurement，profile/网关/镜像/备份与端到端验收 | S09 | TODO |
| S11 | DMN 编辑分析求值、组合/定时发布、完整 Journey 及可恢复迁移/重算、真实实验曝光 | 已有合同，新增语义须核对 | IN_PROGRESS |
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
S01–S05已交付，S05提交61b471b已正常快进推送main，精确候选CI43804843190全部PASS。随后S06→S13继续；先补人群快照/动态segment的身份一致性，真实会员来源仍待业务确认。Docker应用仍为旧镜像，后续按实际受影响服务重建验证，不能以源码测试替代已部署状态。

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
