# 奖励在线授权消费纯边界

依冻结10 §6.3、09 §6.5及05 D15，新增referral侧永久奖励授权纯状态，配合benefit侧永久prepare恢复。仅纯领域，不开放confirm HTTP/签发/DB，不冒称跨服务原子性。

永久Identity含tenant/reward/sourceRequest/稳定claims摘要/首次qualificationRevision；sourceRequest复用既有ReferralAwardIdentity派生。确认需要完整身份一致、当前rowVersion、资格仍有效、资格修订一致，并以同事务当前证据水位=资格计算水位证明未受投影积压影响。候选签名/真实机器身份、许可和风险证明来自事务外可信适配器，纯record不自称完成认证；新确认在锁后用原始Instant复检候选和许可/风险时窗。

第一次确认产生永久Receipt(固定身份、服务端confirmationId/sequence/confirmedAt)，后续同身份请求永久重放原receipt，不依赖旧token/旧钥/过期许可。受信机器认证和Scope核对始终由外层完成；纯类不能通过sourceRequest单键查询或返回他人结果。

取消与确认在同reward锁/rowVersion串行化：先取消时新确认拒绝；先确认时取消标CANCEL_REQUESTED且保留receipt，不能释放未知配额。原确认重放必须同时返回当前取消状态，不能把已确认且待撤销假装仍可新投递。此纯类不实现补偿成功/回调闭环，不把取消请求计成已撤回；资格失效后重新达标不重新分配该reward身份。

验证并发交错的CAS旧版本拒绝、raw nanos边界、旧token过期原确认回放、取消先后顺序、当前证据领先投影时拒绝、身份/摘要/修订冲突。无默认生产时间或清理参数。
