# 控制面裂变校验与纯预览合同

`POST /api/v1/definitions/{definitionId}/versions/{version}:validate` 使用与compiler一致的共享parser/validator。成功的 `valid=true` 仅代表规则语义合法；同时包含WARNING `REFERRAL_CATALOG_UNVERIFIED`，真实SKU/权益目录仍待验证，submit/approve/release关闭。

`POST /api/v1/definitions/{definitionId}/versions/{version}:simulate` 仍要求既有 `definition:simulate` 权限与租户/组织/店铺范围。referral facts严格采用下述完整字符串字段集；数值JSON、boolean、null、数组/对象不是字符串，拒绝；重复key拒绝。所有值仅为模拟输入，不读取真实渠道。以下是测试示例，不是生产默认参数：

```json
{
  "boundAt":"2026-09-01T00:00:10Z",
  "now":"2026-09-01T00:00:30Z",
  "relationId":"relation-fixture",
  "validCount":"5",
  "verified":"true",
  "newCustomerAtBind":"YES",
  "firstValidOrder":"YES",
  "qualifyingFactAt":"2026-09-01T00:00:20Z",
  "qualifyingFactReceivedAt":"2026-09-01T00:00:20Z",
  "settledAmountMinor":"100",
  "cumulativeRefundMinor":"0",
  "currency":"CNY"
}
```

newCustomerAtBind/firstValidOrder为YES/NO/UNKNOWN；verified为字符串true/false。qualifyingFactAt/qualifyingFactReceivedAt允许空字符串显式表示尚无事实；未知证据须相应verified=false或UNKNOWN。金额允许负数模拟非法证据，evaluator会返回相应pending原因；不会将非法输入当通过。validCount必须是该假设变化后的非负当前有效人数；模拟不会自行累加人数，也不能替代数据库计数/去重。

referral返回 `simulationOnly=true`、`evidenceAuthority=SIMULATED_INPUT`、`qualification:{state,reason,dueAt}`、`validCount`、`rewardCandidates`。dueAt仅观察期未成熟时提供；候选含rule/milestoneKey，不含授权receipt或履约成功。逐人候选仅覆盖传入relation；阶梯候选是当前人数满足的集合，不是应重复发送的命令。

旧方言仿真响应仍为subtotalMinor/discountMinor/payableMinor/trace四个字段，合法既有行为保持。新结果不包含占位价格字段。
