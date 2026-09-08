# Delivery Status

2026-09-08。内部邀请令牌发行/解析实现完成。初轮15项通过：12项隔离MySQL令牌用例、2项纯密码学保护用例、1项旧V1表注释兼容用例。独立审查补最后一次token数据库读取后重新检查摘要时限，1项定向复验已通过、退出0。

## 已修改

新增ReferralInviteTokenService、ReferralInviteRepository、历史许可/保护/摘要3个Port、默认拒绝配置、MyBatisRepository/Mapper/XML、V2两表及令牌/密码学测试。application.yml显式invite-replay-seconds=0；旧参与schema测试把6表断言限定V1表名，允许新增V2，不改变V1生产行为。

## 验证

`mvn -o --batch-mode -pl services/referral-service -am -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false test`只在build-workspace.txt指向的临时后端源码快照执行，没有共享target竞争或Maven install。build-focused.log及evidence/initial-15保存15项通过结果；build-summary-expiry-retest.log为仅新摘要超时用例。未重复参与全套或整个平台全量。

两个迁移只作用于不可配置外部URL的临时MySQL 8.4.11容器；原平台共享库未执行V1/V2。没有常驻服务、HTTP写入、发布topic、relay、删除、清理Worker、提交或推送。

## 下一步

收取摘要超时专项，封存报告/源码指纹，主任务完成最终独立复审后衔接首绑切片。真实BFF/KMS/历史发布/摘要来源、隐私物理保留与清理、maxBindAge起点及生产参数保持未确认，不阻塞默认失败关闭的内部实现。

最终证据：evidence/summary-expiry-1仅收集本次新增用例报告；evidence/source-digests.json核对29个源码/配置文件与最终被测快照一致。两次构建句柄均退出0，无运行任务。单测容器启动耗时较高，后续无DB逻辑优先纯应用测试，真实DDL/并发/回滚仍保留隔离MySQL。
