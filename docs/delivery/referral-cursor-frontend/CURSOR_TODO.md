# Cursor 前端待办：交易中心配套与完整邀请有礼

更新时间：2026-09-08。交付对象：Cursor。本文是待办和接口依赖交接，不是前端完成报告。

## 1. 从哪里开始

管理台仓库：`/Users/liruijun/personal/LLM/marketing-lowcode-platform`，前端入口 `frontend/apps/console`。现有技术栈为 React、TypeScript、Vite、React Router、TanStack Query、Zod，测试用 Vitest/Testing Library/Playwright；复用既有组件和样式，不另建管理台。

先读取交易中心根 `CODEX_PROGRESS.md`、最新任务检查点，以及本仓库可用的 AGENTS/CLAUDE/恢复文件。先看 `git status`：CampaignsPage、API client、多个设计器及测试已有用户改动；不得覆盖、重置、删除或混入别人的提交。后端继续由 Codex/Claude Code 负责，Cursor 不修改 Java、迁移、密钥或共享数据库。

C 端商城/H5 的仓库位置、路由及 BFF 尚待提供。不要把管理台管理员登录当成普通参与用户身份，不要在 console 中自行新建一套正式 C 端站点。

权威依据（旧计划的“拟新增”不等于当前已开放）：

- [原始实施清单，特别是 9.4](../../plans/referral-campaign-20260907/FINAL_PLAN.md)
- [生产设计，特别是 6.6 状态与退款规则](../../plans/referral-campaign-20260907/PRODUCTION_DESIGN.md)
- [外部合同](../../plans/referral-campaign-20260907/EXTERNAL_CONTRACTS.md)
- [当前后端进度](../../REFERRAL_IMPLEMENTATION_STATUS.md)
- [BFF 身份边界](../../REFERRAL_BFF_ASSERTION_BOUNDARY.md)
- [控制面预览已交付范围](../referral-control-preview/DELIVERY_REPORT.md)

首期交付邀请有礼：直接邀请、注册新客/权威首单、双边奖励、逐人及阶梯奖、退款失效和追回。好友助力、拼团、砍价、排行榜、多层奖励是原计划中的后续独立迭代，不展示为已支持，也不从原目标中删除。

## 2. 当前可用边界

| 能力 | 当前事实 | Cursor 的处理 |
|---|---|---|
| `REFERRAL_POLICY` 定义保存、校验、仿真、节点目录 | 控制面已有生产代码与专项；不代表联调环境已启动 | 可先接现有入口并定向联调；仿真标识 SIMULATED_INPUT，结果是候选 |
| campaignType | 设计要求 STANDARD/REFERRAL；当前 Campaign 领域对象尚无该字段 | 类型表单/路由可准备，保存和筛选依赖后端合同，不伪造服务端支持 |
| 审批、暂存、ACK、激活、回滚 | 裂变发布链未接通；存在 `REFERRAL_RELEASE_NOT_AVAILABLE` 明确拒绝 | 保留不可用说明，不靠前端开放按钮绕过 |
| 参与、邀请 token、绑定、证据 | 有内部持久化切片；不是浏览器可调用 HTTP | 等 BFF/运营查询合同，缺接口显示待接入 |
| 资格、人数、奖励与配额 | 部分规则完成，完整落库/链路仍推进 | 不在前端推导真实资格或造进度 |
| V11 奖励受理 | 专用耐久 HELD 已专项验证；后续取消复核在推进 | HELD/202/ACCEPTED 均不能显示到账 |
| 真实身份、目录、风险、渠道、KMS | 尚有默认拒绝 Port 或待确认来源 | 不以测试替身声称真实接通 |

## 3. 按阶段执行的任务单

状态说明：**可开始**可做代码与局部验证；**部分可做**先做组件/合同边界，依赖未完成时不得验收联调；**待依赖**只整理合同和验收案例。所有复选框初始均未完成，Cursor 根据实际实现及证据勾选。

### A. 现在先做：管理台合同与设计器

- [ ] **FE-01｜P0｜可开始：合同、路由及能力边界。**
  - 修改 `src/shared/api/schemas.ts`、`client.ts`、`src/shared/model/types.ts`，必要时同步 `frontend/packages/contracts/src/index.ts`；新增 REFERRAL_POLICY 的严格类型与运行时解析，不把未知返回值强制断言为成功。
  - 对照真实后端 DTO/节点 parser，统一规则输入、校验错误、仿真响应；未实现的 API 标记待接入，不把草案响应写入正式 client。
  - 修改 `src/App.tsx`、`src/app/navigation.ts`，预期路由 `/designers/referral`、`/referral-operations`；权限与既有守卫一致，深链接也受限。
  - 验收：旧方言、旧菜单、旧客户端行为保持兼容；缺能力、403、未知状态均不会变为绿色就绪。

- [ ] **FE-02｜P0｜部分可做：活动类型和设计器入口。**
  - 扩展 `features/campaign/CampaignsPage.tsx`，支持类型选择/展示/筛选，REFERRAL 进入裂变设计器，STANDARD 维持原流程。
  - 依赖 BE-01；缺省 STANDARD 的兼容口径须以后端最终合同为准。不能自行用名称/备注字段充当持久类型。
  - 验收：刷新后类型不丢失、筛选来自真实返回、活动与定义绑定准确；已有未提交修改保留。

- [ ] **FE-03｜P0｜可开始：邀请有礼规则设计器。**
  - 新增 `features/referral/ReferralDesignerPage.tsx`，复用 `components/ui.tsx`、既有 LowCodeDesigner/设计器绑定模式及 `SkuPickerField.tsx`，以便于运营填写的分区表单呈现，不另造脚本规则引擎。
  - 分区：基本信息/作用域、参与及绑定规则、新客或首单条件、达标期限/金额最小单位/币种/观察期、邀请人逐人奖、被邀请人奖、阶梯奖励、个人/活动封顶、退款规则、条款预览。
  - 当前五节点为 `referral.start → referral.bind → referral.qualify → referral.reward → referral.end`；序列化必须匹配实际 parser 的节点属性、边和严格类型，不根据文案猜字段。
  - 引用实际权益定义和 SKU 版本；目录不可用/未核验显示原因，不自动选任意券。数字输入不能把空串、浮点、超出安全整数范围的值静默转为有效整数；金额的 wire 类型与后端约定后精确处理。
  - 7 天、3/5 人、奖励券及预算只是设计示例，不预填成已批准生产政策；未确认的绑定期限起点不由前端推断。
  - 验收：创建→保存→重新读取不丢配置；字段错误定位到分区/节点；跨活动和版本误绑定被阻止；修改既有定义生成后端认可的新版本。

- [ ] **FE-04｜P0｜可开始：真实校验与无副作用仿真。**
  - 接现有 `:validate`、`:simulate`，显示错误、WARNING、原因及观察期时间/候选奖励；明确“仿真结果，不会实际发奖”。
  - 覆盖非新客、首单不足、观察期未满、退款后净额不足、阶梯再次达标、目录未核验等输入；后端暂不返回的维度标未支持，不由前端补成成功。
  - 验收：VALIDATED 不等于可发布；SIMULATED_INPUT 不产生真实人数/到账记录；仿真请求失败与规则未达标分开显示。

### B. 管理端业务闭环：依赖后端后接线

- [ ] **FE-05｜P1｜部分可做：就绪清单、审核和发布。**
  - 改 `features/campaign/CampaignReadiness.tsx`，按活动类型分支；裂变不强制依赖 Offer/Journey，核对冻结规则、真实 SKU、身份/事件源/runtime 能力，显示每项缺失原因。
  - 改 `features/governance/GovernancePage.tsx`、`features/release/{ReleaseWizard,ReleaseCenterPage}.tsx`；准备 REFERRAL_PLAN/referral 类型展示和冻结条款；实际提交/审核/发布依赖 BE-02。
  - 版本、活动、制品必须完全匹配；后端未明确支持的灰度不可开放；版本切换只影响新参与者，历史参与者固定旧版本。
  - 验收：当前发布拒绝有明确提示；将来 ACK/激活结果由后端返回，不能因编译成功自行显示 ACTIVE。

- [ ] **FE-06｜P1｜部分可做：裂变运营台。**
  - 新增 `features/referral/ReferralOperationsPage.tsx`，参与人、关系/资格、奖励、异常四页签；支持活动和实际权限范围筛选、详情和游标翻页。
  - 显示脱敏身份、绑定时间、冻结版本、资格原因、观察截止、当前有效人数/历史里程碑、状态更新时间/数据水位；水位缺失显示未知，不能用页面刷新时间冒充。
  - 租户/组织/店铺/活动进入 queryKey；切换身份和作用域时清理相应缓存、取消旧请求，避免迟到响应串数据。敏感字段不得从另一接口“补齐”。
  - 验收：loading、空数据、部分失败、403、数据滞后分别可辨；游标不能跨筛选复用，分页不重不漏；资格撤销后人数下降但历史里程碑仍可解释。

- [ ] **FE-07｜P1｜部分可做：奖励详情和受控复评。**
  - 修改 `features/operations/AwardIntentsPanel.tsx`，显示 referral 来源、rewardId、awardIntent/权益订单关联、最终履约及追回状态；保留原 Drools 来源和旧 status 语义。
  - 同时保留资格/授权/风险/发放/补偿各维度；详情中说明为什么等待/取消/人工处理。双方奖励独立展示。
  - 复评受权限保护，需操作原因、提交中防重、服务端审计关联；不允许填写任意受益人、SKU、金额或绕过拒绝。接口未实现先禁用说明。
  - 验收：响应超时不生成新业务幂等键盲目再发；同一次操作重试复用原键，新内容使用新操作；未知结果查询原操作，不显示“发奖失败，可重领”。

- [ ] **FE-08｜P1｜部分可做：统计、异常和对账展示。**
  - 扩展 `features/analytics/AnalyticsPage.tsx`/运营台适用区域：访问、绑定、有效邀请、成功发奖、退款失效及异常；显示口径、时间范围、统计水位。
  - 展示取消中、追回中、不可追回/人工审核、应发实发差异；查询/复评依赖真实权限与合同，不新增无审计的强制成功/删除证据操作。
  - 验收：点击分析不产生奖励；访问量不当有效邀请；请求失败不能替换成零值；数据导出能力未确认前不新增主体明文导出。

### C. 普通用户端：位置和 BFF 明确后实施

- [ ] **FE-09｜P1｜待依赖：活动页、邀请链接及分享。**
  - 在实际商城/H5 项目实现活动规则、活动起止时间、冻结条款及明确参加动作；已结束/暂停/未发布分开呈现。
  - 通过 BFF 加入和生成分享链接；只使用受控分享域名，不从任意跳转参数拼出开放重定向。
  - inviteToken 是邀请分享凭证；与交易归因 referralToken、奖励授权凭证是不同合同，不能互换。链接解析不等于创建绑定。
  - 分享凭证不写日志、埋点或永久浏览器存储，不被错误上报/第三方脚本带走；登录跳转保留邀请上下文的方式由 BFF 合同确定。
  - 验收：复制失败/链接过期/活动不可参与明确；访问链接不会静默替用户同意条款或创建关系。

- [ ] **FE-10｜P1｜待依赖：登录、绑定确认和我的进度/奖励。**
  - 登录后显示脱敏邀请人、条款版本和明确确认按钮；未同意不能绑定；已绑定/自邀/非新客/链接无效显示后端原因，不提供前端改绑覆盖。
  - 我的页面展示服务端有效人数、档位、好友脱敏状态、观察期及双方各自奖励结果；退款后失效、同档位重新达标不重发的规则向用户说明。
  - C 端只访问正式 BFF；不直连 `/internal/**`，不签发断言、不提交自报 canonicalSubject/受益人、不携带管理台 DEV 身份。
  - 验收：登录跳转/重复点击/超时重试不产生第二关系；移动端可用；个人数据仅当前登录主体可见。

- [ ] **FE-11｜P2｜待依赖：交易页面配套及隐私展示。**
  - 若实际商城包含订单创建/详情，通过已确认 BFF 合同透传可选归因 token；无 token 旧请求继续可用，不从分享 token 自行构造归因 token。
  - 不暴露订单永久归因快照里的主体、签名或内部证据；前端不决定 token 单次消费、释放或旧成功回放。
  - 隐私方案批准后，再接后端脱敏/注销展示状态；现在只尊重服务端最小返回，不提供自行清除交易/资格/审计数据功能。
  - 依赖真实 C 端仓库、订单/BFF DTO 和隐私决定；不能将本项标为已联调。

## 4. 状态文案最低验收表

以下是展示语义要求，不是对尚未开放 HTTP DTO 的字段命名承诺。最终合同须明确原始维度、reasonCode 和更新时间；未知枚举显示“状态待确认”，保留可定位错误，不归入成功。

| 后端含义 | 推荐呈现 | 不能呈现 |
|---|---|---|
| 观察期内/证据不足 | 观察中/等待核验 | 已达标、已到账 |
| 风险 REVIEW / UNAVAILABLE | 审核中/核验暂不可用 | 永久失败或允许重领 |
| WAIT_QUOTA | 等待额度处理 | 整个活动已售罄 |
| HELD / 202 / ACCEPTED | 待发送/已受理，等待发放结果（按真实阶段） | 奖励到账 |
| delivery UNKNOWN | 发放结果确认中 | 新申请一次即可 |
| SUCCEEDED | 已发放（以权威终态为准） | 仅请求提交成功就采用此状态 |
| SUCCEEDED + INVALIDATED + compensation PENDING | 已发放，追回中 | 正常完成或已经追回 |
| REVERSED / MANUAL_REVIEW | 已追回/需人工处理，分别展示 | 两者统一写追回成功 |
| 资格失效后又达到同档位 | 当前人数恢复，历史档位已处理 | 自动生成第二份档位奖励 |

## 5. 后端依赖清单与接口状态

以下“设计路径”只用于对齐，Cursor 不据此假设接口已存在。待后端交付 DTO、权限、幂等、分页、错误码及样例后再接正式 client。

| 依赖ID | 接口/能力 | 当前状态与负责方 |
|---|---|---|
| BE-01 | `/api/v1/campaigns` 的 campaignType 与类型筛选 | 后端待扩展，Codex |
| BE-02 | definition submit/approvals、compile/release 的裂变完整治理与 runtime readiness | 校验/仿真已实现；发布闭环待完成，Codex |
| BE-03 | 设计 GET `/api/v1/referral-campaigns/{campaignId}/{participants,relations,rewards,summary}`，四种资源分别请求 | 运营查询/DTO/Scope/游标待交付，Codex |
| BE-04 | 设计 POST `/api/v1/referral-rewards/{rewardId}:reevaluate` | 权限、原因、幂等及复评结果合同待交付，Codex |
| BE-05 | `/api/v1/award-intents` 新来源/终态/补偿扩展及对应查询 | 旧接口可复用，新字段完整接线待交付，Codex |
| BE-06 | 浏览器→BFF 的活动/加入/token/resolve/bind/me | 浏览器路径、鉴权及 C 端仓库未确定；BFF 与身份负责人/Codex |
| BE-07 | 真实 SKU/权益版本目录、风险/订单/主体映射 | 部分基础可复用；真实来源和接通状态须逐项确认，Codex/来源负责人 |
| BE-08 | 可重复种子与真实联调环境 | 由后端提供 SQL/脚本并授权执行；Cursor 提供场景清单，不直改数据库 |

现有可优先核对的控制接口：POST `/api/v1/definitions`、GET `/api/v1/definitions/{definitionId}/versions/{version}`、POST 同版本 `:validate`/`:simulate`、GET `/api/v1/registries/nodes`。具体 body/响应读取 `ControlController`、`ControlApplicationService`、`GraphSimulationService` 与 SPI parser；接口存在不保证当前环境和权限已配好。

用户待确认：C 端项目/BFF、权威身份与组织店铺、绑定期限起点、隐私及留存、真实渠道/KMS、试点券/门槛/期限/预算。历史文件中的建议天数/数量不可当作批准决定。缺口写入交接，不反复要求用户为普通实现选择逐项批准。

## 6. 测试、数据和交付标准

- [ ] **FE-12｜P0–P2｜贯穿各阶段：专项验证与交付记录。**
  - 数据全部经接口获取；给后端种子需求：有效/失效邀请、观察中、非新客、已绑定、双方一成功一等待、退款追回、券已核销需人工、额度等待、分页/Scope 隔离。演示页禁止硬编码业务数据或假成功，测试目录的固定响应只能作为测试替身，不能冒充真实联调。
  - 组件测试覆盖表单数值/序列化、旧方言兼容、未知枚举、五维状态组合、权限、错误/空态、请求重试与缓存隔离。
  - Playwright 覆盖设计→保存→校验→仿真和发布受阻；接口交付后补运营查询及 C 端加入→绑定→进度→终态→退款。覆盖桌面/移动、键盘、焦点、可读标签，状态不只靠颜色表达。
  - 真实后端+数据库种子的端到端结果与 mocked E2E 分开记录；未接通用 blocked，不使用 skip 充当通过。只访问本地或明确授权的测试环境。
  - 每个功能阶段完成实现、对应专项、review、文档后再接下一阶段；不要对小改动重复运行所有后端全量。

仓库已有验证命令（此交接未执行它们，不宣称前端通过）：

```sh
pnpm frontend:lint
pnpm frontend:build
pnpm --filter @marketing/console test src/features/referral
pnpm frontend:e2e
```

新增测试存在后再执行相应路径；受影响旧 CampaignReadiness/ReleaseWizard/AwardIntentsPanel 等专项同批验证。现有 Playwright 配置已含 desktop/mobile Chromium，端口 4173；运行前检查端口归属，不停止别人的服务。

交付文件：在本目录新增 `CURSOR_PROGRESS.md`，按 FE-ID 记录实际改动、实现/待接口状态、执行命令、结果和证据路径；同步 API 缺口及后端联系人。按逻辑任务建立独立分支/提交，只提交本人变更；不要擅自推送/部署。所有 FE 完成仍不能替代 161 项冻结需求及生产门禁验收。

## 7. 可直接发送给 Cursor 的提示词

> 请读取 `docs/delivery/referral-cursor-frontend/CURSOR_TODO.md`，并核对交易中心最新 `CODEX_PROGRESS.md` 与本仓库规则/现有 Git 改动。你负责前端，后端由 Codex 并行完成。先做 FE-01、FE-03、FE-04，再按依赖推进管理台及 C 端；不覆盖现有用户修改，不重做已完成页面。沿用现有组件和真实 API；缺接口明确待接入，不造假数据/假成功，不把 HELD 或 202 显示为到账，不绕过发布或权限门禁。C 端仓库/BFF 尚未提供时继续独立的管理端工作。普通实现按阶段连续推进，无需反复询问“是否继续”；将进度、测试和后端缺口写入本目录 `CURSOR_PROGRESS.md`。本提示不授权修改后端、清理数据、推送或部署。
