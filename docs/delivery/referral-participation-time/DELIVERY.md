# 参与时间精度补强

## 问题与实现

V1加入流程原先在命令/角色锁后先将当前时间截为微秒，再校验权威身份、短期许可、活动截止。若截止在微秒内第500纳秒而实际时间已达第600纳秒，截断后的时间会错误地通过截止。现在使用完整Instant裁决三种边界，校验成功后仅将持久时间降为微秒。

原同键成功回放分支未改变；角色已存在但新键写回执的身份复检本来就使用完整Instant。令牌发行最终事务检查已经使用完整Instant，并检查已截断的实际token/replay截止，因此无需修改。Binding、配置、V1/V2 SQL均未修改。

## 独立专项证据

2026-09-08 11:19:22 +08:00，独立临时源码快照离线执行 `mvn -o -pl services/referral-service -am -Dtest=ReferralParticipationTimingTest -Dsurefire.failIfNoSpecifiedTests=false test`，3个JUnit方法通过，失败/错误/跳过均0。每方法覆盖subject/permit/campaign三种边界：截止后、精确截止、截止前。没有数据库和服务启动，没有全仓库测试。

源码与实际测试快照的2文件SHA一致，详见source-evidence.json；原始日志maven-pure.txt及Surefire报告同目录。原参与15场景是历史阶段证据，本次3方法为单独补强，不宣称历史SHA仍覆盖当前全部工作树，也不将此结果当生产验收。

## 审查及剩余门禁

独立终审已确认此时间补强阶段通过（3项专项与2源码SHA）；真实渠道、生产参数、权威许可发布、隐私确认及完整发布门禁继续保持待确认/关闭。此阶段只修复参与时间精度，不扩大现有功能授权。
