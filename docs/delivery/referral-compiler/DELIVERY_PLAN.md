# Referral compiler 第二切片

依据已批准 FINAL_PLAN §5/§9.2，继续端到端交付；主 agent 已确认允许 DefaultNodeRegistry 仅追加共享 5 节点注册。范围为语言核心 REFERRAL_POLICY、共享纯节点目录、严格线性图编译、REFERRAL_PLAN 格式与现有 canonical hash/ArtifactAttestation 链路。无发布激活、runtime ACK、持久服务或前端改动。

配置继续使用 Map<String,String>，不拓展其他方言配置协议。严格允许 start→bind→qualify→reward(1+)→end，拒绝未知字段/类型/版本/端口、循环、分叉、额外入口和脚本/webhook。所有运营数字必填，无生产默认值。奖励/资格业务校验只使用已有 ReferralPolicyValidator；归因字段与范围以不可变编译 envelope 保留，防止降级时丢失约束。

| AC | 验证 |
|---|---|
| C01 | 五类节点注册为 SideEffect.NONE；旧方言继续原校验 |
| C02 | 线性图及字段/类型/范围严格拒绝非法输入 |
| C03 | 确定性计划序列化、规则与图 hash、定义/版本作用域签名 |
| C04 | GRAPH 不能绕过 referral lowering；失败不保存 artifact |

专项运行低代码 GraphValidator/Hasher 和编译相关测试；不全仓库运行、不启服务。现有 CI reactor 覆盖相关模块，远程结果未验证。SKU存在性、真实权威来源、隐私/保留、运营参数、发布审批/runtime ABI能力匹配仍为后续生产门禁。

## 审查后补充
新增方言共享hash采用有版本域的UTF-8字节长度前缀与集合计数，旧方言摘要不变；HTTP原始JSON enum标识必须字符串，referral配置原值必须字符串，重复JSON字段在解析器层拒绝。控制面新增最小失败关闭门禁属于主agent追加授权，未开放新发布能力。
