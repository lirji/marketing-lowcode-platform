# 永久准备仓储实施设计（预审中）

现有benefit-funding迁移为V1–V9；新增仅V10，不修改旧迁移或Drools abandonEvaluation。此设计不是生产迁移授权或实际渠道签收。

## 唯一身份和不可覆盖

新mk_referral_award_preparation按tenant/sourceSystem/sourceRequest唯一，同时tenant/reward唯一；source固定marketing-referral。稳定claims/payload摘要和qualificationRevision首次冻结。phase/receipt/rejection/本地成功引用永久保存，不提供DELETE。列机器键精确比较，避免默认不区分大小写/尾空格的排序规则改变身份。

仓储写入必须在调用侧已开启的短事务中，先当前读FOR UPDATE，再用注入Clock的原始时间检查领域lease/phase，最后全identity+stateVersion+fence CAS。不能接受任意nextState覆盖。返回冲突quarantine必须由调用侧提交，不能把它转异常导致隔离被回滚。未来准备/receipt/Outbox原子事务另行接入。

## 原始时间与微秒数据库

DATETIME(6)仅保存微秒部分；纳秒余数单独0..999字段，同一个状态行同时读写。Java组合后才进行原始Instant判断，精确保存expiresAt和receipt语义，防止截止延长或同receipt不同纳秒内容被当作相等。SQL不直接用微秒expiry取代raw许可判断，Clock必须在行锁取得后读取。

## 受保护候选纳入本片

准备保存摘要和无主体标识，并以加密快照保证token过期后可恢复完整候选。本片固定保存候选密文，由事务外固定来源ProtectionPort处理，AAD绑定全Identity，默认拒绝。仓储仅持有keyId/cipher/bindingDigest，不保存原token、主体或JSON明文；不得在持锁期间调用KMS。真实密钥和保留期限仍未签收。

## 验证安排

先纯时间往返/非法纳秒/状态转换和离线test-compile；DB专项单独临时MySQL，必须等待root错峰。专项将覆盖唯一键并发、完整Identity冲突、lease到期/旧fence、确认未知接管、receipt冲突隔离、真实行锁后到期、事务回滚不泄漏部分状态、禁止明文与原旧表不变。没有HTTP/真实confirm/Outbox发送。

## 取消与确认上下文门禁

root正在实现的确认恢复结果将同时返回永久receipt与currentState/currentRevision。原receipt在CANCEL_REQUESTED下仍用于恢复/对账，不能自动成为新投递许可。当前纯prepare Receipt尚不含取消状态，本仓储只保存已有模型；未来服务必须显式校验当前取消状态并进入恢复/补偿分支，不能只摘receipt调用acceptLocally。真实本地受理/投递仍未接入。

## 已落源码与当前构建

新增SnapshotService/ProtectionPort（默认拒绝）在事务外执行protect+同AAD解密回验，restore再核原payload hash；使用固定版本CanonicalMapCodec对全Identity编码。随机cipher不同不改变幂等意图，同身份重复prepare永久保留原密文。

2026-09-08 11:42:28 +08:00隔离快照离线12项纯测试通过（保护4+既有状态机8），DB新类8项已编译，尚未启动DB。测试调用的fixture只有本地AES/Ed25519，未引用任何IntegrationTest静态容器。真实MySQL专项等待独立源码预审与root错峰。
