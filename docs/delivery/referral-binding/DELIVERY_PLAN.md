# 首次有效绑定内部切片计划

2026-09-08。用户授权持续完整裂变；主任务确认邀请令牌阶段29源文件SHA、初轮15项及新增1项均独立复核通过，允许按本计划继续。前置审查见PREIMPLEMENTATION_REVIEW.md，参与/令牌既有证据保持历史封存。

## Scope与安全缺省

只实现内部ReferralBindingService及持久首绑，不含HTTP、资格成功、奖励、真实BFF/JWKS/KMS接线。复用现有TenantScope、TrustedSubject RequestBinding、HMAC版本锚点、participant冻结规则、token永久摘要及subject_role。分享token可用于多个好友，不添加token单次消费标记。绑定状态为BOUND并明确资格待评估，不调用会员/订单/风控取得成功值。

maxBindAgeSeconds起点未签收。新增历史BindingPermit Port必须显式返回权威条款已确定的absolute bindUntil、活动startsAt/endsAt、settlementEndsAt、qualificationWindowSeconds、consentVersion/consentHash、完整原Participant和短期许可iat/exp。缺绝对截止、历史规则、条款证明或运行许可均默认拒绝；不在服务中推导token.createdAt或participant.createdAt年龄。测试称fixture截止，不当成业务签收。

## 输入与固定内容

Bind输入为opaque inviteToken、consentVersion、consentHash、用户assertion、idempotencyKey、traceId。token只派生完整SHA256身份，断言只经事务外受信Port，不落库/日志。业务摘要包含tokenHash、原participant/campaign/org/shop、条款版本/摘要、好友HMAC/版本和固定operation，不包括刷新断言、随机密文或trace。RequestBinding显式使用INTERNAL/referral.binding.bind；未来HTTP需实际method/path及永久jti接入。

首次创建ReferralRelation固定tenant、campaign、organization/shop、participant、invitee HMAC/密文/两种key引用、原tokenId、definition/version/generation/artifact/policyHash、boundAt、qualifyDeadline、同意条款与状态。qualifyDeadline=min(boundAt+qualificationWindowSeconds,settlementEndsAt)，处理时间溢出并要求仍有可评估窗口。

## 永久性与回放

V3新增mk_referral_relation，唯一(tenant,campaign,invitee_key)不含规则版本；新增mk_referral_bind_command，唯一(tenant,invitee_key,idempotencyKey)并保存完整请求摘要及原relation引用。原成功同key保持原响应，不因token过期/撤销、活动升级或许可不可用重新绑定；入口仍必须有效认证和Scope允许。

新key命中已有关系：不同participant为REFERRAL_ALREADY_BOUND；同participant仅允许原token及同一条款版本/摘要一致，不默默换token或同意凭据。即使返回已有关系，新回执也需锁后身份/token/范围/条款/许可校验。后续资格拒绝/退款/风控不释放归因或角色。

## 锁序及原子性

事务外：拒绝外层事务→机器权限referral:participate→按tenant+tokenHash非锁定位原participant/Scope→受信Subject及RequestBinding→历史BindingPermit读取。任何外部/KMS调用均不进事务。

事务内锁序：HMAC锚点FOR SHARE→bind_command→两个主体角色按完整HMAC字典序→participant→token→relation。

邀请人角色只能读取已有INVITER及原participant，不自动创建邀请人；好友角色仅以INSERT...ON DUPLICATE KEY no-op建立INVITEE并锁读，既有INVITER不能转换。自邀在角色写入前拒绝。两个角色获取按同一排序，避免相向申请死锁。锁后重读participant/token身份、有效期/撤销及当前时间，核对历史许可/条款与所有期限后创建relation、关联invitee role、审计、内部Outbox和完成command；任一步失败整体回滚，无角色空占位或成功回执。

TokenBindingReadPort由现有token持久化实现提供非锁定位及事务内锁读，只读同一张V2表；锁读明确要求已在本地事务，不复制token存储或重新解析为其他凭证。现有TokenService锁序命令→participant→新token写与本切片相容，不持token后再等participant。

## 文件与实现顺序

1. 新domain/ReferralRelation.java；application/ReferralBindingPermitPort.java、ReferralBindingRepository.java、ReferralBindingService.java、TokenBindingReadPort.java；独立默认拒绝BindingConfiguration。
2. 新MybatisReferralBindingRepository、ReferralBindingMapper/XML，V3__referral_binding.sql（两表及每字段全中文注释）；不修改V1/V2迁移。
3. token既有MybatisRepository/Mapper/XML只增加只读绑定定位/锁读端口，保留原令牌服务行为。角色排序锁用绑定专用Mapper，不修改join逻辑。
4. 先纯应用/时点/异常/摘要测试；代码齐备先独立审查，再一次隔离MySQL组合验证DDL/真实锁/竞争/回滚。后续纯时间或异常修复优先纯测试，避免重启DB。

## 验证矩阵

- 同key同请求并发一关系/审计/Outbox；同key不同token/条款/Scope冲突。
- A与C争B只有一位永久邀请人；分享同token可绑定B与D各一关系。
- 自邀、INVITER当好友、A→B/B→A角色互斥；排序锁与原join/token路径无倒序。
- 历史版本/条款固定；startsAt接受、endsAt/bindUntil拒绝；qualifyDeadline裁剪及溢出拒绝。
- 同key历史成功在过期/撤销/许可关闭后回放；新key回执必须锁后身份和token有效，原token/条款一致。
- 真实锁等待跨身份/token/许可截止回滚；审计Outbox后异常整体回滚。
- HMAC锚点缺失/版本改变、来源不可用、错绑定、跨主体/tenant/Scope拒绝。
- 默认状态不授予新客/首单/奖励，未确认maxBindAge通过绝对截止Port隔离。

只在独立临时后端快照执行定向测试，不操作共享target/数据库，不启动常驻服务、删除数据、提交、推送或改前端。真实maxBindAge起点、隐私留存/清理、BFF/发布/KMS、生产参数继续未确认。
