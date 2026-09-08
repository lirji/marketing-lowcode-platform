# Delivery Status

## State
本纯累计证据合并切片实现与专项测试完成，独立修复终审待确认；完整活动/数据库原子性/真实适配合同与生产未完成。

## Completed
- ReferralOrderEvidence定义六维Scope、稳定Snapshot、独立Observation元数据、显式HistoryLookup与只保存latest/锚/阻断水位的State。
- ReferralOrderEvidenceMerger单调合并、同revision当前/历史内容冲突、累计退款不加总不下降、退款先到保持pending、可信首接收/结算接收锚固定。
- pendingRefund/映射/首单口径未确认不投影为可信可评估；订单投影newCustomer始终UNKNOWN。
- HistoryUnavailable可恢复pending水位、可信同scope较新非法事实永久quarantine、旧重放无法重新READY。
- 规范主体256码点/Unicode有效性检查、精确身份、嵌套record输出Scope脱敏。

## Verification
隔离快照见snapshot-path.txt，基于前阶段通过源码，不混入其他agent的服务/合同修改。命令：
`mvn -o -pl runtime-spi/referral-runtime-spi -am -Dtest=ReferralOrderEvidenceMergerTest,ReferralPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test`。

初版26项通过，但独立审查指出失败关闭缺口，因此不是最终证据。修复历史lookup/quarantine/隐私后于2026-09-08 11:08:21通过30项（新merger17+原Evaluator13），0失败/错误/跳过，4.004秒。日志maven-history-review-retest.txt；source-evidence.json三个文件SHA与快照一致。未运行全平台全量、数据库/服务或迁移。

## Next / Gates
独立复核最终源码及30项证据。后续服务必须在同一数据库短事务提供真实history lookup并原子保存Inbox/历史/返回State/资格；不能靠内存State声称持久幂等。实际交易paidAt/completedAt到SETTLED、memberId到canonicalSubject、首单政策及netEligible口径均需要适配器确认；本切片不自造映射。隐私/保留及真实生产参数继续待确认。
