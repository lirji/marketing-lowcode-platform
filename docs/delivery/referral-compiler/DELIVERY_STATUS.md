# Delivery Status

## Goal / State
原平台第二切片：REFERRAL_POLICY 图降级为 REFERRAL_PLAN；实现、专项验证与独立最终审查完成（仅本编译阶段 pass）。整体裂变和生产状态仍未完成。

## Completed
- 五节点共享目录、REFERRAL_POLICY 纯方言、严格线性拓扑/字段/版本/字符串数值校验。
- 复用 ReferralPolicyValidator，保持归因与范围的 CompiledReferralPlan envelope。
- REFERRAL_PLAN ABI `marketing-referral-plan/1`，复用 ArtifactBundle、ArtifactAttestation；禁止 GRAPH 绕过、定义身份错配及失败保存。
- HTTP 先读取原始 JsonNode：裂变 config 必须原 JSON 字符串，数字/布尔/null/数组/对象拒绝；compiler JSON 解析器拒绝重复属性。
- 独立审查发现旧 hash 分隔符歧义，已为新方言修复为 UTF-8 字节长度前缀与集合计数，并拒绝非法 Unicode 代理项；旧方言字节摘要保持不变。
- 控制面仅追加节点发现；validate/submit/simulate/stage 明确拒绝未接入 referral，不能借旧默认分支获得审批、仿真成功或发布。

## Changed Files
- `runtime-spi/lowcode-language-core`：Dialect、GraphValidator、CanonicalGraphHasher、ReferralNodeDefinitions 及两组专项测试。
- `services/rule-compiler-worker`：pom、RuleCompilerService、ReferralPlanCompiler、ReferralCompileRequestReader、CompilerConfiguration、CompilerController、编译及旧格式签名测试。
- `services/marketing-control-service`：DefaultNodeRegistry 仅追加共享目录；ControlApplicationService、GraphSimulationService、ReleaseApplicationService 只增加未实现门禁；ReferralControlGateTest 纯测试。
- `docs/delivery/referral-compiler/`：本阶段方案、评审、QA、日志与证据。

## Verification
所有命令在 `snapshot-path.txt` 指向的隔离源码快照执行，未改共享 target；离线 `mvn -o -pl <相关模块> -am -Dtest=<选定类> -Dsurefire.failIfNoSpecifiedTests=false test`。没有全仓库测试、服务启动或数据库访问。具体结果见 QA_REPORT.md 与各 maven 日志。首次选定专项 22 项通过，随后仅对新增测试及审查修复定向复跑；最终汇总29个唯一测试全部通过，19个本切片源文件与隔离快照hash相同（source-evidence.json）。

## Decisions / Open Gates
`benefitDefinitionVersion`/`skuVersion` 是调用侧提供的不可变完整版本引用，不将单个数字或“latest”解释为权威 SKU，不在此解析中伪造目录签收；其真实引用形状/存在性/冻结授权须后续目录与发布 gate 验收。新客 scope 首期 NEW_CUSTOMER，真实渠道、业务 policyVersion、运营值、隐私/保留与生产参数继续待确认。

## Next Action
独立审查已确认 hash 与原 config 两项修复；enum ordinal/boolean/null防绕过补强已过11项专项，本阶段最终确认通过；下一切片在同源规则基础上接控制面完整校验/仿真/条款与发布能力闭包，服务侧不得直接把编译候选当发奖授权。


2026-09-08 独立最终复核：compiler本阶段 pass；enum旁路关闭、源文件SHA匹配。仅本编译切片完成，不表示整平台或生产通过。后续机械共享parser重定位在referral-control-preview阶段独立记录。
