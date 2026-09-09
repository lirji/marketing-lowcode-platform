# Cursor 前端进度：邀请有礼管理台

更新时间：2026-09-08。仓库：`marketing-lowcode-platform/frontend/apps/console`。只改前端，未改 Java / 迁移 / 数据库。

## 本轮范围

按评定裁剪：FE-01 合同与路由、FE-03 分区设计器、FE-04 校验/仿真、FE-02 仅导航分流。FE-06 已接参与人/关系/奖励三类只读查询；异常、奖励复评、统计仍待接口。C 端未动。

## FE-ID

| ID | 状态 | 实际改动 | 待接口 |
|---|---|---|---|
| FE-01 | 已实现（未联调） | `schemas.ts` 增加 `REFERRAL_POLICY`、裂变仿真联合解析；`client.simulate` 不再把裂变响应当 Offer 价格；路由 `/designers/referral`、`/referral-operations`；权限沿用 `definition:*` / `campaign:read`+`trace:read` | 联调环境与权限未验证 |
| FE-02 | 部分 | 创建弹窗「创建后进入」只决定跳转，**不写入** `campaignType`。列表若后端返回 `campaignType` 才展示，缺省不伪造。增加「邀请有礼」入口 | BE-01 持久类型与筛选 |
| FE-03 | 已实现（未联调） | `ReferralDesignerPage` 分区表单；`referralGraph` 序列化为 `start→bind→qualify→reward+→end`，字段集合与编译器一致；复用 `SkuPickerField` / 权益目录；不预填 7 天、3/5 人、示例券；`maxBindAgeSeconds` 不推断起点 | 控制面保存/回读需联调 |
| FE-04 | 已实现（未联调） | 接现有 `:validate` / `:simulate`；仿真 facts 全字符串且 keyset 固定；结果要求 `simulationOnly` + `SIMULATED_INPUT`；VALIDATED ≠ 可发布；提交审核禁用并写明 `REFERRAL_RELEASE_NOT_AVAILABLE` | 仿真/校验需联调环境 |
| FE-05 | 部分 | 就绪清单：存在 `REFERRAL_POLICY` 定义时不强制 Offer/Journey；发布项保持未接通 | BE-02 发布闭环 |
| FE-06 | 三类查询已接 | 参与人 / 关系 / 奖励接 `GET /api/v1/referral-campaigns/{id}/…`；`validCount=null` 显示未计算；ACCEPTED 不显示已到账；异常页签仍待接入；水位用响应 `asOf` | BE-03 summary |
| FE-07 / FE-08 | 未做 | 不接未交付奖励复评与统计 | BE-04 / BE-05 |
| FE-09–11 | 未做 | C 端仓库 / BFF 未提供 | BE-06 |
| FE-12 | 进行中 | 已补 referral 组件测试与 Playwright 演示态；未跑真实后端种子 E2E | BE-08 |

## 命令与结果

本机已执行并通过：

```sh
pnpm --filter @marketing/console exec tsc -b --pretty false
pnpm --filter @marketing/console test src/features/referral src/shared/api/schemas.test.ts src/features/campaign src/App.test.tsx src/features/designers/graph.test.ts src/features/designers/OfferDesignerPage.test.tsx src/features/designers/BenefitEditorPage.test.tsx
```

结果：`tsc -b` 通过；referral + 回归 Vitest 通过。Playwright 仅跑新增裂变用例：

```sh
pnpm --filter @marketing/console exec playwright test e2e/console.spec.ts -g "referral"
```

desktop / mobile Chromium 共 6 项通过（设计器表单 + 发布禁用；运营台演示态无假数据；运营台 live mock 读参与人、未计算、无已到账）。未跑全量 `frontend:e2e`、`frontend:lint`、`frontend:build`，也未接真实后端种子。

## 后端缺口

- BE-01 `campaignType` 持久化与筛选
- BE-02 裂变 submit / ACK / 激活
- BE-03 运营 summary / 异常查询（三类列表已交付）
- BE-04 受控复评
- BE-05 award-intents 裂变终态
- BE-06 C 端 BFF
- 绑定期限起点、真实目录核验、权威身份来源仍待确认

## 分支与提交

未推送、未部署。工作区与原平台已有用户未提交改动并存；本轮只新增/修改前端文件，不覆盖他人后端改动。
