# QA Report

## Scope / Environment
本地隔离源码快照、Java21/Maven3.9.12、离线纯模块测试。只构建SPI及依赖，只选定两个纯测试类，不启动数据库或服务，不验证真实渠道。

## Cases
新 ReferralOrderEvidenceMergerTest 17项覆盖：
- 重试运输元数据变化、同revision当前/历史内容冲突、旧修订不回退累计金额/不补结算、更新完整快照固定结算receivedAt。
- 累计退款不重复相加、不降低、乱序收敛；较旧可信证据揭示更高退款时隔离。
- 来源未验、未来/逆序时间不抢占首次接收；六维scope逐一错配不污染当前状态。
- 在途退款不计作成功退款且保持pending；释放在途后恢复证据可评估。
- 映射/首单政策未知与newCustomer始终UNKNOWN；可评估订单输入仍不能自行得到新客资格。
- 金额不一致/范围/Long.MAX_VALUE、币种/政策/结算身份改变、取消与完全退款失败关闭。
- HistoryUnavailable阻断投影并保留水位；旧重放不清高水位；Seen错误scope与恢复、缺State不伪造首接收。
- 新可信非法修订quarantine，旧READY重放仍blocked；主体码点/Unicode/嵌套输出脱敏。

原 ReferralPolicyTest 13项回归一起通过。最终总30项0失败/错误/跳过；不是把初版26项与复跑相加。maven-history-review-retest.txt于2026-09-08 11:08:21 BUILD SUCCESS；source-evidence.json核对3文件SHA一致。

## Verdict / Not Covered
纯测试pass；独立最终复核待完成。无真实验签、来源授权、适配器映射、持久Inbox/资格原子性、生产参数或隐私准入证明。
