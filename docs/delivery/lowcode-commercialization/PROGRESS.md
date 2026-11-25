# 低代码营销商业化执行进度

执行用户已批准的[实施方案](../../design/lowcode-commercialization/IMPLEMENTATION_SLICES.md)。首发目标为单门店指定商品满减与订单级补贴对账。

| 切片 | 当前状态 | 证据或缺件 |
| --- | --- | --- |
| LC-01 | 完成 | 复核历史隔离证据和当前本机网关、账本与画像。交付[证据与签收准备](READINESS.md) |
| LC-02 | 隔离原生下单及支付前预算门禁已验收，正式签收待补 | 商城核对报价来源，营销签价并按唯一订单预留预算，交易验签放行支付意图，运营按原生订单核对付款前绑定。正式签收仍需授权门店、真实身份与商品配对、机器范围、同单付款退款与出资分摊凭证 |
| LC-03 | 等待前置 | LC-02 签收后，使用实际支付回执验证绑定、唯一入账和故障恢复 |
| LC-04 | 等待前置 | LC-03 完成后，以已签收的出资分摊和分次退款样本验证冲回 |
| LC-05 | 等待前置 | LC-04 完成后，按确认、退款及异常处理状态完成完整日结 |
| LC-05a | 本机验证通过 | 控制台用既有流水查询权限导出已加载明细，保留精确金额、范围、订单过滤和分页状态。构建、Lint、277 个单测通过，浏览器 116 通过、12 条按环境跳过。候选控制台通过本机网关导出两条初始化账本记录，CSV 与查询结果一致 |
| LC-05b | 验证通过 | 资金汇总提供按币种及归属的补贴确认、退款或冲正和净额。控制台可导出同口径 CSV，保留窗口、租户、权限范围及关联校验状态。真实 MySQL 验证跨日、权限、全额冲正和超出单笔 long 范围的汇总 |
| LC-05c | 验证通过 | 订单对账可在入账前查看待绑定、已绑定及首次回执待核查。真实 MySQL 验证首次版本、只读数据库权限和范围隔离，桌面和手机通过打包联跑 |
| LC-06 | 等待前置及授权 | LC-05 的财务验收、试点门店、关账观察期和上线授权 |
| LC-07 | 来源准备完成，等待合同 | 已识别业务身份、水位、冻结注销与等级写入边界。等待 Commerce 和 Auth 签收身份与全量读取合同 |
| LC-07a | 专项与远端源码验证通过 | Commerce `69a57b0` 的同步入口在固定只读事务内按会员主键分批扫描。隔离 MySQL 验证空结果、10,000 行、超限拒绝和两批间提交等级及状态变更。旧请求读取一致，新请求读取更新值。`452eda1` 进一步隔离重复验证的对照租户 |
| LC-07b | 本机验证通过 | 商城按会员等级汇总在籍人数，响应不含会员标识。营销用已保存规则匹配。隔离环境通过商城与营销实际应用和双端浏览器预估 10,001 人，同步快照和旧预估路径仍拒绝超限 |
| LC-08 | 等待前置 | LC-07 基线水位和授权撤销合同 |
| LC-09 | 等待前置 | LC-05 与 LC-08，含已批准的人群投影、失效和撤销策略 |
| LC-10 | 等待前置及渠道签收 | LC-06、已开通的真实渠道、同意与退订及生产事件入口 |
| LC-11 | 等待前置 | LC-05 与 LC-10，含稳定分组、真实送达、订单收入和成本 |
| LC-12 | 等待前置及获客签收 | LC-06、试点获客渠道、主体授权、首单证据及观察期 |
| LC-13 | 等待前置及权益签收 | LC-12、真实奖励 SKU 终态、撤销合同及发布授权 |

## 验证与恢复

- 首轮复核基线为营销 `07393f1`、商城 `cd5ba82` 和交易 `d85d392`。营销任务分支为 `feat/lowcode-commercialization`。交易中心既有未提交改动保留。
- 脱敏本机复核记录为 `~/.local/share/marketing-delivery/lowcode-commercialization/live-readonly.json`。历史交易隔离证据位于 `~/.local/share/marketing-delivery/platform-integration-20261124/`；商城本机验证位于 `~/.local/share/marketing-delivery/commerce-member-audience/`。记录可区分本机、隔离、正式证据。
- LC-02 的试点签价绑定范围版本、原交易报价、商城快照摘要、报价主体、活动与发布组合、出资方及逐行应付。报价签发采用独立权限和固定机器，普通权益报价与资金预留继续使用既有凭证。营销决策 52 项验证通过，涵盖真 MySQL 与 Redis 下的组合求值、权限隔离、签名、重放及过期，证据见 `~/.local/share/marketing-delivery/lowcode-commercialization/lc02-checkout-signing/decision-final.log`。
- 首店试点使用商城 `62757a7`、交易 `6049254` 和营销 `9c5d773`。独立 MySQL、打包商城与交易应用、OIDC 签名及打包营销定价与签名代码联跑，二件原价 11800、优惠 1000、应付 10800 分。对照门店拒绝，一件未达门槛，异签 409，交易重启后相同签名返回原金额，数据库仍只有一笔。证据见同目录 `checkout-freeze-result.json`。
- S3 原生订单使用显式会员、两侧规格发布版本和收款商户配对，冻结报价与订单、库存同事务。交易原生下单与版本复核通过真 MySQL 全量 450 项测试，暂停后证据增量再次通过五项专项。打包商城、交易、签价代码和隔离 OIDC 联跑核对同一笔 11800/1000/10800 分订单。重启可重放，关闭新下单开关后机器仍能查单，支付依旧拒绝且数据库支付意图为零，取消释放两件库存。交易功能提交为 `2cdd7ad`、`9f73944`，证据见 `~/.local/share/marketing-delivery/lowcode-commercialization/lc02-native-order/native-order-result.json`。正式映射及授权仍按签收清单确认。
- S5 隔离联跑在打包商城、营销资金、交易及 OIDC 中验证。资金对两件商品预留 1000 分，原回执重复请求稳定，交易验签后受理 10800 分支付意图。真实交易取消的另一笔释放 1000 分，仍在途订单保留 1000 分。真 MySQL 专项覆盖不足额、普通到期任务跳过试点、渠道仍在关单时保留及收到迟到已付证据后只核销一次。资金模块 108 项、交易服务 450 项及合同回归通过。证据见 `~/.local/share/marketing-delivery/lowcode-commercialization/lc02-funding-receipt/`。正式渠道收款与分次退款继续按 LC-03、LC-04 和签收单验证。

- 流水导出证据位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc05/`。`live-result.json` 记录租户级只读审计身份在 1440 和 390 像素的候选版验证。网关验证缺少对账权限时返回 403，默认门店范围查询该初始化订单为空。操作见[运行手册](../../operations/RUNBOOK.md#按订单核对营销补贴流水)。
- 账本汇总证据位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc05b/`。资金服务全量 106 项通过。最终改动复验 20 项真实 MySQL 集成及 16 项契约通过。控制台构建、Lint、277 项单测及 116 项浏览器用例通过，12 项按环境跳过。现有本机资金服务收到新参数后仍返回 HTTP 200 和原来的净额结构。候选应用以只读数据库账户连接隔离 MySQL，桌面与手机查询及导出均得到确认 1,000、冲减 1,000、净额 0 分，见 `candidate-result.json`。正式试点仍由真实付款和退款分摊凭证签收。
- 订单绑定证据位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc05c/`。资金服务全量 107 项通过，共用授权条件变更后复验 28 项真实 MySQL 集成及 16 项接口契约。控制台构建、Lint、277 项单测及 118 项浏览器通过，12 项按环境跳过。打包资金服务以只读账户连接隔离 MySQL，两种屏幕宽度均查到三种绑定状态，并同时验证补贴流水为空、门店越界和大小写不同的租户返回空、缺对账权限返回 403。首次版本以原始精确值显示，后续事件不会替换它。正式交易、退款和关账继续按 LC-02 至 LC-06 验收。
- 安全检查 `45403099534` 在 Flink 作业报告 Jackson Databind 2.20.2。作业模块统一使用 2.21.8 BOM，覆盖 [FasterXML 拒绝服务修复](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24)及[后续校验修复](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)。重复打包时重建原始 Jar，避免 Shade 复用含旧依赖的产物，见 [Maven Jar Plugin](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation)。功能提交和安全修复保留各自任务分支。
- 本机三个 Flink 作业重新打包后，每个产物的 1,194 个 Jackson 类文件均与 BOM 解析到的依赖逐字节一致。作业及支持模块 37 项测试通过。安装新 JAR 后的真实 Flink checkpoint 回执用例通过。组合发布用例由独立 CI 作业运行。检查点测试保留失败的标准输出与错误输出，并以固定只读权限为非 root JobManager 提供 JAR。
- 会员本机验证日志位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc07/`。首次并发用例执行测试替身失败，调整为当前事务绑定的 SqlSession 执行真实 XML 后通过。并行全量回归出现既有积分测试事务超时，随后专项复验 10 项全部通过，见 `targeted-final.log`。CI `45365787291` 对 `69a57b0` 完整验证通过。现有机器租户过滤及 10,000 人同步入口约束继续适用。
- 新版等级预估证据位于 `~/.local/share/marketing-delivery/lowcode-commercialization/lc07b/`。商城 `5c70c83` 实测冻结、注销、租户隔离、大小写与空格。营销 Audience 全量 15 项通过，覆盖旧商城结果、越权、过期数据、重复组、非整数和人数溢出。控制台 277 项单测及 116 项浏览器用例通过，12 项按环境跳过。`candidate-result.json` 记录商城 MySQL、Audience 和 1440、390 像素控制台的同一人数。现有本机旧营销入口收到新参数后仍返回原响应。该预估不提供持久化会员名单及撤销水位。
- 后续收到签收记录时先核对授权及同单证据。LC-02 签收后执行 LC-03，随后在 LC-04 验证退款分摊。会员身份及全量水位合同到位时可并行继续 LC-07。旅程和裂变的额外业务缺件见[就绪条件](READINESS.md#lc-10-和-lc-12-的就绪条件)。

## LC-02 S5 预算回执操作

资金服务默认关闭该入口。启用 `CHECKOUT_FUNDING_ENABLED` 时，按已确认范围配对机器主体、两侧租户、组织、营销店铺、商城门店、活动版本和平台出资方。交易证据 URL、受限定交易令牌和 Ed25519 密钥通过受控 Secret 注入。资金与签价使用独立密钥。资金直接复用 `BUDGET:PLATFORM:<出资方>:CNY` 账户、现有分桶和审计流水。

结算机器对同一交易报价调用 `POST /api/v1/promotion-applications/checkout-pilot/{reference}:reserve`，提交原始签价并使用 `promotion:checkout-pilot` 权限。资金 V18 在同一事务中固定唯一的原生订单、预算申请和回执。超时以原引用和原证明重试。在资金返回预留回执后，结算机器才用交易的 `trade.checkout-pilot.funding` 权限提交回执，随后走原生支付入口。

相同权限通过 `POST /api/v1/promotion-applications/checkout-pilot/{reference}:reconcile` 主动复查交易，订单可靠取消时释放，存在正常支付事实时核销，渠道在途时保留。普通到期作业和通用确认、取消、退款入口不改变试点资金。暂停售卖时关闭新下单及 `CHECKOUT_FUNDING_ACCEPT_NEW`，继续处理已产生的预留。启用前确保资金和交易的活跃实例均已完成对应迁移并执行新门禁。

## LC-02 S6 运营核对

在控制台「运营 → 资金对账」输入原生交易订单 ID，可在补贴入账前查看「试点订单已绑定」及绑定时间。该状态来源于资金预留时已核对的订单，首次回执版本留空。资金流水用于查看已确认和已冲回的补贴。

旧客户端仍可按首次交易回执查询。订单绑定接口支持可选的 `includeCheckoutBinding=true`，继续按租户、组织、门店和活动筛选。

资金模块 108 项、控制台 277 项单测及 118 项桌面与移动端浏览器用例通过，浏览器 12 项按环境跳过。隔离联跑及远端检查以 `~/.local/share/marketing-delivery/lowcode-commercialization/lc02-funding-receipt/delivery-result.json` 为证据索引。
