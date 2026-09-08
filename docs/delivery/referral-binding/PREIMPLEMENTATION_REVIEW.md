# 首次有效绑定：实施前独立审查

2026-09-08。只读核对交易任务冻结 baseline/10-FINAL_PLAN §9、§10～§12、baseline/09-PRODUCTION_DESIGN §6.2～§6.3，以及当前 referral-service 参与实现与 referral-invite-token/DELIVERY_PLAN.md。本文件只确定后端内部切片，不是 C01、发布、隐私或生产渠道签收；未运行测试、未迁移数据库。

## 结论与适用边界

可以继续实现默认关闭的内部绑定 Service/Port/Repository 与隔离 MySQL 测试。复用现有 participant 固定版本、subject_role、HMAC 版本锚点和可信 Subject.RequestBinding。不得把机器 JWT sub 当作用户主体，不开放 HTTP、奖励或资格成功入口。邀请令牌是可供多个好友绑定的分享凭证，不套用交易订单归因令牌的单次消费规则。

FIRST_VALID_BIND 是身份、令牌、时间、条款与角色检查后第一次被数据库接受的绑定；不是第一次成为新客、首单或风险通过的关系。绑定初始资格保持 PENDING，后台独立取得权威事实。后续资格失败、退款或风控拒绝不释放归因，不改绑其他邀请人。

## 最小持久模型

- 新增 `mk_referral_relation`：随机 relation_id；认证 tenant/campaign/organization/shop；participant_id；invitee HMAC、索引版本与独立密钥密文；token 身份引用；固定 definition/version/generation/artifact/policy_hash；bound_at、qualify_deadline；同意条款版本及内容摘要；资格状态与 row_version。唯一键 `(tenant,campaign,invitee_subject_key)` 不带规则版本。引用 participant 使用 tenant 复合外键。
- 新增 `mk_referral_bind_command`：唯一键 `(tenant,invitee_subject_key,idempotency_key)`；固定 operation、完整请求摘要、原 relation 引用。摘要包含令牌摘要、条款身份/摘要、实际业务范围与用户 HMAC/版本；不包括刷新后的 BFF 断言、随机密文或 trace。成功凭证永久，不依赖 JVM 响应缓存。
- 复用 `mk_referral_subject_role`：邀请人为 INVITER，好友为 INVITEE，并将好友 relation_id 同事务关联；角色不可转换。既有 participant_id 与新增 relation_id 的关联更新必须检查影响行数。后续补 FK/约束需避开循环建表依赖。
- 复用追加审计与事务 Outbox。事件不含原断言、原 token、主体明文或 HMAC；仅发布内部待评估事实，不命名为奖励达标。DDL 表及所有字段均有中文注释；迁移号在邀请令牌切片落盘后按下一空号确定，不修改其迁移。

## 事务及锁顺序

1. 拒绝外层事务。验证机器权限和 tenant/org/shop；事务外由受信 Subject Port 解析用户断言及精确 RequestBinding。事务外按 tenant+token hash 获取只读定位信息，取得原 participant 对应的已验证历史计划、条款及新绑定许可；不得改用最新发布版本。外部验签、KMS 和会员/订单查询不进入写事务。
2. 开启有界本地事务，先对租户 HMAC 索引锚点 `FOR SHARE`；缺失或版本不符拒绝，禁止自动初始化/轮换。
3. 锁定 bind_command（独立于 join/token command）。同键异摘要冲突；已成功同键只返回该主体自己的原关系，不重新选择版本或要求 token 尚有效。请求身份在事务外必须先合法验证；历史业务回放不等于允许未认证读取。
4. 已有令牌定位出的邀请人 HMAC 与好友 HMAC 必须不同。按两个 HMAC 的精确字典序依次创建/锁定主体角色行；检查 inviter 为 INVITER 且关联原 participant，invitee 不得为 INVITER。不同键竞争同一好友也会在角色键串行化，不能先分别查“没有关系”后再无锁插入。
5. 锁定原 participant，再锁定所用 token 行复检 hash、tenant、participant、有效期与撤销状态。锁序固定为锚点→本操作命令→排序角色→participant→token→relation；令牌撤销/后续相关写路径必须遵守相同相对顺序，禁止先持 token 再等 participant。不得调用嵌套 join/token 写服务以取得这些锁。
6. 查询好友已有关系。不同 participant 返回 `REFERRAL_ALREADY_BOUND`，保持原归因；相同 participant 不创建第二关系。新幂等键若产生新回执，仍须在锁后验证身份、token、范围、条款及绑定许可；是否允许换 token 作为同一关系的新键回放必须在合同中显式固定，不能默默丢弃摘要差异。保守实现可仅接受原 token/条款一致的新键。
7. 锁等待结束后重新读取可信时间，复检身份时限、历史许可时限、token 到期/撤销、participant 状态及活动绑定窗口。通过后在同一事务写 relation、关联 invitee 角色、审计、Outbox、完成 command。任一失败全部回滚，不残留角色占位、成功回执或事件。

实际后续 token 代码若已有锁序与第 5 步不同，必须先统一双方顺序并补并发回归；不能只在文档宣称一致。`SKIP LOCKED` 不用于归因或角色裁决。HMAC 锚点共享锁不承担活动许可或 kill-switch 版本的同步职责。

## 检查与测试矩阵

| 场景 | 必须行为/证据 |
|---|---|
| 相同主体、同键、同请求并发 | 一个 relation/审计/Outbox，所有成功响应同原关系 |
| 同键改 token、条款或范围 | 内容冲突，原成功不变 |
| A 与 C 同时邀请 B | tenant+campaign+B 唯一关系；一个获胜，另一方 ALREADY_BOUND |
| 自邀、A→B 与 B→A、好友已为邀请人 | 角色校验拒绝；排序锁无反向获取；不得自动转换角色 |
| 发布升级、旧 participant 链接 | 仍采用 participant 固定定义/代次/制品/条款；无旧制品证据则拒绝新绑定 |
| token/身份/许可等待锁后到期 | 新绑定及新回执拒绝，事务无残留；原成功同键业务回放保留 |
| token 已撤销、越租户或 participant 不匹配 | 拒绝；不退回匿名主体或当前版本 |
| 绑定时间恰 startsAt / endsAt | startsAt 接受，endsAt 拒绝；窗口为 [startsAt,endsAt) |
| qualify_deadline | `min(boundAt+qualificationWindowSeconds,settlementEndsAt)`；避免时间溢出，来源为固定计划 |
| maxBindAgeSeconds | 必须由签收的令牌创建/有效期合同定义起点并限制；不能自行猜是注册时间或 participant 创建时间；令牌切片未提供明确语义前默认拒绝该计划 |
| 条款 consentVersion 与内容摘要不符/条款未发布 | 拒绝新绑定，不自动使用最新条款；原同意凭证保持不可变 |
| 审计或 Outbox 注入失败 | relation、角色、command、事件全部回滚；重试可正确首次绑定 |
| 新客 NO/UNKNOWN、首单未知、退款先到 | 不改变已接受的邀请人归属；不产生奖励，资格待评估/按策略失败关闭 |
| HMAC 轮换、来源超时、Subject 错租户/水位 | 缺锚/版本错拒绝；异常脱敏；不得猜权威主体 |

## 必须默认关闭的真实边界

真实 BFF issuer/audience/JWKS、签名算法及撤销、machine→tenant/org/shop 许可、canonicalSubject 权威映射及合并、C01 jti 持久重放、历史制品验证/运行代次及 kill-switch、已发布条款同意合同均尚不能由本切片替代。实现独立 BindingPermit/TokenBindingRead Port 的拒绝默认值，不让管理端直接写 READY。当前内部 RequestBinding 的 method/path 不能冒称实际 HTTP 绑定已完成。

隐私仅保留现有密文/HMAC 分离与最小审计约束；真实 KMS、索引初始化/迁移、密文留存、注销与清理仍待确认。本切片不删除任何既有数据，不生成生产默认值。会员新客与订单首单/退款证据由后续资格链路取得，绑定事务不调用远程事实服务。

## 下一实施最小文件范围

仅 `services/referral-service` 新增 `application/ReferralBindingService.java`、`ReferralBindingPermitPort.java`、`ReferralBindingRepository.java`，`domain/ReferralRelation.java`，独立持久 Repository/Mapper/XML，下一空迁移号和 `ReferralBindingIntegrationTest.java`。在 `ReferralConfiguration.java` 追加拒绝默认 Port。令牌只读绑定定位/锁定能力通过明确 Port 扩展，与令牌作者协商接口，不复制其存储逻辑、不修改正在开发的令牌 Service。

复用 TrustedReferralSubjectPort、ReferralInputs、租户索引锚点；确需两个角色排序锁的新方法放绑定专用 Mapper，不改变现有 join 路径锁序或响应。无需修改 control/compiler/runtime SPI、共享认证链、前端、公开路由或奖励模块。实现前先核对最终邀请令牌 schema、字段和锁序；保留全部已完成专项，仅增量运行上述矩阵所需测试并独立复核。
