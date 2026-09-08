# Referral 控制面校验与预览

已授权冻结方案后续小切片：允许控制面完整校验及无副作用预览，不开放审批、发布或奖励。继续 deliver-feature-end-to-end 工作流，复用前两阶段已审查规则。

共享 `ReferralPlanCompiler` 从 compiler application 机械移至 `referral-runtime-spi`，SPI增加lowcode-language-core依赖，无环；compiler保留HTTP原始类型检查与签名层，只有parser import改变。编译envelope、ABI及hash字节不变。控制面新增SPI依赖，无服务间应用包依赖。

校验使用同一parser+ReferralPolicyValidator，非法配置返回ValidationIssue解释；有效草稿可成为VALIDATED，仍不能submit/approve/publish。仿真使用既有 `:simulate`，返回不同的类型化ReferralSimulation（simulationOnly=true，authority=SIMULATED_INPUT），仅含资格/原因/dueAt、有效人数输入和候选规则；旧价格Simulation JSON字段保持不变。

仿真facts保持Map<String,String>：boundAt、now、relationId、validCount、verified、newCustomerAtBind、firstValidOrder、qualifyingFactAt、qualifyingFactReceivedAt、settledAmountMinor、cumulativeRefundMinor、currency。模拟时点、观察期与金额全部来自输入/冻结计划，不读真实渠道；未知证据显式UNKNOWN/verified=false。仅计算当前候选集合，不提供已授权/已发放或持久去重证明。

| AC | 验证 |
|---|---|
| P01 | 同一共享parser，旧compiler签名/字节兼容，非法图和规则解释错误 |
| P02 | 注册/首单、观察期、退款失效、未知事实、双边阶梯同SPI输出 |
| P03 | 仿真不调用任何参与/奖励写入，旧价格返回不变 |
| P04 | submit/decide/stage/ACK/activate保持referral拒绝，真实SKU目录仍待确认 |

隔离后端源码快照离线选定纯测试；不全量、不启动服务/数据库、不提交/迁移、不改其他agent参与服务和前端。实际目录/权威证据、冻结运营口径、隐私及生产参数继续待确认。
