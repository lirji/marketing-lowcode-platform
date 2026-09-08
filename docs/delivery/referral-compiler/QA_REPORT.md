# QA Report

## Environment
Java 21.0.11 / Maven 3.9.12，本地离线隔离源码快照（snapshot-path.txt）；复制后端源码/POM，排除 target/.git/frontend/.env。无 Spring 服务、数据库、Docker、网络或真实渠道联调。依赖 reactor 只编译所选模块依赖，测试仅通过 `-Dtest` 选择纯类。

## Cases
| AC | 核心断言 | 测试 |
|---|---|---|
| C01 | 5 个纯节点、不泄漏旧方言、拒副作用、旧图校验仍有效 | ReferralNodeDefinitionsTest(2)、GraphValidatorTest(2) |
| C02 | 严格链路、分叉/环/孤立节点/错序、未知配置、非法角色、未填版本、数量/范围/时间、JSON重复键及原类型 | ReferralPlanCompilerTest(11) |
| C03 | 新hash分隔符/Unicode反例、旧hash固定值、图重排、完整envelope序列化回读、签名/身份/版本作用域 | CanonicalGraphHasherTest(3)、ReferralPlanCompilerTest |
| C04 | GRAPH绕过拒绝、身份不符/配置错误不保存产物、控制面未接入路径明确拒绝、旧仿真保留 | ReferralPlanCompilerTest、ReferralControlGateTest(3) |
| 旧兼容 | GRAPH/DRL/DMN/OFFER_POLICY/JOURNEY_PLAN 与新格式签名均验证 | RuleCompilerServiceTest(8) |

最终证据覆盖 29 个唯一测试用例；并非一次当前全量运行。最初22项过后，新增或修复只复跑受影响类，保留全部日志：maven-test.log、maven-duplicate-json-retest.log、maven-control-gate-retest.log、maven-artifact-retest.log、maven-review-retest.log、maven-unicode-retest.log、maven-enum-guard-retest.log。审查后 hash/compiler/旧签名 21 项专项通过；Unicode补强 hash/compiler 13 项专项通过；最终 enum 原类型补强 compiler 11 项于 2026-09-08 10:44:12 通过（0 failures/errors/skipped）。19 个本切片生产/测试/POM 文件与隔离快照逐文件 SHA-256 相同，汇总见 source-evidence.json。

## Blocked External Checks
真实目录/会员/订单渠道、隐私政策/保留、运营参数、完整控制面仿真/发布/ACK、持久幂等/额度/授权/履约/补偿以及生产门禁未覆盖。未运行远程 CI；现有根 reactor CI 通过模块依赖纳入本代码，不声称远程已绿。

## Verdict
本切片纯测试 pass；独立审查已确认 hash 与原 config 两项源码修复关闭，新增 enum 原类型补强已获独立最终确认，完整裂变与生产未完成。


2026-09-08 独立最终复核：compiler本阶段 pass；enum旁路关闭、源文件SHA匹配。仅本编译切片完成，不表示整平台或生产通过。后续机械共享parser重定位在referral-control-preview阶段独立记录。
