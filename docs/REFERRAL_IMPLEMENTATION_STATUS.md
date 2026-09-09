# 裂变后端实施导航

2026-09-08。完整目标及161项验收以交易中心`.codex/tasks/referral-end-to-end`冻结原文和最新检查点为准。本页说明当前可用内部切片，不能将R1原有production-candidate说明套用到新增裂变能力。

## 2026-09-09可信发布接续

R1 制品验签与 R2 的 VERIFIED 耐久安装已实现，见[发布实施状态](delivery/referral-release-runtime/DELIVERY_STATUS.md)。仅验证签名计划与存储一致性；尚未完成 READY/ACK、激活、参与许可或 control 发布开放。冻结 SKU 的权威映射继续待签收。

## 2026-09-09接入合同核对

身份、交易证据和发布的实际字段、路径、拒绝语义、实现顺序与验收矩阵已对照代码整理到[跨平台接入基线](../../transaction-center/docs/contracts/referral-integration/CONTRACT_BASELINE.md)。C02/C03旧目标与交易原生DTO存在差异，不能直接改名接线；权威主体/范围、绑定期限起点与签名配置等尚未签收。本次没有开放参与入口、移除发布拒绝或宣称真实适配已部署。

## 2026-09-08本轮接续

以下旧切片表保留其历史证据范围。最新V5–V11、活动类型和运营接口的实现/审查/QA/剩余工作统一见[接续交付](delivery/referral-completion/DELIVERY_REPORT.md)。已补永久奖励、配额、授权回执、可信终态账本、4个运营GET及复评POST；真实外部适配、发布和全链路仍未验收。代码未部署，前端由Cursor联调，不能把历史独立审查覆盖声明套到本轮改动。

## 已收口的内部切片

| 切片 | 当前行为与证据 | 保留边界 |
|---|---|---|
| 规则SPI | 两种资格模式、观察期、累计退款净额、双方与阶梯候选；[13项专项](delivery/referral-runtime/QA_REPORT.md) | 候选不是授权或发奖成功 |
| 编译器 | REFERRAL_POLICY→REFERRAL_PLAN，严格线性图、规范字符串、专用稳定hash；[29唯一专项](delivery/referral-compiler/QA_REPORT.md) | 冻结SKU权威校验及发布闭包未完成 |
| 控制面预览 | 同源validate、纯simulate，明确SIMULATED_INPUT及目录未核验提示；[50组合专项+12修订专项](delivery/referral-control-preview/QA_REPORT.md) | submit/decide/stage/ACK/activate/rollback继续关闭 |
| 参与持久化 | 永久命令、固定版本/Scope、主体角色、HMAC持久版本锚点、审计Outbox；[15唯一场景](delivery/referral-participation/QA_REPORT.md) | 无公开HTTP、真实身份/发布默认拒绝，V1只隔离库验证 |
| 奖励授权合同 | 内部Ed25519完整声明、固定受众/Scope、规范编码/时间、稳定业务摘要；[最终10项专项](delivery/referral-award-contract/QA_REPORT.md) | 无签发服务/发奖入口，在线资格消费、永久受理回放及SKU重建待接 |

| 邀请令牌 | 256位随机分享token、永久hash、短期加密回放；[15初轮+1专项](delivery/referral-invite-token/)及独立审查 | 分享允许多人，非交易归因jti单次消费；V2未共享迁移 |
| 首绑 | 首绑永久获胜、原请求回放、排序锁及原始时间；[DB11+纯12](delivery/referral-binding/)及独立审查 | V3只隔离库；maxBindAge起点待确认，以受信绝对bindUntil默认拒绝 |
| 累计证据纯合并 | 历史冲突、保留原Scope隔离、未决退款/水位；[最终30专项](delivery/referral-evidence/)及独立审查 | 不推导paid=settled或memberId=canonical，缺权威仍PENDING |
| 参与时间精度 | 锁后原始Instant判断、只在持久时间截断；[3专项](delivery/referral-participation-time/)及独立审查 | 此后修订不包含在原参与/绑定DB快照中 |
| 奖励候选组装 | 固定签名/历史发布/COUPON版本/主体规则；[20专项](delivery/referral-benefit-assembler/)及独立审查 | CANDIDATE_ONLY，不能算资格确认或发奖 |
| 永久准备纯状态 | 同身份确认未知恢复、fence接管、receipt隔离；[8专项](delivery/referral-award-preparation/)及独立审查 | V10另有21项仓储专项及独立审查；仅receipt不得忽略当前取消栅栏 |
| 资格证据纯准备 | Scope/历史/版本CAS准备、显式许可寿命；[9专项](delivery/referral-qualification/)及独立审查 | V4A真实账本仍在实施 |
| 数量配额纯边界 | 守恒/调拨/fence、未知不释放、永久成功历史回放；[9专项](delivery/referral-quota/)及独立审查 | 非资金预算；全库唯一/个人限额/数据库原子尚未接入 |

以上数量各自对应不同切片、重跑与源码快照，不能相加为整平台全量。详细日志、独立审查和源指纹在对应delivery目录。

## 正在实施

当前并行：资格证据V4A的Inbox/history/current/quarantine/fanout正在跑真实MySQL11；权益准备V10已13纯+8DB=21专项及独立审查，继续V11专用HELD受理编排。数据库验证按就绪错峰，隔离源码目录避免target竞争。[奖励在线确认与取消纯边界](delivery/referral-authorization/DELIVERY.md)已10项专项/4SHA及独立审查，尚未开放HTTP/真实签发。

同级benefit-center可选expectedSkuVersion受理已25项受影响专项和独立审查通过，保留旧请求/原hash及永久回放。该技术前置不等于真实SKU权威目录映射、渠道接入或完整裂变端到端验收。

## 集成与部署状态

- 控制面接口及返回形状见[预览合同](delivery/referral-control-preview/API_PREVIEW.md)，前端由Cursor接入，不写死业务数据。
- 新SPI、contracts及referral-service已注册Maven reactor；既有CI根clean verify会包含。当前仅本地隔离专项，远程CI未执行。
- 新referral-service已补默认关闭的Compose profile/Helm配置并通过离线模型审查；未实际构建镜像、启动部署或纳入共享dev_infra库初始化及网关发布。V1/V2/V3仅隔离数据库测试；不能将现有9服务迁移脚本输出解释为新增裂变库已部署。
- 真实BFF/JWKS/KMS、主体及组织店铺权威来源、历史制品/kill-switch、SKU/风控/权益履约/取消栅栏、完整Inbox/Outbox/对账、容量故障与生产签收仍未完成。
- 隐私方案、真实渠道、生产参数和绑定年龄起算点未确认；不删除数据。密文回显截止不等于物理清理已验收。
