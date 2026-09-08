# Delivery Status

## Goal / State
原平台邀请有礼控制面完整规则校验与纯预览；实现及专项QA完成，独立最终审查已通过（仅本阶段）。完整裂变/原平台全量/生产未完成。

## Completed
- ReferralPlanCompiler 仅改 package 机械共享至 referral-runtime-spi；control与compiler直接复用，签名层仍在compiler。机械比较及旧真实payload SHA golden均通过。
- 规则校验复用同一parser/validator；有效草稿可标VALIDATED，但返回 `REFERRAL_CATALOG_UNVERIFIED` WARNING，明确真实SKU/权益目录未核验。
- 原 `:simulate` 返回受控 SimulationResult；旧价格字段不变，新referral结果只有模拟标识、资格/原因/dueAt、输入有效人数与双边/阶梯候选。
- JSON图原值类型在共享ReferralGraphInputGuard中检查；模拟facts在Object原类型检查后才转换；control重复key严格拒绝。旧合法字符串方言与旧价格行为保持。
- submit/decide/stage/ACK/activate/rollback均对referral保持拒绝；仿真无参与者/奖励/授权数据库写入。

## Verification
- 隔离快照 `snapshot-path.txt`；marketing-contracts使用前compiler阶段已通过基线，排除root并行新增award-contract。
- `mvn -o -pl services/rule-compiler-worker,services/marketing-control-service -am -Dtest=ReferralGraphInputGuardTest,CanonicalGraphHasherTest,ReferralPolicyTest,ReferralPlanCompilerTest,RuleCompilerServiceTest,ReferralControlGateTest,ReferralControlPreviewTest -Dsurefire.failIfNoSpecifiedTests=false test`：50项通过，2026-09-08 10:54:20，23.773秒。
- 补目录未核验响应标识后，仅control两类12项复跑：10:55:11通过，9.458秒；没有重复全量。
- `source-evidence.json`：16个本阶段文件SHA与快照一致，parserRelocationPackageOnly=true，所有50个唯一测试0失败/错误/跳过。
- 无服务/数据库/Docker启动、无提交/推送/迁移；既有前端及其他agent服务未修改。

## Changed Files
详见source-evidence.json（lowcode共享原始图guard、referral SPI parser及pom、control服务/入口/JSON配置/纯测试、compiler import/reader/兼容测试）。旧compiler application parser已随机械搬迁移至SPI，未保留第二套算法。

## Next Action / Remaining Gates
本阶段已获独立最终复核通过。下一阶段仍需真实目录版本引用合同、冻结运营条款、完整发布能力/ACK闭包及持久活动生命周期。隐私/保留方案、权威会员/订单/权益渠道及生产参数未确认；此处不会将模拟verified=true当真实证明。
