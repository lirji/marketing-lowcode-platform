# 邀请令牌内部切片

2026-09-08：用户授权持续推进完整裂变，主任务指派邀请令牌发行/解析切片；不含绑定、奖励、公开HTTP或部署。

依据baseline05 D04/D20、09 §参与归因流程、10 §9.1，采用256位安全随机opaque token。token身份只存SHA256摘要；明文仅经独立保护Port加密存短期重放回执。复用已有Scope、受信身份RequestBinding与固定索引锚点，校验本人participant。历史发布许可Port必须绑定原participant完整冻结版本，默认拒绝，不能从当前最新规则替换。

新增V2两张表（token永久身份、密文重放命令）与独立TokenService/Repository/Mapper。幂等键tenant+subject+key永久冲突约束，短期重放窗口须显式配置且≤24h，缺参数默认拒绝；过窗同key不再解密回显，返回需显式新请求错误，不删除原记录。token过期/撤销只停止解析，永久hash身份不删除。当前不开放撤销入口，revoked_at为后续受信撤销流程保留；测试夹具覆盖已撤销状态。

加密/解密及可信来源读取均事务外，拒绝外层事务。新发行锁序锚点共享读→token命令→participant；锁后复检身份、历史许可和token/重放截止，token/命令/审计同事务。重放返回原token，不重新发行；解密后再次检查窗口防止慢KMS越过回显期限。无自动清理Worker，不声称密文24h物理清理已完成。

解析要求机器scope权限，按tenant+token hash查永久身份并检查有效性，仅从可信摘要Port读取活动名、公开条款与masked inviter，返回最小视图。不解析出canonicalSubject、不授予资格、不建关系；可信摘要不可用默认拒绝，不硬编码展示数据。没有消息消费者或新外部事件合同。

验收：真实MySQL唯一键/并发同key、同key异内容、跨主体/Scope、锁后与加解密后过期、原固定版本、过窗拒绝、新key新token、审计失败原子回滚、已到期/撤销hash保留、表字段注释。密码学保护Port使用AES-GCM测试替身验证AAD隔离与篡改拒绝，真实KMS接入另验收。所有构建在独立临时后端快照，日志及报告落本目录。
