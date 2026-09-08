# 参与者固定版本内部切片

2026-09-08，用户已授权继续完整裂变及并行，主任务指派本独立后端切片。

仅新增referral-service及services模块注册，无前端、HTTP写入、token、绑定、奖励、消息投递或共享数据库迁移。实现内部加入活动：受信身份Port返回版本HMAC与密文，受信发布/kill-switch Port提供短期许可；两个来源默认拒绝。

复用TenantScope、Digests、MyBatis、Spring事务。核对平台PersistentIdempotentCommandExecutor后发现其7天淘汰并明文保存响应、键无主体作用域，无法直接满足此切片永久原成功/原键冲突不变量；不改公共执行器，新增最小永久join-command业务回执，只引用participant，不存敏感响应。现有Outbox均领域特定，无通用事务append组件；沿用同事务append模式新增referral outbox，不复制relay。

事务外解析受信身份/发布许可，内部拒绝外层事务。事务锁序索引版本锚点共享读→命令→主体角色→参与者；角色与参与者唯一键均为tenant+campaign+subject_key，跨规则版本永久唯一。首次加入固定Scope、definition/version/generation/artifact/policyHash/routeEpoch，同事务审计/Outbox；重复请求返回原参与版本，即使最新release不可用或kill-switch关闭。锁后重检许可有效期/活动窗口；默认来源与索引版本均关闭。固定索引版本同时受持久锚点约束（应用只读FOR SHARE）；缺锚点拒绝，不提供初始化或轮换生产参数，轮换需双索引/回填方案另行实施。

验收：AC01默认失败关闭；AC02 Scope/租户/主体隔离；AC03重放固定版本、同键异内容冲突；AC04多请求竞争只建1参与者/角色/审计/Outbox；AC05原子回滚；AC06锁后过期拒绝；AC07真实HMAC测试替身及SQL全注释。共享test-support允许外部数据库覆盖，故本模块使用不接受外部URL的专用MySQL 8.4.11容器fixture；只跑本模块定向测试，日志落本目录。

未确认：真实BFF/主体映射、KMS/HMAC来源及轮换、签名release/kill-switch部署、真实范围/生产参数、保留期。测试替身不等于来源签收。后续令牌/绑定/奖励及公开合同维持原baseline范围。

## 独立审查后的边界细化

权限沿用平台归一形式`referral:participate`，无通配权限测试。身份Port接收RequestBinding（tenant、campaign、organization、shop、operation、idempotencyKey、bodyDigest、method、path）并回显；Subject携带iat/exp，期限不大于60秒。业务bodyDigest以受限ASCII字段排序JSON生成，内部method/path明确为INTERNAL/referral.participation.join，不冒充C01 HTTP已签收；后续HTTP必须用真实请求method/path、双方规范JSON算法及持久jti注册接入。

事务外检查身份有效及完整绑定。首次参与或已有参与者的新幂等键必须在锁后重检身份有效期。已完成同幂等键只有原成功查询，可在请求入口认证有效但等待命令锁期间过期时回放；进入时已过期、身份来源不可用或Scope不符仍拒绝，重放不会授予新参与或更换原版本。

持久`mk_referral_subject_index_anchor`是新增第6表。只读既存锚点，无自动插入、更新或删除Mapper；不从部署配置推导初始数据库值。每次使用共享锁读取，不更新全租户热行。测试场景数据库中明确插入fixture锚点，不等于批准真实租户/密钥/生产参数。
