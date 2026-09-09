# 活动类型合同

POST `/api/v1/campaigns` 新增可选campaignType：STANDARD或REFERRAL；缺省/null按STANDARD兼容。未知枚举返回400。返回CampaignView新增campaignType。GET同路径可选`?campaignType=REFERRAL`，由SQL过滤，仍按认证租户/组织/门店范围隔离。

STANDARD与旧四字段请求使用完全相同的幂等载荷编码；旧响应缺类型补STANDARD，重试保留原id。相同幂等键改为REFERRAL返回冲突。类型创建后固定，未新增改类型接口。

控制面V4迁移为历史行补STANDARD，不依据名称或已有定义猜测分类。已有历史裂变定义若需归类需明确迁移映射，不从前端备注绕过。类型只是分类，REFERRAL仍受现有治理/发布拒绝门禁约束；本次不宣称ACK/激活可用，也没有新增或更改定义方言匹配政策。

验证：ControlFlowIntegrationTest新增真实HTTP/MySQL场景覆盖创建、数据库保存、筛选、旧请求hash、旧回执、换类型冲突、未知枚举和跨租户列表。前端由Cursor接入，当前未部署。
