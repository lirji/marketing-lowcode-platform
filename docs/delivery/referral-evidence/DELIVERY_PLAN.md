# 裂变累计订单/退款证据纯合并器

## 合同核对
冻结10-FINAL_PLAN §7与07-EXTERNAL_CONTRACTS C03：同订单结算/退款共享orderRevision；grossEligibleMinor-cumulativeRefundMinor=netEligibleMinor；事件累计快照；rev8退款先到不能被rev7结算覆盖；同revision不同内容隔离。

交易中心当前真实实现（只读核对，未修改）：
- transaction-server/OrderReferralEvidenceService.Evidence：memberId/evidenceVersion/firstOrderPolicy/isFirstConfirmedPaidOrder/paidAmount/refundedAmount/pendingRefundAmount/netPaidAmount/conservativeEligibleAmount/paidAt/completedAt/asOf。
- transaction-core/RefundEvidenceEvent.Data：累计退款和在途退款，净支付金额与保守金额均显式；事件不带结算时点。
- transaction-core/OrderPaidEvent、OrderCompletedEvent：支付与完成是独立事件，不能自行解释为冻结的FIRST_ORDER_SETTLED。

因此本切片不重新实现交易金额账本或状态机，不把memberId当canonicalSubject，不把paidAt当settledAt。适配器须提供经过明确策略映射的归一累计快照；缺失/未确认映射继续PENDING。pendingRefund>0保守地不产生资格，不擅自以某种净额口径判定达标。

## 计划范围
只新增SPI纯数据结构与合并器/专项测试，无网络、数据库、服务入口或参与绑定修改。完整scope为tenant+sourceSystem+canonicalSubject+orderId，另固定首单政策版本；所有索引比较采用字符串精确身份。来源真实性来自受信调用方，bool不是验签证明。

既有状态+输入快照→新状态/稳定reasonCode：未验证输入不推进；revision单调；旧revision无覆盖；相同revision相同语义是重放；内容不同冲突，保留原状态与冲突结果。金额用累计覆盖而不是按event累加；已知累计退款不能下降；净额一致性/币种/作用域失败关闭。退款先到缺settledAt保留为pending，等待权威完整快照；完整事实首次可信接收时间固定，不随重试、后续退款或读取时间移动。

输出可供Evaluator的Evidence，但newCustomer始终UNKNOWN；只有调用方另附经过租户/主体/策略关联验证的会员新客结果后才可能产生资格。账户合并/映射未确认不自认新客。持久Inbox/证据/资格原子推进与冲突隔离由后续服务完成。

## 待根Agent核对的边界
同revision“不完整退款通知”与“完整查询快照”不得简单当成两个不同完整内容任选一个。优先要求事件适配器获取相同schema完整权威快照；若支持字段级补充，必须明确补充合同与冲突定义，不能在纯合并器中默默放宽原同revision冲突要求。

## 验证计划
作用域/政策/币种隔离；重复和不同eventId不累加；旧revision/同revision冲突；退款先到；退款累计降低；金额溢出/净额不一致；首事实receivedAt固定；来源未知/首单语义未知/会员未知失败关闭；所有顺序与重放结果确定。使用隔离专项和独立审查，不全平台全量。

## 已确认的边界与审查修订
root已确认同revision不同规范语义一律CONFLICT，不隐式补全；receivedAt、snapshotAt/asOf、evidenceId作为元数据分离，不参与业务相等比较。新增六维Scope包含组织/店铺，不提供通配匹配；canonicalSubject按C01限制256 Unicode码点并拒绝非法代理项，不trim/NFC/casefold，Scope的toString脱敏。

历史冲突不能只看latest。纯API显式接收 `HistoryLookup: NotSeen | Seen(snapshot) | Unavailable`，由后续数据库短事务提供，不在State存无界历史Map，也不执行隐藏I/O。Seen须与输入scope/revision对应，不同内容隔离；Unavailable不当NotSeen。State的historyPendingRevision保存未完成历史核验水位，阻止旧revision重放清除更新修订的pending；恢复同等/更新修订核验后可解除，永久quarantine则不自动解除。

独立审查指出新可信revision非法金额/币种/状态若仅REJECTED保留READY会让旧证据继续通过。已改为持久quarantine，原latest及时间锚仍保留，但toEvaluatorEvidence必定verified=false。来源未验证/纯跨scope错误不污染已有可信状态。
