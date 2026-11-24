# 低代码营销商业化执行进度

执行用户已批准的[实施方案](../../design/lowcode-commercialization/IMPLEMENTATION_SLICES.md)。首发目标为单门店指定商品满减与订单级补贴对账。

| 切片 | 当前状态 | 证据或缺件 |
| --- | --- | --- |
| LC-01 | 完成 | 复核历史隔离证据和当前本机网关、账本与画像。交付[证据与签收准备](READINESS.md) |
| LC-02 | 签收准备完成，等待输入 | 已查找本地跨仓合同与验收资料。现有资料属于本机与隔离验证、接口设计或权益发放对账。等待授权试点、真实机器范围、同单支付退款凭证及出资分摊签收 |
| LC-03 至 LC-06 | 等待前置 | LC-03 等待 LC-02 签收。LC-04 验证已签收的分摊及分次退款样本。正式上线单独审批 |
| LC-05a | 本机验证通过 | 控制台用既有流水查询权限导出已加载明细，保留精确金额、范围、订单过滤和分页状态。构建、Lint、277 个单测和 4 个桌面及移动浏览器用例通过。候选控制台通过本机网关导出两条初始化账本记录，CSV 与查询结果一致 |
| LC-07 | 来源准备完成，等待合同 | 已识别业务身份、水位、冻结注销与等级写入边界。等待 Commerce 和 Auth 签收身份与全量读取合同 |
| LC-07a | 验证中 | Commerce `feat/member-baseline-read` 的同步入口实现固定只读事务分批扫描。首轮验证通过 10,000 行及超限拒绝。并发用例首次在测试替身调用失败，调整为执行真实 XML 后复验 |
| LC-08 至 LC-13 | 等待前置 | 按实施方案的依赖推进 |

## 验证与恢复

- 首轮复核基线为营销 `07393f1`、商城 `cd5ba82` 和交易 `d85d392`。营销任务分支为 `feat/lowcode-commercialization`。交易中心既有未提交改动保留。
- 脱敏本机复核记录为 `~/.local/share/marketing-delivery/lowcode-commercialization/live-readonly.json`。历史交易隔离证据位于 `~/.local/share/marketing-delivery/platform-integration-20261124/`；商城本机验证位于 `~/.local/share/marketing-delivery/commerce-member-audience/`。记录可区分本机、隔离、正式证据。
- 流水导出证据位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc05/`。`live-result.json` 记录租户级只读审计身份在 1440 和 390 像素的候选版验证。默认门店范围查询该初始化订单为空，页面保留既有权限边界。
- 后续收到签收记录时先核对授权及同单证据。LC-02 签收后执行 LC-03，随后在 LC-04 验证退款分摊。会员身份及全量水位合同到位时可并行继续 LC-07。旅程和裂变的额外业务缺件见[就绪条件](READINESS.md#lc-10-和-lc-12-的就绪条件)。
