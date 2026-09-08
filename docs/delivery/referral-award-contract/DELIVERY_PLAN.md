# 奖励授权合同切片

## 范围与授权
沿用已批准10-FINAL_PLAN第6.3节奖励授权设计和用户持续并行实施指令。只增加marketing-contracts纯Java声明/Ed25519编解码及专项测试，不增加HTTP、真实签发器、KMS、资格确认或发奖。前端不适用。

## 方案
复用SignedTokenCodec的固定Ed25519和CanonicalMapCodec长度前缀格式，明确这是内部签名格式而非JWT。声明固定完整奖励身份、冻结规则、资格revision及受益人，单个奖励数量固定1，仅允许关系奖或邀请人阶梯奖。校验固定issuer、专用audience、tenant/org/shop及显式配置最大寿命；拒绝未来签发、半开区间过期、未知/缺失字段、非法Unicode、畸形数值及签名。稳定声明摘要包含全部业务声明，排除keyId/issuedAt/expiresAt，支持同奖励重签。未经资格确认的有效签名不等于发奖授权完成。

## 验收
- AC01 真实Ed25519往返与篡改/错钥拒绝。
- AC02 绑定issuer/audience/tenant/org/shop，错误边界拒绝。
- AC03 iat<=now<exp，最大寿命由可信调用侧显式提供，锁后可重复纯时间验证。
- AC04 只有精确声明集合及规范编码可接受，Unicode不修剪归一化。
- AC05 相同业务声明换钥重签稳定摘要不变，任一业务字段改变摘要不同。
- AC06 身份/角色/关系或阶梯/数量验证，toString不暴露受益主体。

## 文件/验证/交付
marketing-contracts/src/{main,test}/java/com/acme/marketing/contracts/referral/；隔离临时后端源码快照，离线指定测试，无数据库。独立审查后只重跑受影响专项。根CI现有clean verify自动包含模块；远程CI未执行。无迁移、部署或删除动作，后续集成可停用新来源并保留持久事实。

## 门禁
真实issuer/key/最长有效期尚未配置；真实签发、永久受理回放、在线资格消费、取消栅栏、风险和冻结SKU重建后续实施，不以本合同通过替代生产验收。

## 统一外部幂等键
独立审查确认符合冻结6.3：sourceRequestId=referral:+SHA256(CanonicalMapCodec{format=marketing-referral-source/1,tenantId,rewardId})。Claims强制与永久reward身份自洽；签名、资格revision、受益信息变更均不能换外部键绕过去重。reward唯一账本本身仍后续实现。
