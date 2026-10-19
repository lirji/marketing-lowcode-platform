# 20 架构适应度函数与持续验证

## 2026-10-19 更新

F01/F02/F03 类型部分/F14/F16/F17 已有局部代码验证；不将其等同全部 18 项通过，环境、合同和容量前置继续保留。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## 当前基线与计划性质

CONFIRMED：已有ArchUnit依赖/循环/SQL位置规则，且断言确实导入领域和应用类；不是“只有空规则”。[ArchitectureRulesTest.java:24](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/architecture-tests/src/test/java/com/acme/marketing/architecture/ArchitectureRulesTest.java#L24)。本轮没有执行整仓ArchUnit或新增测试文件；下面是要交给下一阶段的验收合同，不是18项已通过。

本轮临时Journey探针只证明A02反例，不能证明修复，也不能替代全链验收。前轮83前端、130限定后端测试/构建结果保持原范围；Lint失败未修。

|ID|不变量/量化判断|执行方法与触发时机|Owner /当前状态|
|---|---|---|---|
|F01|支持DSL的仿真/在线同输入同制品：金额/选项/分摊差异=0；不支持语义拒绝|黄金集+边界生成差分，compiler/IR变更必跑|Rule，A01未通过|
|F02|必需路由缺失/断连/非法循环发布前拒绝；合法等待可编译|Graph→compiler负例与纯runtime正例，节点变更|Journey，缺false反例已证实|
|F03|每种类型×operator×arity语义一致；错误身份零命中|类型矩阵+真实membership新增撤销/乱序/过期|Audience，合同与实现待补|
|F04|Campaign展示业务/desired/observed不混同|双方言部分发布/灰度/回滚API集成|Control，待实现|
|F05|运行路径不得读取Draft|既有依赖规则扩展+受控SQL访问验证；每次runtime变更|Runtime，静态局部确认，自动门禁待补|
|F06|已有V3实例发布V4后仍用V3；新START用批准当前版本|历史制品/激活+checkpoint恢复集成|Journey，源码确认，当前环境未测|
|F07|指令乱序不覆盖新sequence；rollback有审计；缺ACK不能激活|同slot并发/崩溃/回滚实验|Release，源码机制有，待实测|
|F08|每个副作用有稳定commandId，重试不变且同key异内容冲突|消费者DB成功/ACK失败重投与payload冲突|Action/Benefit，真实下游待证|
|F09|一个授权execution查询得到实际版本、关键拒绝和终态/UNKNOWN|三个故事：未命中、timer耗尽、外部超时|诊断视图，待补|
|F10|每计划寿命/等待/保留窗相容；累计状态受已签收预算约束|长时虚拟时钟+真checkpoint测试，改TTL/serializer必跑|Journey，预算UNKNOWN|
|F11|慢DB/冷启动不突破连接与租户预算，kill陈旧有明确上限|隔离故障注入与偏斜负载；目标数值必须先签收|Runtime，待测|
|F12|恢复无无法解释丢失，重复业务效果=0|15章窗口注入、ledger/receipt/外部对账|域Owner，待运行|
|F13|实际恢复时间/可恢复数据点满足签收RTO/RPO|独立目标备份/checkpoint恢复演练，不覆盖共享数据|运维/域Owner，目标与实测UNKNOWN|
|F14|受支持图read-edit-write不丢版本、variables、语义hash相关字段|前端旧fixture往返+服务器兼容reader，新writer前|Low-code，A09未通过|
|F15|新增纯operator不改核心分派；新增节点不改既有executor|扩展fixture+变更差异审查+旧实例回放；registry落地后|Engine，目标不是当前事实|
|F16|跨域Repository/写入/Entity泄漏为0或有明确受控例外|复用ArchUnit，Owner映射+jobs SQL覆盖+负例夹具|架构，当前SCOPED，未全审计|
|F17|模块依赖无领域循环，Shared Kernel不含可变业务实体|现有slice依赖规则+合同边界检查，跨模块变更|架构，规则存在，本轮未重跑|
|F18|裂变未满足权威READY/终态验收保持拒绝|配置负例、身份/资格/奖励未知/取消/对账真实sandbox|Referral，保留拒绝；接线待证|

## Gate组合

PR gate按改动选相关函数与受影响业务测试，不能全部只跑Mock；金额/租约/唯一键需真实DB集成，Flink需checkpoint与sink恢复，外部幂等需受控真实协议。Nightly/隔离演练覆盖长时状态和故障；发布gate核对制品、ABI、兼容及生效观测。性能目标未定时返回UNKNOWN/HOLD，不能让“没有指标”自动PASS。

为适应度加入负例夹具证明规则确实能失败，禁止拿覆盖率数字替代正确性。新增F15必须有真实扩展变化点，不为了未来可能性建立庞大插件测试平台。例外要记录Owner、原因、范围和复评条件，不能通过忽略包让统计变绿。

## 继承证据

- [上一阶段 14-observability-recovery](../marketing-platform-analysis/14-observability-recovery.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 16-architecture-evolution](../marketing-platform-analysis/16-architecture-evolution.md)：作为发现输入；本章新增判断以定向源码复核为准。
