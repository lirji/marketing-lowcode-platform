# 永久准备与receipt恢复纯状态机结果

## 本切片

新增ReferralAwardPreparation不可变状态机与纯测试，无Spring bean、HTTP、Repository、SQL或外部调用。未修改原Drools入口/仓储/abandonEvaluation；原161基线仍保留。永久Identity由tenant、固定marketing-referral、稳定sourceRequest/reward、claims摘要、payload摘要和qualificationRevision构成，任何变化拒绝复用。

首次PREPARED只有可信应用层当前CENTER/风险ALLOW及短授权时间许可才能进入CONFIRMING。CONFIRMING必须先持久提交再调用远端；超时转CONFIRM_UNKNOWN，崩溃留CONFIRMING也只允许按原Identity查询/重放远端确认。lease takeover递增fence并保留原阶段和receipt；每次转换递增stateVersion。所有时间为raw Instant。

永久Receipt包括完整Identity、confirmationId、confirmedAt；来自未来受信适配器，record本身不完成验签。已有确认后原token过期仍可恢复，但本地受理仍检查当前lease、CENTER和风险许可。Receipt稳定内容不同会保留原记录并进入粘性quarantine，不能覆盖后继续受理。明确未确认拒绝保留Rejection证明并终止；确认后取消不能变成未确认，留后续补偿。ACCEPTED只表示本地意图准备耐久受理，并非权益履约成功；原引用回放不依赖过期token/lease，但需要调用层认证及完整Identity。

## 8项纯验证

2026-09-08 11:34:59 +08:00，独立快照离线命令：
`mvn -o -pl services/benefit-funding-service -am -Dtest=ReferralAwardPreparationTest -Dsurefire.failIfNoSpecifiedTests=false test`。

8项通过，失败/错误/跳过均0。包含成功/永久回放、未知/崩溃接管、远端确认本地状态丢失后同身份恢复、过期token但永久receipt恢复、旧fence/纳秒租约边界、冲突receipt隔离、完整身份变化、确认后拒绝不能抹receipt、CENTER/风险/首次授权边界。两个源码SHA与测试快照相同，日志/报告/source-evidence.json同目录。

## 剩余门禁

独立最终源码审查待确认。纯模型返回状态不等于写入成功；未来仓储必须唯一来源键+stateVersion/fence CAS，并将receipt/intent/Outbox/expected-fact同事务提交。先提交CONFIRMING再网络、网络超时恢复、数据库rollback与并发确认/取消尚未由真实数据库验证。受信receipt/风险/身份适配器、外部确认签收、生产参数/真实渠道/隐私仍待确认。没有Docker/DB/服务启动，也不重复全仓库测试。
