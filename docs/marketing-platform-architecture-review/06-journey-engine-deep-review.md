# 06 Journey Runtime、图治理与长时等待

## 2026-10-19 更新

必需路由、悬空/重复边及孤立节点在编译前拒绝；数据库异常走恢复，生命周期预算与复杂工作流仍有边界。

当前源码核对基线 `6b9092f`；[完整状态与证据](../marketing-platform-analysis/CURRENT_STATUS.md)。以下保留原评审基线的发现、严重度和测试结果；其中“当前/待实施”均指原评审当时。历史源码链接已固定到原提交，后续实现请从最新状态索引进入。

## 历史报告正文（1392ca9 基线）

评审日期：2026-09-22；源码基线：`1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c` 加现有工作区。模式：`READ_ONLY_ARCHITECTURE_REVIEW`。

`CONFIRMED` 为直接源码或明确测试证据，`INFERRED` 为推断和建议，`UNKNOWN` 为待补证据。所有目标模型、任务与验收均为规划，不表示已经实现或授权执行。

## Runtime定位与隔离

CONFIRMED：Journey是事件驱动的单游标状态机，包含Task编排语义；不是通用并行DAG调度器、完整BPMN或单纯定时器。Definition graph经JourneyCompiler变为JourneyPlan；Enrollment保存journeyId/version/currentNode/variables/去重。运行中固定计划，新START查当前激活，老实例可读历史激活；不能编辑草稿迁移既有实例。

Node模型已将UI目录、编译节点和Enrollment状态分离，但JourneyNode.config仍是map、执行集中switch；不存在每个节点各自稳定NodeExecutor插件的完整体系。[JourneyCompiler.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L1)、[JourneyRuntime.java:38](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L38)、[JourneyNode.java:1](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyNode.java#L1)。

## A02：静态图验证未覆盖运行必需路由

- Problem：条件缺false出口依然通过校验和编译，执行时失败。
- Evidence：CONFIRMED，[GraphValidator.java:48](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/lowcode-language-core/src/main/java/com/acme/marketing/lowcode/validation/GraphValidator.java#L48) 只校验已存在边；[JourneyCompiler.java:13](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L13) 不检查必需输出；[JourneyNode.java:17](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyNode.java#L17) 运行时才抛错。2026-09-22临时内存探针实际得到 `validationIssues=[]; compile=SUCCESS; runtime=missing route false from node c`。
- Impact：INFERRED，合法提交/编译不保证可执行；命中缺分支的实例进入错误处理，可能遗失本次推进机会；未进行生产发布或真实发奖。
- Root Cause：通用图结构校验与方言执行前置条件分开且后者不完整。
- Current Workaround：运行时异常、DLQ、有限timer重试；它们是止损，不替代发布前拒绝。
- Recommended Direction：T03按节点spec声明required routes；从唯一trigger检查可达，明确END/等待终结语义，编译时拒绝缺口，输出nodeId/pointer错误。
- Trade-off：旧设计可能无法重新发布；保留旧ABI运行及诊断，不能静默改存量图。
- Priority：P1，发布前正确性门禁；需要批准支持的图子集。

## DAG Governance矩阵

|检查|现状|补强|
|---|---|---|
|Cycle detection|DFS拒绝普通环；bounded-repeat判定存在|重复节点未注册/类型映射不通，当前先拒绝；将来校验真实循环区间与deadline|
|Topology/ports|节点/边ID、悬挂边、端口类型、成本|参数schema与路由基数|
|Reachability/Dead node|从所有零入度节点遍历|不能证明全部从唯一业务trigger可达；要以trigger为起点|
|Missing Start|通用校验NO_ENTRY，Journey compiler恰一trigger|保留并增加语义起点校验|
|Missing End|JourneyPlan只校验start及运行限额|按方言要求所有有限路径可终结；允许无限等待要显式寿命|
|Branch completeness|仅校验存在边的合法性|缺true/false/matched/elapsed/next在编译时拒绝|
|Join semantics|单currentNode，无并发token/join|不把多个入边误作并行汇聚；先明确禁止或支持择一路汇入|

## Timer：分钟至数周

CONFIRMED：STREAM使用Flink processing-time timer与ValueState/checkpoint，**不是Redis ZSet、DB轮询、Delay MQ或外置Scheduler**。DIRECT timer表不证明生产调度；OIDC要求STREAM。默认stateTtl 30天，resolver拒绝超过365天；delay是否早于实例到期目前不能仅凭正TTL推定。

[JourneyRuntime.java:51](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L51)、[JourneyRuntimeProcessFunction.java:56](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyRuntimeProcessFunction.java#L56)、[JourneyCompiler.java:44](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyCompiler.java#L44)、[JdbcJourneyPlanResolver.java:96](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JdbcJourneyPlanResolver.java#L96)。

|等待/故障|当前语义|验证与改造|
|---|---|---|
|分钟/小时|事件occurredAt+delay，过去到期钳制processingNow+1|验证事件迟到、积压与计时误差|
|天/数周|理论可表达，但受TTL、历史制品、checkpoint保留限制|发布校验delay+恢复余量小于有效寿命；不承诺数周SLA|
|机器重启|checkpoint恢复设计已接入|隔离重启、停机超过due、到期与timer同时触发|
|重复唤醒|activeTimer校验+稳定effect key+下游幂等|checkpoint回退重放不得重复业务效果|
|状态迁移/扩容|需兼容serializer/key group/UID与checkpoint|Flink扩容与Kafka增分区是两件事，后者还受投影分区身份约束|
|时钟漂移|processing-time并非业务日历时区服务|验证时钟前后跳；业务节假日规则另定义|
|失败重试|timer最多5次指数退避；暂停每5秒直到expiry|耗尽人工处置与恢复权限，不新增身份重发|
|大量任务|每实例timer+expiry与去重数据持久|测活跃数/状态字节/checkpoint耗时/timer lag|

## A03：活跃生命周期与恢复保留窗口未统一

- Problem：maxSteps只限制一次advance，信号ID集合可持续增长；输入续TTL但业务等待/历史版本保留各自配置。
- Evidence：CONFIRMED，[JourneyRuntime.java:23](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/runtime-spi/journey-runtime-spi/src/main/java/com/acme/marketing/journey/JourneyRuntime.java#L23)、[JourneyJobReducer.java:46](https://github.com/lirji/marketing-lowcode-platform/blob/1392ca97bfe9cc6cfcd67888d318e0b2b66efe9c/jobs/journey-runtime-flink-job/src/main/java/com/acme/marketing/jobs/journey/JourneyJobReducer.java#L46)；容量事故UNKNOWN。
- Impact：INFERRED，checkpoint变大、恢复变慢；等待超过TTL先过期，不等于准时发送。
- Root Cause：每次调用预算与跨月执行预算是不同维度，缺业务生命周期合同。
- Current Workaround：步数、迭代与TTL上限，有界timer重试。
- Recommended Direction：T08建立绝对/闲置寿命、最大信号数、去重重放窗和存量版本保留；超限明确拒绝/终结，不随意删除去重记录。
- Trade-off：限制超长玩法；压缩去重要证明不重发，Bloom类有误判不能直接做最终幂等权威。
- Priority：P1（寿命/恢复合同），容量优化P2待测。

## Workflow Build / Buy / Integrate

|候选|主要能力（官方来源）|对本仓收益与代价（INFERRED）|
|---|---|---|
|现有状态机+Flink Build|本仓状态、timer、checkpoint和事务sink|保留营销语义与已集成事件流；自己承担运维恢复/可视化/兼容|
|Flowable Integrate|BPMN任务、事件、timer|若人工审批/标准流程模型成为核心才评估；现有活动画布/运行状态需转换|
|Camunda Integrate/Buy|job worker执行任务和重试协议|标准编排及操作能力有价值；额外平台、worker合同/许可需专项核验|
|Temporal Integrate/Buy|持久Workflow、历史重放、Activity|跨日服务编排/恢复可能适合；要提供营销DSL解释器、确定性与历史兼容，Activity仍需幂等|
|LiteFlow Integrate|规则/组件编排|短流程组件组织候选；不能凭编排能力认定长等待持久恢复与本仓等价|

官方依据：[Flowable BPMN](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)、[Camunda workers](https://docs.camunda.io/docs/components/concepts/job-workers/)、[Temporal执行](https://docs.temporal.io/workflow-execution)、[Temporal Activity](https://docs.temporal.io/activity-definition)、[LiteFlow简介](https://liteflow.cc/pages/5816c5/)。本轮只核验能力，不验证候选版本/许可证/成本或做迁移PoC。

当前不换引擎。触发重评：已签收的并行join、人工长流程、跨实例协调或恢复运营需求持续超出现有模型，且代表性PoC证明迁移净收益。新旧实例隔离运行，禁止把在途Flink状态直接写成另一引擎表记录。

## 继承证据

- [上一阶段 07-journey-engine](../marketing-platform-analysis/07-journey-engine.md)：作为发现输入；本章新增判断以定向源码复核为准。
- [上一阶段 14-observability-recovery](../marketing-platform-analysis/14-observability-recovery.md)：作为发现输入；本章新增判断以定向源码复核为准。

## A02临时探针复核记录

2026-09-22在 `/tmp/marketing-architecture-review/` 编译运行；使用当前 lowcode model/validation 与 Journey顶层Java源码，依赖沿用仓库已编译的common/contracts等类。探针registry是与DefaultNodeRegistry对应的trigger/condition/end三种节点端口夹具，不启动Spring、不进入审批发布、不访问DB/MQ/真实渠道。缺false的负例与补false的正例使用同一其余配置，排除仅因fixture完全不可运行导致的误判。

实际输出：

```text
validationIssues=[]
compile=SUCCESS
runtime=missing route false from node c
completeBranchControl=COMPLETED
```

以下为可复核输入与断言；编译产物和临时源码不加入仓库，全文记录避免临时目录被清理后丢失复现条件。编译需Java21及对应模块classpath；它是机制反例，不是完整HTTP/数据库E2E。

```java
import com.acme.marketing.lowcode.model.*;
import com.acme.marketing.lowcode.validation.*;
import com.acme.marketing.journey.*;
import java.util.*; import java.time.*;
public class Probe {
 static NodeDefinition def(String id,Map<String,String> in,Map<String,String> out) {
 return new NodeDefinition(id,"1.0.0",Set.of(Dialect.JOURNEY_STATE_MACHINE),"java-runtime",in,out,"schema://x","schema://ui",NullSemantics.NO_MATCH,MissingSemantics.NO_MATCH,SideEffect.NONE,10,List.of(),"sha256:"+"0".repeat(64),Map.of(),Set.of("definition:write")); }
 public static void main(String[] a) {
 var registry=List.of(def("journey.trigger",Map.of(),Map.of("next","flow")),def("journey.condition",Map.of("in","flow"),Map.of("true","flow","false","flow")),def("journey.end",Map.of("in","flow"),Map.of()));
 var nodes=List.of(new GraphNode("t","journey.trigger","1.0.0",Map.of()),new GraphNode("c","journey.condition","1.0.0",Map.of("field","tier","equals","VIP")),new GraphNode("e","journey.end","1.0.0",Map.of()));
 var edges=List.of(new GraphEdge("a","t","next","c","in"),new GraphEdge("b","c","true","e","in"));
 var graph=new GraphDefinition("review-probe",Dialect.JOURNEY_STATE_MACHINE,"1.0.0",nodes,edges,Map.of(),Map.of());
 var issues=new GraphValidator(registry,GraphValidator.Limits.productionDefaults()).validate(graph);
 System.out.println("validationIssues="+issues);
 if(!issues.isEmpty())throw new AssertionError("unexpected validation rejection");
 var plan=new JourneyCompiler().compile(graph,1);System.out.println("compile=SUCCESS");
 try {new JourneyRuntime().advance(plan,EnrollmentSnapshot.start("t","e","s",plan,Instant.EPOCH),new JourneySignal.Start("signal",Instant.EPOCH));throw new AssertionError("expected missing route");}
 catch(IllegalStateException ex){if(!ex.getMessage().contains("missing route false"))throw ex;System.out.println("runtime="+ex.getMessage());}
 var completeEdges=new ArrayList<GraphEdge>(edges);completeEdges.add(new GraphEdge("c","c","false","e","in"));
 var completeGraph=new GraphDefinition("review-probe",Dialect.JOURNEY_STATE_MACHINE,"1.0.0",nodes,completeEdges,Map.of(),Map.of());
 var goodPlan=new JourneyCompiler().compile(completeGraph,1);
 var good=new JourneyRuntime().advance(goodPlan,EnrollmentSnapshot.start("t","e","s",goodPlan,Instant.EPOCH),new JourneySignal.Start("signal",Instant.EPOCH));
 if(good.snapshot().status()!=EnrollmentSnapshot.Status.COMPLETED)throw new AssertionError("positive control failed");
 System.out.println("completeBranchControl="+good.snapshot().status());
 }
}
```
