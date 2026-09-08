# Delivery Status

## Goal

原平台referral-service内部加入活动及永久固定版本切片，真实来源未签收时失败关闭。

## State

2026-09-08，实现完成，独立审查两轮反馈均已落实；最终8项受影响MySQL专项全部通过、退出0。用户工作树改动保留，未提交、推送、运行共享迁移或常驻服务。

## Completed

- 只新增services/referral-service（主类、默认OIDC配置、领域快照、受信Port、服务、仓储、Mapper/XML、带全中文注释的V1、隔离MySQL测试），services/pom.xml追加模块。
- 复用TenantScope、Digests、Spring/MyBatis事务。未复用会7天淘汰/无主体作用域的公共明文幂等执行器；新增永久join业务引用。Outbox复用原平台同事务append模式，无现成通用Outbox可注入，未复制relay。
- V1共6表：participant、subject_role、join_command、audit、outbox、subject_index_anchor。应用无主体原文、无HTTP写入、无自动锚点初始化或索引轮换。
- 先前12项隔离MySQL测试全通过，evidence/pre-anchor-12；最终代码新增持久索引锚点与新key锁后身份时效门禁后，8项专项全部通过、退出0。

## Verification Log

| 日志 | 结果/说明 |
| --- | --- |
| build-focused.log | 测试编译失败：测试参数org遮蔽全限定包名，已改显式import |
| build-focused-retest.log | 测试容器JDBC URL缺问号，已修正；无共享库访问 |
| build-boundary-retest.log | 测试AES key误多拼1字符导致17字节，已修正为16字节测试key |
| build-integration-retest.log | 12项MySQL全部通过；封存evidence/pre-anchor-12 |
| build-anchor-retest.log | 持久锚点/身份时效审查修复后8项受影响专项全部通过、退出0，evidence/final-anchor-8 |

所有Maven命令`-o -pl services/referral-service -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test`在build-workspace.txt记录的临时后端源码快照执行，无共享target竞争。未运行原平台全量，未安装产物到本地Maven库。

## Next Action

8项专项已完成并封存evidence/final-anchor-8；source-digests.json核对当前16个源码/配置文件与被测快照一致，等待主任务最终独立复审后汇总。本切片后续真实BFF/权威身份/KMS、签名release/kill-switch、持久jti、token/绑定/奖励、外部Outbox消费者与生产参数仍待后续实施/确认。
