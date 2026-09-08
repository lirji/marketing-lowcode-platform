# Code Review Report

独立审查者为主任务Agent，生产代码作者为mapping_adapter Agent。审查范围仅新referral-service及模块注册。

| 问题 | 修复 |
| --- | --- |
| 权限referral.participate与平台归一格式不一致 | 改referral:participate，测试只授予该非*权限 |
| 身份Port没有完整业务绑定和可锁后验证时限 | 新增RequestBinding、Subject的iat/exp≤60s，事务外及新写入锁后校验 |
| 配置和来源同时换HMAC版本会绕开旧业务唯一键 | 新增持久租户索引版本锚点；缺失拒绝、只读FOR SHARE，无生产数据或轮换接口 |
| 已有参与者使用新幂等键时锁后未检查身份过期 | complete前检查；真实行锁等待后过期回滚新回执 |

只读Port作为未来可信适配入口，不以构造器格式校验证明密码学真实性。默认实现拒绝，生产默认OIDC、DEV headers=false，未配置数据源/认证就绪时不隐式进入开发模式。没有真实JWT/JWS、永久jti、已签收release接收器；这些缺口不能视为实现完成。

测试发现的3个缺陷均局限测试fixture（名字遮蔽、URL分隔符、AES key长度），修复后12项通过。最终新增门禁8项专项全部通过、退出0，最后独立复审结论由主任务记录。
