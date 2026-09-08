# V12 HELD取消同步与在线复核计划

依据交易库冻结 baseline/10-FINAL_PLAN §6.3、§8退款/§12回滚与 09-PRODUCTION_DESIGN §授权取消竞争，以及本库 referral-benefit-intake/PREIMPLEMENTATION_REVIEW。V11已独立终审33项；本轮不重跑该组合。

## 范围与真实性

新增内部 ReferralHeldReviewService.syncCancellation / reviewBeforeDispatch；无Controller、定时扫描器、真实网络客户端或发送。调用侧未来调度可持续调用取消同步；本轮证明重复同步的耐久语义，不声称已部署持续worker。始终保持V11 HELD/CANCEL_HELD；CHECKED仅表示一次可信复核观察，不是发送许可/权益受理或履约。

复用固定机器Scope→ReferralIntakeIdentityPort→全Binding；不能凭sourceRequest普通文本读取或修改。事务外 recover 同一永久Identity，禁止confirm造新消费身份。取消同步独立于当前CENTER投递模式，已受理结果/补偿不能因切回LEGACY停止；在线复核要求CENTER。功能默认关闭，真实端口仍默认拒绝，时窗配置必须显式输入。

## 锁与耐久转换

短事务锁序 prep→intake→review。复用 V11 observe 的完整Identity/receipt/currentRevision/cancelRevision 比较，低水位不可覆盖，同水位不同稳定内容隔离，运输issued/expires刷新不算永久回执冲突。CANCEL_REQUESTED和冲突须正常提交，将已有HELD/expected标CANCEL_HELD；永不擦除原receipt/意图。原成功回放继续返回当前取消栅栏，不重新创建。

新增V12 mk_referral_award_held_review，永久tenant/source/request唯一且外键到V11固定上下文。仅存binding摘要、顺序check版本、状态、current/cancel水位、检查时间及有效截至（微秒+nanos）；无主体/token/明文候选。状态 UNKNOWN/CHECKED/BLOCKED/CANCELLED/QUARANTINED。写入持有context/review锁并CAS旧check版本，全Identity由锁后Binding核对。CANCELLED/QUARANTINED不可被后续有效确认降级；过期复核不是永久许可。

在线复核在事务外解密原候选、查当前风险与确认。只在原ACCEPTED+HELD、当前可信CONFIRMED同永久receipt、风险ALLOW且无任何取消/冲突时记录CHECKED；UNKNOWN/风险非ALLOW只记不可放行的观察，不发送。锁后及最后写入后raw时间复检；有效截至取风险和确认截止的较早值。风险REJECT按既有V11永久拒绝语义耐久记录。取消响应优先持久，不能被风险端口不可用遮住。

确认结果时窗/形状校验抽共享纯helper，V11机械调用保留行为；将仅重跑受影响V11 Intake11兼容专项，不重复旧33组合。

## 测试与门禁

纯测覆盖默认关闭、事务外端口、复核不是发送、旧接受的取消同步、UNKNOWN/低水位/冲突、锁后raw过期、原身份Scope。隔离MySQL专项验证V12迁移/注释、已HELD取消同步耐久、相同回执运输刷新、低水位不能解栅栏、原成功仍回放取消、并发/锁等待过期及复核写入失败原子回滚。DB前与root错峰，无共享数据库。

真实确认来源/风险/KMS/机器权限、运行许可kill switch、持续worker/退避和真正投递瞬间的竞态协议仍待后续接入；本轮CHECKED不能缓存后作为发送授权。真实权益取消/终态未知，不能删除或释放配额，仍保留对账/补偿待办。运营时限、渠道与隐私保留策略不猜。
