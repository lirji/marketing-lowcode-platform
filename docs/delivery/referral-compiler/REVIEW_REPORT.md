# Code Review Report

## Scope
本切片语言核心、编译 worker、控制面最小失败关闭门禁；不含其他 agent 的 referral-service 或用户前端。主 agent 转达独立审查发现，下表保留失败场景和修复依据。

| 严重度 | 场景 | 修复/证据 |
|---|---|---|
| 高 | 原 CanonicalGraphHasher 以 `key=value;` 拼接，合法规则 `ruleId=a;skuVersion=b,skuVersion=c` 与 `ruleId=a,skuVersion=b;skuVersion=c` 可同源 hash 对应不同语义 | REFERRAL_POLICY 专用 `marketing-referral-graph-semantic/1` 编码，UTF-8字节长度前缀/集合计数/固定字段顺序；反例完整图、不同sourceDigest、签名验证、重排测试 |
| 中 | 原始 JSON quantity 数字1在 Map<String,String> 绑定时转为字符串，绕过原 JSON 类型要求 | CompilerController 先以 JsonNode 交给 ReferralCompileRequestReader；referral 配置原值非字符串拒绝，旧 GRAPH 数字转换兼容测试 |
| 中（自审） | 两个同名 role/ruleId 在 JSON→Map 阶段静默覆盖 | compilerStrictJson 开启 STRICT_DUPLICATE_DETECTION，原始重复 role 拒绝测试 |
| 中（自审） | 注册新方言后旧仿真默认分支可返回 visited，旧发布未知 runtime 缺少 dedicated gate | 控制面明确拒绝新方言 validate/submit/simulate/stage；纯门禁测试证明无批准状态/发布操作 |
| 中（自审） | UTF-8默认替换孤立代理项可令不同 Java 字符串同编码 | 新方言摘要拒绝非法代理项，保留字符串精确身份；Unicode测试 |

补强：原 JSON format/dialect 必须字符串，拒绝 ordinal/boolean/null，防止 Jackson enum 强制转换绕过 referral 分支。保留旧方言合法字符串请求及既有 GRAPH 数字配置转换行为。独立审查经主 agent 确认 hash/原 config 两项源码关闭；enum 补强 11 项测试通过，独立最终复核已通过。

## Residual Risks
独立最终审查已确认所有本切片修复关闭。SPI 候选、编译签名都不能代表实际发奖授权。目录真实 SKU/benefit 版本引用、图仿真/冻结条款/ABI能力/runtime ACK与激活尚未交付，发布路径关闭。旧方言保持旧摘要协议，本轮没有迁移其历史产物或宣称修复其摘要歧义。

## Verdict
pass：本编译切片独立最终审查通过；不是整平台或生产验收。

## 既有风险：旧方言摘要协议
旧方言 CanonicalGraphHasher 为历史签名兼容继续使用未转义分隔符编码。本次只修复 REFERRAL_POLICY 新方言，不能声称旧方言碰撞/歧义已解决。旧方言升级需要独立的摘要协议版本、数据/产物迁移、审批引用和签名兼容方案；本轮不重写旧摘要、产物或批准记录。本阶段通过范围仅是新方言编码无歧义以及未交付发布路径保持关闭。


2026-09-08 独立最终复核：compiler本阶段 pass；enum旁路关闭、源文件SHA匹配。仅本编译切片完成，不表示整平台或生产通过。后续机械共享parser重定位在referral-control-preview阶段独立记录。
