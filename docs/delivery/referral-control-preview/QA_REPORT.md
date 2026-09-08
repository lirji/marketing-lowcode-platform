# QA Report

## Environment And Scope
本地Java21/Maven3.9.12离线隔离快照；只选定纯测试，无服务器或数据库启动。合同依赖固定前阶段已通过baseline，未混入root并行award-contract。不是当前整平台全量结果。

| 类 | 数量 | 证据与断言 |
|---|---:|---|
| CanonicalGraphHasherTest | 3 | 旧hash精确golden、新hash分隔符/Unicode边界 |
| ReferralGraphInputGuardTest | 2 | string合同、number/boolean/null/object/array与enum ordinal拒绝、旧值转换兼容 |
| ReferralPolicyTest | 13 | 先注册后绑定、权威未知、净退款、窗口/观察期、双边阶梯原规则回归 |
| ReferralPlanCompilerTest | 12 | 原编译边界+共享parser payload与旧真实class输出字节一致 |
| RuleCompilerServiceTest | 8 | 旧格式及新格式签名/版本作用域兼容 |
| ReferralControlGateTest | 3 | 非法图不可VALIDATED，submit/stage关闭，旧价格入口保持 |
| ReferralControlPreviewTest | 9 | VALIDATED语义与目录WARNING、审批拒绝、模拟双边/阶梯/注册/观察/退款/未知、无业务写入、原JSON类型、旧JSON四字段、ACK/激活/回滚拒绝 |

总计50项0失败/错误/跳过。首轮maven-targeted.txt于10:54:20通过；目录WARNING补强后只重跑control12项，maven-catalog-marker-retest.txt于10:55:11通过。源码映射source-evidence.json逐文件与快照相同，旧parser只有package变更；previous-parser-payload.txt来自前阶段已通过class实际探测，checksum在新编译专项golden断言。

## Limitations
没有真实SKU存在性核验、上游权威合同联调、生产参数/隐私确认、控制面审批发布/ACK能力闭包或最终发奖验收。CI沿用现有reactor，未运行远程CI；未宣称相关外部项通过。

## Verdict
本阶段纯测试pass；独立最终审查已通过（仅本阶段）。
